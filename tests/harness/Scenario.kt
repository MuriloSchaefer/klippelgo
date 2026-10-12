package org.klippelgo.tests

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.tests.drivers.App
import java.io.File

/**
 * What every test starts from: the running stack, the phone with the app on it, and peers to ride with.
 *
 * The app under test is always the first phone of its role, as it is when a person picks a role on the
 * create-account screen: account #3 as a rider, #4 as a driver. The other seeded accounts play the peers.
 */
@ExtendWith(Scenario.KeepTheScreen::class)
abstract class Scenario {
    val stack: Stack = Stack.current
    val phone: Phone = stack.phone
    val app = App(phone)
    private val opened = mutableListOf<AutoCloseable>()

    /** Reads the chain as anyone can. The key signs nothing here. */
    val chain: KlippelGoClient by lazy { stack.client(DevAccount.RIDER_2) }

    @BeforeEach
    fun cleanSlate(info: TestInfo) {
        println("\n=== ${info.testClass.map { it.simpleName }.orElse("")}: ${info.displayName}")
        stack.settle()
    }

    @AfterEach
    fun closePeers() {
        opened.reversed().forEach { runCatching { it.close() } }
        opened.clear()
    }

    fun peer(account: DevAccount): Peer {
        val peer = Peer(stack, account)
        opened += peer
        waitUntil<Boolean>("${account.name} to reach the gossip network", 20_000) { if (peer.connected()) true else null }
        return peer
    }

    fun stranger(): Stranger = Stranger(stack).also { opened += it }

    /** What the government's tax collector holds of a coin. */
    fun taxCollected(coin: org.klippelgo.chain.Coin): java.math.BigInteger = chain.balanceOf(coin, stack.deployment.taxCollector)

    /** The account the app has when it is opened as a rider, and as a driver. */
    val appRider: String by lazy { stack.client(DevAccount.RIDER).address }
    val appDriver: String by lazy { stack.client(DevAccount.DRIVER).address }

    /**
     * The app as it is on the day it was installed, taken through onboarding by hand as a rider standing in the
     * centre of Cascavel, up to the home screen with the map pack on the phone and the pickup pin on the rider.
     */
    fun freshRider(name: String = "Ana") {
        phone.launch(fresh = true)
        phone.locate(Places.CENTRE.latE7 / 1e7, Places.CENTRE.lonE7 / 1e7)
        app.welcome.start()
        app.createAccount.fill(name, "rider", stack.rpcFromPhone)
        app.createAccount.submit()
        app.funding.skip()
        app.whereTo.shown()
        app.mapNotice.download()
        // The pin goes to where the phone is once the map is there. Only then is the home screen at rest.
        val here = named(Places.CENTRE)
        waitUntil("the pickup pin to be where the phone is, at $here", 60_000) { true.takeIf { app.whereTo.pickup() == here } }
    }

    /** What the map pack calls a spot: what the app shows for a pin there. */
    fun named(at: org.klippelgo.core.Coordinate): String =
        stack.pack.reverse(at)?.name ?: error("the map pack has no name for ${at.latE7 / 1e7}, ${at.lonE7 / 1e7}")

    /** The same for a driver, placed in the centre of Cascavel, up to the offline home screen. */
    fun freshDriver(name: String = "Bruno") {
        phone.launch(fresh = true)
        phone.locate(Places.CENTRE.latE7 / 1e7, Places.CENTRE.lonE7 / 1e7)
        app.welcome.start()
        app.createAccount.fill(name, "driver", stack.rpcFromPhone)
        app.createAccount.submit()
        app.goOnline.shown()
        app.mapNotice.download()
        phone.awaitTag(Tags.GO_ONLINE_SCORE)
    }

    /** Saves what the phone showed when a test failed, next to the report. */
    class KeepTheScreen : TestWatcher {
        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            val stack = runCatching { Stack.current }.getOrNull() ?: return
            val name = context.requiredTestClass.simpleName + "." + context.requiredTestMethod.name
            stack.phones.take(stack.phonesUp()).forEachIndexed { i, phone ->
                val file = File(stack.reportDir, if (i == 0) "$name.png" else "$name.phone${i + 1}.png")
                runCatching { phone.screenshot(file) }.onSuccess { println("The screen at the failure: $file") }
            }
        }
    }
}
