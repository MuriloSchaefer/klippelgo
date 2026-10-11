package org.klippelgo.tests

import org.klippelgo.chain.Deployment
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.chain.RideStatus
import org.klippelgo.core.MapPack
import org.web3j.crypto.Hash
import java.io.File
import java.net.URI

/**
 * Where everything the tests run against is: the chain, the contracts on it, the gossip network, the map pack
 * and the router that reads it, and the phone the app is installed on. Nothing here is a stand-in. `tests/stack.sh
 * up` brings up whatever is missing; the environment says where to find what already exists.
 *
 *   KLIPPELGO_RPC         the chain node, as this computer reaches it (default http://127.0.0.1:8545)
 *   KLIPPELGO_DEPLOYMENT  the contract addresses on it (default deployments/local.json)
 *   KLIPPELGO_PACK        the built map pack (default mappack/build/br-pr-cascavel/pack)
 *   KLIPPELGO_VALHALLA    the router the peer accounts ask for routes on that pack (default http://127.0.0.1:8002)
 *   KLIPPELGO_PHONE       adb serial of the phone under test (default emulator-5570)
 */
class Stack(
    val root: File,
    val rpc: String,
    val deployment: Deployment,
    val packDir: File,
    val valhalla: String,
    val serial: String,
) {
    /** The gossip bootstrap node runs next to the chain node, as the app assumes. */
    val gossip: String = "/ip4/${hostAddress(rpc)}/tcp/4001"

    /** The chain node as an emulator reaches it: this computer is 10.0.2.2 from inside one. */
    val rpcFromPhone: String = URI(rpc).let { if (it.host in LOOPBACK) rpc.replace(it.host, "10.0.2.2") else rpc }

    val adb: String = (System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Android/Sdk") + "/platform-tools/adb"

    /** Where a run leaves its logs, screenshots and measurements. */
    val runDir: File = File(root, "tests/.run").apply { mkdirs() }
    val reportDir: File = File(runDir, "report").apply { mkdirs() }

    val pack: MapPack by lazy { MapPack.open(packDir.path) }

    /** The pack's id: the hash of its manifest, which is what governance activates in MapRegistry. */
    val packId: ByteArray by lazy { Hash.sha3(File(packDir, "manifest.json").readBytes()) }

    val phone: Phone by lazy { Phone(this, serial) }

    /**
     * Every phone the tests may use, the first being [phone]. `PHONES=3 tests/stack.sh up` starts the other
     * two; a test that needs them asks [phonesUp] first.
     */
    val phones: List<Phone> by lazy { listOf(phone, Phone(this, "emulator-5572"), Phone(this, "emulator-5574")) }

    /** How many of [phones] are running, counted from the first. */
    fun phonesUp(): Int = phones.takeWhile { it.booted() }.size

    fun client(account: DevAccount) = KlippelGoClient(rpc, account.privateKey, deployment)

    /**
     * Ends every ride the test accounts left under way, so a test starts with nobody mid-ride. A chain that
     * was used before, by an earlier run or by hand, is the normal case, not an error.
     */
    fun settle() {
        for (account in DevAccount.entries) {
            val rider = client(account)
            for (id in rider.ridesAsRider().take(30)) {
                val ride = rider.ride(id)
                runCatching {
                    when (ride.status) {
                        RideStatus.Open, RideStatus.Accepted -> rider.cancel(id)
                        RideStatus.Started -> rider.complete(id, Hash.sha3("settled by the tests $id".toByteArray()))
                        else -> {}
                    }
                }
            }
        }
    }

    companion object {
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

        /** anvil's chain id. */
        private const val LOCAL_CHAIN = 31337L

        private fun hostAddress(rpc: String): String = java.net.InetAddress.getByName(URI(rpc).host).hostAddress

        val current: Stack by lazy {
            val root = File(System.getProperty("klippelgo.root") ?: System.getenv("KLIPPELGO_ROOT") ?: error("klippelgo.root is not set"))
            fun env(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
            val deployment = File(env("KLIPPELGO_DEPLOYMENT", "$root/deployments/local.json"))
            check(deployment.isFile) { "No deployment at $deployment. Bring the stack up first: tests/stack.sh up" }
            val rpc = env("KLIPPELGO_RPC", "http://127.0.0.1:8545")
            val addresses = Deployment.fromJson(deployment.readText())
            // The tests sign with anvil's public keys and end rides for them. Never on anything but a local testnet.
            val chainId = runCatching { KlippelGoClient(rpc, DevAccount.RIDER.privateKey, addresses).chainId() }
                .getOrElse { error("No chain node answers at $rpc. Bring the stack up first: tests/stack.sh up") }
            check(chainId == LOCAL_CHAIN && addresses.chainId == LOCAL_CHAIN) {
                "The chain at $rpc (id $chainId, deployment for ${addresses.chainId}) is not a local testnet ($LOCAL_CHAIN). The tests will not touch it."
            }
            Stack(
                root = root,
                rpc = rpc,
                deployment = addresses,
                packDir = File(env("KLIPPELGO_PACK", "$root/mappack/build/br-pr-cascavel/pack")),
                valhalla = env("KLIPPELGO_VALHALLA", "http://127.0.0.1:8002"),
                serial = env("KLIPPELGO_PHONE", "emulator-5570"),
            )
        }
    }
}
