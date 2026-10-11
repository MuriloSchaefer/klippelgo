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

    /** The account the app has when it is opened as a rider, and as a driver. */
    val appRider: String by lazy { stack.client(DevAccount.RIDER).address }
    val appDriver: String by lazy { stack.client(DevAccount.DRIVER).address }

    /**
     * The app as it is on the day it was installed, taken through onboarding by hand as a rider, up to the home
     * screen with the map pack on the phone.
     */
    fun freshRider(name: String = "Ana") {
        phone.launch(fresh = true)
        app.welcome.start()
        app.createAccount.fill(name, "rider", stack.rpcFromPhone)
        app.createAccount.submit()
        app.funding.skip()
        app.whereTo.shown()
        app.mapNotice.download()
    }

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
