package org.klippelgo.tests.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.klippelgo.chain.DevAccount
import org.klippelgo.tests.Peer
import org.klippelgo.tests.PhoneException
import org.klippelgo.tests.Stack
import org.klippelgo.tests.drivers.App
import java.io.File
import java.math.BigInteger
import kotlin.system.exitProcess

private val HELP = """
klippelgo: use the KlippelGo app on a phone the way a person does, from a command line.

The app
  klippelgo launch [fresh]            start the app; "fresh" wipes it first, as on the day it was installed
  klippelgo stop                      close the app
  klippelgo screen                    which screen is showing and every named piece on it, as JSON
  klippelgo shot <file.png>           a picture of the screen
  klippelgo locate <lat> <lon>        tell the emulator where it is

One piece of the UI (each has a driver in tests/harness/drivers)
  klippelgo ui                        every component and what can be done with it
  klippelgo ui <component>            one component's commands
  klippelgo ui <component> <command> [arguments]

By tag, for what no driver covers yet
  klippelgo tap <tag> | read <tag> | type <tag> <text> | wait <tag> | gone <tag> | back

The other people in the ride (accounts: rider, driver, rider2, driver2)
  klippelgo peers start|stop|status   peers keep keys in memory and must keep announcing, so they run in the background
  klippelgo peer                      what a peer can do
  klippelgo peer <account> <command> [arguments]

The chain
  klippelgo chain ride <id>           a ride as the escrow holds it
  klippelgo chain balance <account>   an account's BRZ balance
  klippelgo chain settle              end every ride the test accounts left under way

The app's own account is "rider" (#3) when it was opened as a rider and "driver" (#4) as a driver, so play the
other side with a different one. The phone is KLIPPELGO_PHONE (default emulator-5570). Bring everything up
first with tests/stack.sh up.
""".trimIndent()

private val json = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

private fun show(result: Any?) {
    when (result) {
        null, Unit, is com.fasterxml.jackson.databind.node.NullNode -> println("ok")
        is String -> println(result)
        else -> println(json.writeValueAsString(result))
    }
}

fun main(args: Array<String>) {
    try {
        run(args.toList())
        exitProcess(0)
    } catch (e: UsageException) {
        System.err.println(e.message)
        exitProcess(2)
    } catch (e: PhoneException) {
        System.err.println(e.message)
        exitProcess(1)
    } catch (e: AssertionError) {
        System.err.println(e.message)
        exitProcess(1)
    } catch (e: IllegalStateException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}

private fun run(args: List<String>) {
    val command = args.firstOrNull() ?: return println(HELP)
    val rest = args.drop(1)
    fun word(i: Int, name: String) = rest.getOrNull(i) ?: throw UsageException("Missing <$name>. See: klippelgo help")
    if (command in setOf("help", "-h", "--help")) return println(HELP)
    val stack = Stack.current
    when (command) {
        "launch" -> println("drawn in ${stack.phone.launch(fresh = rest.firstOrNull() == "fresh")} ms")
        "stop" -> show(stack.phone.stop())
        "screen" -> stack.phone.screen().let { screen ->
            show(
                mapOf(
                    "screen" to screen.name,
                    "pieces" to screen.nodes.map { n ->
                        // A whole screen's text is every text on it; the pieces below say the same, one by one.
                        linkedMapOf<String, Any>("tag" to n.tag, "text" to if (n.tag == screen.name) "" else n.text).also {
                            if (!n.enabled) it["enabled"] = false
                            if (n.selected) it["selected"] = true
                        }
                    },
                ),
            )
        }
        "shot" -> File(word(0, "file.png")).let { stack.phone.screenshot(it); println(it.absolutePath) }
        "locate" -> show(stack.phone.locate(word(0, "lat").toDouble(), word(1, "lon").toDouble()))
        "tap" -> show(stack.phone.tap(word(0, "tag")))
        "read" -> show(stack.phone.read(word(0, "tag")))
        "type" -> show(stack.phone.type(word(0, "tag"), rest.drop(1).joinToString(" ")))
        "wait" -> show(stack.phone.awaitTag(word(0, "tag")).text)
        "gone" -> show(stack.phone.awaitGone(word(0, "tag")))
        "back" -> show(stack.phone.back())
        "ui" -> {
            val app = App(stack.phone)
            val name = rest.firstOrNull() ?: return app.components.forEach { (name, driver) -> describe(name, driver) }
            val driver = app.components[name] ?: throw UsageException("No component \"$name\". There is: ${app.components.keys.joinToString(", ")}")
            val action = rest.getOrNull(1) ?: return describe(name, driver)
            show(call(driver, action, rest.drop(2)))
        }
        "peers" -> when (word(0, "start|stop|status")) {
            "serve" -> PeerDaemon.serve(stack)
            "start" -> {
                if (PeerDaemon.running(stack)) return println("already running")
                // The same program, left running in the background.
                val java = File(System.getProperty("java.home"), "bin/java").path
                ProcessBuilder(
                    java, "-Djna.library.path=${System.getProperty("jna.library.path")}", "-Dklippelgo.root=${stack.root.path}",
                    "-cp", System.getProperty("java.class.path"), "org.klippelgo.tests.cli.CliKt", "peers", "serve",
                ).redirectErrorStream(true).redirectOutput(File(stack.runDir, "peers.log")).start()
                org.klippelgo.tests.waitUntil("the peers to start", 30_000) { true.takeIf { PeerDaemon.running(stack) } }
                println("started; log in ${File(stack.runDir, "peers.log")}")
            }
            "stop" -> show(runCatching { PeerDaemon.send(stack, mapOf("stop" to true)) }.map { "stopped" }.getOrDefault("not running"))
            "status" -> println(if (PeerDaemon.running(stack)) "running" else "not running")
            else -> throw UsageException("klippelgo peers start|stop|status")
        }
        "peer" -> {
            val account = rest.firstOrNull()
            val action = rest.getOrNull(1)
            if (account == null || action == null) {
                println("klippelgo peer <${PeerDaemon.ACCOUNTS.keys.joinToString("|")}> <command> [arguments]")
                return commandsOf(PeerSession(Peer(stack, DevAccount.RIDER_2))).forEach { println("  " + usage(it)) }
            }
            show(PeerDaemon.send(stack, mapOf("peer" to account, "fn" to action, "args" to rest.drop(2))))
        }
        "chain" -> {
            val reader = stack.client(DevAccount.RIDER_2)
            when (word(0, "ride|balance|settle")) {
                "ride" -> show(reader.ride(BigInteger(word(1, "id"))))
                "balance" -> {
                    val account = PeerDaemon.ACCOUNTS[word(1, "account")] ?: throw UsageException("No such account")
                    show(reader.brz.toReais(reader.balanceOf(reader.brz, stack.client(account).address)).toPlainString())
                }
                "settle" -> show(stack.settle())
                else -> throw UsageException("klippelgo chain ride <id> | balance <account> | settle")
            }
        }
        else -> throw UsageException("Unknown command \"$command\".\n\n$HELP")
    }
}

private fun describe(name: String, driver: Any) {
    println(name)
    commandsOf(driver).forEach { println("  " + usage(it)) }
}
