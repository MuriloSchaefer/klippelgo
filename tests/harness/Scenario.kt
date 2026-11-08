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
 * create-account screen: `DevAccount.E2E_RIDER` as a rider, `E2E_DRIVER` as a driver. The tests' other seeded
 * accounts play the peers. None of them is an account phones played by hand use, so those are strangers here.
 */
@ExtendWith(Scenario.KeepTheScreen::class)
abstract class Scenario {
    val stack: Stack = Stack.current
    val phone: Phone = stack.phone
    val app = App(phone)
    private val opened = mutableListOf<AutoCloseable>()

    /** This test's peers, kept after they are closed so that a failure can say what each one held. */
    private val peers = mutableListOf<Peer>()

    /** Reads the chain as anyone can. The key signs nothing here. */
    val chain: KlippelGoClient by lazy { stack.client(DevAccount.E2E_RIDER_2) }

    @BeforeEach
    fun cleanSlate(info: TestInfo) {
        println("\n=== ${info.testClass.map { it.simpleName }.orElse("")}: ${info.displayName}")
        // The app the last test left running goes on announcing its request for a while, under the same account
        // this test's app has: a peer made before this test launches the app would take it for this test's.
        // Every test launches the app itself, so stop it on every phone before anyone joins the network.
        stack.phones.take(stack.phonesUp()).forEach { runCatching { it.stop() } }
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
        peers += peer
        waitUntil<Boolean>("${account.name} to reach the gossip network", 20_000) { if (peer.connected()) true else null }
        return peer
    }

    fun stranger(): Stranger = Stranger(stack).also { opened += it }

    /** Instituto Klippel's team council, through `klippel-gov`. What a test changes with it is put back after. */
    fun governance(): Governance = Governance(stack).also { opened += it }

    /**
     * A rider Instituto Klippel blocked: an account made on the spot (a new device key, a few test reais in, then
     * its first operation registers it as a rider), then blocked the way Segurança does it, with `gov/klippel-gov`.
     */
    fun blockedRider(): KlippelGoClient {
        val key = org.klippelgo.chain.SoftwareDeviceKey(
            java.math.BigInteger(256, java.security.SecureRandom()).mod(org.klippelgo.chain.P256.N - java.math.BigInteger.ONE) + java.math.BigInteger.ONE,
        )
        val rider = KlippelGoClient(stack.rpc, key, stack.deployment)
        rider.mint(rider.brz, rider.brz.toBaseUnits(java.math.BigDecimal("50.00")))
        rider.ensureRegistered(KlippelGoClient.ROLE_RIDER)
        check(chain.canOpenRides(rider.address)) { "the new rider should be able to open rides before the block" }
        stack.script("gov/klippel-gov", "block", rider.address)
        check(!chain.canOpenRides(rider.address)) { "the rider was blocked and can still open rides" }
        return rider
    }

    /**
     * Another device of a person's, played in process: a key of its own, signing for `joined` when given (an account
     * another device made and added this key to), else for the account this key makes.
     */
    fun device(joined: String? = null, key: org.klippelgo.chain.DeviceKey = newKey()): KlippelGoClient =
        KlippelGoClient(stack.rpc, key, stack.deployment, joined = joined)

    /** A person's account made on the spot by a device of theirs, with R$ 50 in and registered as a rider. */
    fun personWithAccount(): KlippelGoClient = device().also {
        it.mint(it.brz, it.brz.toBaseUnits(java.math.BigDecimal("50.00")))
        it.ensureRegistered(KlippelGoClient.ROLE_RIDER)
    }

    /** A new P-256 key, as a new phone's chip makes one. */
    fun newKey(): org.klippelgo.chain.DeviceKey = org.klippelgo.chain.SoftwareDeviceKey(
        java.math.BigInteger(256, java.security.SecureRandom()).mod(org.klippelgo.chain.P256.N - java.math.BigInteger.ONE) + java.math.BigInteger.ONE,
    )

    /** The signer entry a device's key code stands for, as the phone that adds it reads the code. */
    fun signerOf(device: KlippelGoClient): ByteArray =
        org.klippelgo.chain.KeyCodes.signerOf(device.keyCode, stack.deployment.deviceKeyVerifier)!!

