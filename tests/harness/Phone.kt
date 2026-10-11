package org.klippelgo.tests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** One piece of the UI as the phone reports it. `text` is everything written inside it, top to bottom. */
data class Node(
    val tag: String,
    val text: String,
    val description: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val enabled: Boolean,
    /** A picked choice, tab or language. */
    val selected: Boolean,
    val scrollable: Boolean,
) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
    val height get() = bottom - top

    /** The amount written in this node, as in "R$ 1.819,10". */
    fun reais(): BigDecimal = reais(text)
}

/** Reads the first amount in reais out of a text: "Travar R$ 25,40" gives 25.40. */
fun reais(text: String): BigDecimal {
    val match = Regex("""R\$[\s\u00A0]*([\d.]+,\d{2})""").find(text) ?: error("no amount in reais in \"$text\"")
    return BigDecimal(match.groupValues[1].replace(".", "").replace(",", "."))
}

/** What the app shows at one moment: every named piece on screen, and the unnamed areas that scroll. */
class Screen(val nodes: List<Node>, val scrollers: List<Node>) {
    operator fun get(tag: String): Node? = nodes.firstOrNull { it.tag == tag }

    fun has(tag: String) = get(tag) != null

    fun startingWith(prefix: String): List<Node> = nodes.filter { it.tag.startsWith(prefix) }

    /** The screen's own name: the component that fills it. Null while the app shows none the tests know. */
    val name: String? get() = nodes.firstOrNull { it.tag in SCREENS }?.tag

    companion object {
        /** Tags of the components that are a whole screen. */
        val SCREENS = setOf(
            "welcome", "create-account", "funding", "where-to", "wallet", "rules", "profile", "ask", "offers", "confirm",
            "on-the-way", "on-trip", "ride-end", "go-online", "request", "driver-ride",
        )

        fun parse(xml: String): Screen {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            val nodes = mutableListOf<Node>()
            val scrollers = mutableListOf<Node>()
            fun texts(e: Element, into: MutableList<String>) {
                e.getAttribute("text").takeIf { it.isNotBlank() }?.let(into::add)
                val children = e.childNodes
                for (i in 0 until children.length) (children.item(i) as? Element)?.let { texts(it, into) }
            }
            fun walk(e: Element) {
                if (e.tagName == "node" && e.getAttribute("package") == Phone.APP) {
                    val tag = e.getAttribute("resource-id")
                    val scrollable = e.getAttribute("scrollable") == "true"
                    if ((tag.isNotEmpty() && ':' !in tag) || scrollable) {
                        val b = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").matchEntire(e.getAttribute("bounds"))!!.groupValues.drop(1).map(String::toInt)
                        val node = Node(
                            tag = tag,
                            text = mutableListOf<String>().also { texts(e, it) }.joinToString(" "),
                            description = e.getAttribute("content-desc"),
                            left = b[0], top = b[1], right = b[2], bottom = b[3],
                            enabled = e.getAttribute("enabled") == "true",
                            // Compose reports a picked choice as checked and a picked tab as selected.
                            selected = e.getAttribute("selected") == "true" || e.getAttribute("checked") == "true",
                            scrollable = scrollable,
                        )
                        if (tag.isNotEmpty() && ':' !in tag) nodes += node
                        if (scrollable) scrollers += node
                    }
                }
                val children = e.childNodes
                for (i in 0 until children.length) (children.item(i) as? Element)?.let(::walk)
            }
            walk(document.documentElement)
            return Screen(nodes, scrollers)
        }
    }
}

class PhoneException(message: String) : Exception(message)

/**
 * One phone, real or emulated, with the app on it. Everything goes through adb and the UI agent in the app's
 * test APK (`apps/android/app/src/androidTest`): the screen is read as a screen reader would read it, and taps
 * are real touches. Nothing reaches into the app.
 */
class Phone(private val stack: Stack, val serial: String) : AutoCloseable {
    private val json = ObjectMapper()
    private var socket: Socket? = null
    private var port = 0
    private var input: BufferedReader? = null
    private var output: OutputStream? = null

    // ---------------------------------------------------------------- adb

    fun adb(vararg args: String, timeoutSeconds: Long = 60): String {
        val process = ProcessBuilder(listOf(stack.adb, "-s", serial) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw PhoneException("adb ${args.joinToString(" ")} did not finish in $timeoutSeconds s")
        }
        if (process.exitValue() != 0) throw PhoneException("adb ${args.joinToString(" ")} failed: ${out.trim()}")
        return out
    }

    fun shell(command: String): String = adb("shell", command)

    /** Tells the emulator where it is. A driver's phone reads its position from this. */
    fun locate(latitude: Double, longitude: Double) {
        adb("emu", "geo", "fix", longitude.toString(), latitude.toString())
    }

    fun screenshot(file: File) {
        val process = ProcessBuilder(stack.adb, "-s", serial, "exec-out", "screencap", "-p").redirectOutput(file).start()
        process.waitFor(30, TimeUnit.SECONDS)
    }

    /** Megabytes of memory the app holds, as Android counts it (PSS). */
    fun memoryMb(): Int {
        val total = shell("dumpsys meminfo $APP").lineSequence().firstOrNull { it.trim().startsWith("TOTAL PSS:") || it.trim().startsWith("TOTAL ") }
            ?: throw PhoneException("the app is not running")
        return Regex("""\d+""").find(total)!!.value.toInt() / 1024
    }

    // ---------------------------------------------------------------- the app's life

    /**
     * Starts the app, and the agent with it. `fresh` wipes the app first: no account, no map pack, as on the
     * day it was installed. Otherwise it comes back the way a person finds it after closing it. With a `role`
     * (rider or driver) it skips the welcome and starts in that role, as account `pair` (1 or 2) of it. Returns
     * how long Android took to draw the first screen, in milliseconds.
     */
    fun launch(fresh: Boolean = false, role: String? = null, pair: Int? = null): Long {
        requireTestDevice()
        stop()
        if (fresh) shell("pm clear $APP")
        // The system's own permission dialog is not the app's UI. Answer it ahead of time, as "allow".
        shell("pm grant $APP android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant $APP android.permission.ACCESS_COARSE_LOCATION")
        shell("nohup am instrument -w -e class $APP.agent.UiAgent $APP.test/androidx.test.runner.AndroidJUnitRunner >/dev/null 2>&1 &")
        waitUntil("the UI agent to answer", 30_000) { runCatching { ask("op" to "dump") }.getOrNull() }
        // A scripted launch, as scripts/two-phones.sh does it: the role to start in, and which of the two
        // seeded accounts of that role. The create-account screen only ever gives the first.
        val extras = if (role == null) "" else " --es role $role --es rpc ${stack.rpcFromPhone}" + (pair?.let { " --ei pair $it" } ?: "")
        val started = shell("am start -W -n $APP/.MainActivity$extras")
        return Regex("""TotalTime:\s*(\d+)""").find(started)?.groupValues?.get(1)?.toLong()
            ?: throw PhoneException("the app did not start: ${started.trim()}")
    }

    private var checked = false

    /**
     * The tests wipe the app again and again, and the UI agent can drive the screen. That is for an emulator,
     * never for a phone someone uses: a real device has to be vouched for with KLIPPELGO_ALLOW_DEVICE=1.
     */
    private fun requireTestDevice() {
        if (checked) return
        if (shell("getprop ro.kernel.qemu").trim() != "1" && System.getenv("KLIPPELGO_ALLOW_DEVICE") != "1") {
            throw PhoneException("$serial is not an emulator. The tests wipe the app's data and start an agent that can drive the screen. If this is a device kept for testing, say so: KLIPPELGO_ALLOW_DEVICE=1")
        }
        checked = true
    }

    /** Closes the app the way swiping it away does. What it kept in memory is gone. */
    fun stop() {
        disconnect()
        // Leave the screen first: the emulator's graphics can crash when the app dies with the map showing.
        shell("input keyevent KEYCODE_HOME")
        shell("am force-stop $APP")
    }

    /** Whether this phone is there and has finished starting. */
    fun booted(): Boolean = runCatching { adb("shell", "getprop sys.boot_completed", timeoutSeconds = 10).trim() == "1" }.getOrDefault(false)

    val running: Boolean get() = runCatching { ask("op" to "dump") }.isSuccess

    // ---------------------------------------------------------------- the agent

    private fun connect() {
        if (socket != null) return
        port = adb("forward", "tcp:0", "localabstract:klippelgo-ui-agent").trim().toInt()
        socket = Socket("127.0.0.1", port).also {
            it.soTimeout = 20_000
            input = it.getInputStream().bufferedReader()
            output = it.getOutputStream()
        }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        // Each connection asked adb for a port of its own; give it back.
        if (port != 0) runCatching { adb("forward", "--remove", "tcp:$port") }
        port = 0
    }