    /**
     * Moves the local testnet's clock on and mines a block at the new time: what a guardian's two days of waiting
     * take. Every later test sees the chain that much older, which none of them counts on.
     */
    fun passTime(seconds: Long) {
        stack.script("cast", "rpc", "--rpc-url", stack.rpc, "evm_increaseTime", seconds.toString())
        stack.script("cast", "rpc", "--rpc-url", stack.rpc, "evm_mine")
    }

    /** The chain's latest block, to count an account's gas from ([gasPaid]). */
    fun mark(): java.math.BigInteger = chain.blockNumber()

    /**
     * What `account` paid the network in `coin` since block `from`: every account pays its gas in reais through
     * the paymaster, which takes the most an operation could cost and gives back what it did not use. So it is
     * what went from the account to the paymaster, less what came back, read from the coin's Transfer events.
     */
    fun gasPaid(account: String, coin: org.klippelgo.chain.Coin, from: java.math.BigInteger): java.math.BigInteger {
        fun moved(src: String, dst: String): java.math.BigInteger {
            val logs = stack.script(
                "cast", "logs", "--json", "--rpc-url", stack.rpc, "--from-block", from.toString(), "--address", coin.address,
                "Transfer(address indexed,address indexed,uint256)", src, dst,
            )
            return com.fasterxml.jackson.databind.ObjectMapper().readTree(logs)
                .fold(java.math.BigInteger.ZERO) { sum, log -> sum + org.web3j.utils.Numeric.toBigInt(log.get("data").asText()) }
        }
        val paymaster = stack.deployment.paymaster
        return moved(account, paymaster) - moved(paymaster, account)
    }

    /** What the government's tax collector holds of a coin. */
    fun taxCollected(coin: org.klippelgo.chain.Coin): java.math.BigInteger = chain.balanceOf(coin, stack.deployment.taxCollector)

    /** The account the app has when it is opened as a rider, and as a driver. */
    val appRider: String by lazy { stack.client(DevAccount.E2E_RIDER).address }
    val appDriver: String by lazy { stack.client(DevAccount.E2E_DRIVER).address }

    /**
     * The app as it is on the day it was installed, taken through onboarding by hand as a rider standing in the
     * centre of Cascavel, up to the home screen with the map pack on the phone and the pickup pin on the rider.
     */
    fun freshRider(name: String = "Ana", checkRpc: String = "") {
        phone.launch(fresh = true)
        phone.locate(Places.CENTRE.latE7 / 1e7, Places.CENTRE.lonE7 / 1e7)
        app.welcome.start()
        app.createAccount.fill(name, "rider", stack.rpcFromPhone, checkRpc)
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

    /**
     * Saves what the phone showed when a test failed, next to the report: the screen, what the app traced since it
     * was launched (debug builds trace gossip and chain reads under one tag), and what each peer's matchmaker held.
     */
    class KeepTheScreen : TestWatcher {
        /** `AppViewModel.TRACE_TAG`: the harness compiles nothing of the app's but its tags. */
        private val TRACE_TAG = "KlippelGo"

        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            val stack = runCatching { Stack.current }.getOrNull() ?: return
            val name = context.requiredTestClass.simpleName + "." + context.requiredTestMethod.name
            stack.phones.take(stack.phonesUp()).forEachIndexed { i, phone ->
                val suffix = if (i == 0) "" else ".phone${i + 1}"
                val file = File(stack.reportDir, "$name$suffix.png")
                runCatching { phone.screenshot(file) }.onSuccess { println("The screen at the failure: $file") }
                val trace = File(stack.reportDir, "$name$suffix.trace.txt")
                runCatching { trace.writeText(phone.adb("logcat", "-d", "-v", "time", "-s", "$TRACE_TAG:D")) }
                    .onSuccess { println("What the app traced: $trace") }
            }
            val peers = (context.testInstance.orElse(null) as? Scenario)?.peers.orEmpty()
            if (peers.isNotEmpty()) {
                val file = File(stack.reportDir, "$name.peers.txt")
                runCatching { file.writeText(peers.joinToString("\n") { it.match.describe() }) }
                    .onSuccess { println("What the peers held: $file") }
            }
        }
    }
}