    @Synchronized
    private fun ask(vararg fields: Pair<String, Any>): JsonNode {
        try {
            connect()
            output!!.write((json.writeValueAsString(mapOf(*fields)) + "\n").toByteArray())
            output!!.flush()
            val line = input!!.readLine() ?: throw PhoneException("the UI agent is not running on $serial. Start the app: klippelgo launch")
            val answer = json.readTree(line)
            if (!answer.get("ok").asBoolean()) throw PhoneException(answer.get("error").asText())
            return answer
        } catch (e: java.io.IOException) {
            disconnect()
            throw PhoneException("lost the UI agent on $serial: ${e.message}")
        } catch (e: PhoneException) {
            if (e.message!!.startsWith("the UI agent")) disconnect()
            throw e
        }
    }

    // ---------------------------------------------------------------- looking

    fun screen(): Screen = Screen.parse(ask("op" to "dump").get("xml").asText())

    fun find(tag: String): Node? = screen()[tag]

    /** Waits until `check` gives something, looking at the screen as often as the phone answers. */
    fun <T : Any> await(what: String, timeoutMs: Long = TIMEOUT_MS, check: (Screen) -> T?): T {
        var last: Screen? = null
        return waitUntil(what, timeoutMs, onTimeout = { "On screen: " + (last?.let(::describe) ?: "nothing readable") }) {
            screen().also { last = it }.let(check)
        }
    }

    fun awaitTag(tag: String, timeoutMs: Long = TIMEOUT_MS): Node = await("\"$tag\" to show", timeoutMs) { it[tag] }

    fun awaitGone(tag: String, timeoutMs: Long = TIMEOUT_MS) {
        await("\"$tag\" to go away", timeoutMs) { screen -> true.takeIf { !screen.has(tag) } }
    }

    /** The text of a piece on screen, scrolling to it if needed. */
    fun read(tag: String): String = reach(tag).text

    private fun describe(screen: Screen) = (screen.name ?: "no known screen") + " with " + screen.nodes.joinToString(", ") { it.tag }

    // ---------------------------------------------------------------- touching

    /**
     * The piece with this tag, brought on screen by scrolling whatever scrolls if it is not there. A piece
     * that is in the app but under the fold is not on screen for a person either.
     */
    fun reach(tag: String, timeoutMs: Long = TIMEOUT_MS): Node {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            visible(tag)?.let { return it }
            // Down the page, then back up, in whatever scrolls. The map does not count: moving it moves the pin.
            for (direction in listOf(1, -1)) {
                repeat(8) {
                    val before = screen()
                    val area = before.scrollers.filter { it.tag != "map" }.maxByOrNull { it.height } ?: return@repeat
                    val from = area.y + direction * area.height / 4
                    val to = area.y - direction * area.height / 4
                    ask("op" to "swipe", "x1" to area.x, "y1" to from, "x2" to area.x, "y2" to to, "steps" to 30)
                    Thread.sleep(250)
                    visible(tag)?.let { return it }
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw PhoneException("\"$tag\" is not on screen. On screen: ${describe(screen())}")
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** On screen with enough of it showing to read and to touch. */
    private fun visible(tag: String): Node? = screen()[tag]?.takeIf { it.height >= 24 }

    fun tap(tag: String, timeoutMs: Long = TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var node = reach(tag, timeoutMs)
        // A button that is busy or not yet allowed is there but does nothing. Wait for it, as a person would.
        while (!node.enabled) {
            if (System.currentTimeMillis() > deadline) throw PhoneException("\"$tag\" is on screen but disabled")
            Thread.sleep(POLL_MS)
            node = reach(tag, timeoutMs)
        }
        ask("op" to "tap", "x" to node.x, "y" to node.y)
    }

    fun tapAt(x: Int, y: Int) {
        ask("op" to "tap", "x" to x, "y" to y)
    }

    /** Replaces what a text field holds. */
    fun type(tag: String, text: String) {
        reach(tag)
        ask("op" to "type", "tag" to tag, "text" to text)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, steps: Int = 30) {
        ask("op" to "swipe", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "steps" to steps)
    }

    fun back() {
        ask("op" to "back")
    }

    override fun close() = disconnect()

    companion object {
        const val APP = "org.klippelgo.app"

        /** How long a piece of UI may take to show. A transaction waits for a block, and the app looks every 2 s. */
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 150L
    }
}

/** Waits for `check` to give something, or fails saying what it was waiting for. */
fun <T : Any> waitUntil(what: String, timeoutMs: Long = 30_000, pollMs: Long = Phone.POLL_MS, onTimeout: () -> String = { "" }, check: () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    var failure: Throwable? = null
    while (true) {
        try {
            check()?.let { return it }
        } catch (e: PhoneException) {
            failure = e
        }
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError("Timed out after ${timeoutMs / 1000} s waiting for $what. ${onTimeout()}" + (failure?.let { " Last error: ${it.message}" } ?: ""))
        }
        Thread.sleep(pollMs)
    }
}
