package org.klippelgo.chain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import org.web3j.crypto.Hash
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A full read checked against a second provider (A18 in apps/docs/security.md): the read pinned to one block,
 * and what it gave the app compared with what the second provider says of that block. Either provider here is
 * the chain node, honest, or behind a proxy that lies about one kind of answer.
 *
 * Runs when KLIPPELGO_RPC is set, as for [TwoPhonesRideTest].
 */
class CrossCheckTest {
    private val rpc = System.getenv("KLIPPELGO_RPC").orEmpty()
    private val deployment = System.getenv("KLIPPELGO_DEPLOYMENT").orEmpty()
        .takeIf { it.isNotBlank() }?.let { Deployment.fromJson(File(it).readText()) } ?: Deployment.LOCAL_DEFAULT

    /** A full read of `c` pinned to `block`, as the app makes it, and what it gave. */
    private fun fullRead(c: KlippelGoClient, block: Long, rides: List<java.math.BigInteger>): Shown = c.at(block) {
        c.refreshRates()
        Shown(
            rides = c.rides(rides),
            balances = c.coins.associateWith { c.balanceOf(it) },
            configs = c.coins.associateWith { c.tokenConfig(it) },
            taxBps = c.taxBps(),
            tariff = c.tariff,
            release = c.releaseCheck(),
            canAccept = null,
            busy = emptyMap(),
        )
    }

    @Test
    fun aLieByEitherProviderAboutWhatTheAppUsesIsCaught() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val rider = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val driver = KlippelGoClient(rpc, DevAccount.DRIVER_2.key(), deployment)
        val id = rider.openRide(driver.address, rider.brz, rider.brz.toBaseUnits(BigDecimal("10.00")), Hash.sha3("ride for the cross-check ${System.nanoTime()}".toByteArray()))
        val block = rider.blockNumber().toLong()
        val shown = fullRead(rider, block, listOf(id))

        assertEquals(ProviderCheck.Agree(block), rider.crossCheck(rpc, block, shown))

        // The second provider lies about the tariff, then the ride's status, then the balance, then the tax.
        for ((lie, what) in listOf(("tariff()" to 0) to "tariff", ("ride(uint256)" to 3) to "ride $id", ("balanceOf(address)" to 0) to "balance BRZ", ("taxBps()" to 0) to "tax")) {
            LyingRpc(rpc, lie.first, lie.second).use { liar ->
                val result = rider.crossCheck(liar.url, block, shown)
                assertIs<ProviderCheck.Disagree>(result, "a lie about $what")
                assertTrue(what in result.what, "a lie about $what is named: ${result.what}")
            }
        }

        // The main provider lies once about the tariff and the release, then tells the truth: what the app got is
        // what is compared, not the main provider asked again.
        for ((function, what) in listOf("tariff()" to "tariff", "release(uint48)" to "release")) {
            if (what == "release" && (deployment.releases.isBlank() || deployment.versionKey == 0L)) continue
            LyingRpc(rpc, function, if (what == "release") 1 else 0, once = true).use { liar ->
                val main = KlippelGoClient(liar.url, DevAccount.RIDER_2.key(), deployment)
                val lied = fullRead(main, block, listOf(id))
                val result = main.crossCheck(rpc, block, lied)
                assertIs<ProviderCheck.Disagree>(result, "a lie told once about $what")
                assertTrue(what in result.what, "named: ${result.what}")
            }
        }

        // The main provider lies only to reads of the newest block. The full read's pinned reads replace what the
        // app had from those, so it goes on with the honest tariff, and that is what is checked.
        LyingRpc(rpc, "tariff()", onlyLatest = true).use { liar ->
            val main = KlippelGoClient(liar.url, DevAccount.RIDER_2.key(), deployment)
            val lie = main.tariff
            assertNotEquals(shown.tariff, lie)
            val read = fullRead(main, block, listOf(id))
            assertEquals(shown.tariff, main.tariff, "after a full read the app prices on the tariff it checked")
            assertEquals(ProviderCheck.Agree(block), main.crossCheck(rpc, block, read))
        }

        // A screen that shows another ride state than the chain's disagrees.
        val wrong = shown.copy(rides = shown.rides.map { it.copy(status = RideStatus.Accepted) })
        assertTrue("ride $id" in (rider.crossCheck(rpc, block, wrong) as ProviderCheck.Disagree).what)

        // One that does not answer says nothing either way.
        assertIs<ProviderCheck.Unreachable>(rider.crossCheck("http://127.0.0.1:9", block, shown))

        // A ride that ended after the pinned block is read as it was there, not from what was learned later.
        rider.cancel(id)
        assertEquals(RideStatus.Cancelled, rider.rides(listOf(id)).single().status)
        assertEquals(RideStatus.Open, rider.at(block) { rider.rides(listOf(id)) }.single().status)
        assertEquals(ProviderCheck.Agree(block), rider.crossCheck(rpc, block, fullRead(rider, block, listOf(id))))
    }

    @Test
    fun anotherHashForTheBlockIsUnknownOnceAndADisagreementTwice() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val c = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val block = c.blockNumber().toLong()
        val shown = fullRead(c, block, emptyList())
        LyingRpc(rpc, method = "eth_getBlockByNumber").use { liar ->
            assertIs<ProviderCheck.Unreachable>(c.crossCheck(liar.url, block, shown))
            assertEquals(listOf("block"), (c.crossCheck(liar.url, block, shown) as ProviderCheck.Disagree).what)
        }
        // Agreeing again starts the count over.
        assertEquals(ProviderCheck.Agree(block), c.crossCheck(rpc, block, shown))
    }

    @Test
    fun aSecondProviderNamingAnOldHeadCannotWindTheReadBack() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val c = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val head = c.blockNumber().toLong()
        // Honest, it is at the main head, or a little behind it.
        assertTrue(head - c.pinFor(rpc, head) in 0..30)
        // Naming a head 100 blocks back, the read stays at the main head, and the check disagrees on the block.
        LyingRpc(rpc, method = "eth_blockNumber", headBehind = 100).use { liar ->
            val block = c.pinFor(liar.url, head)
            assertEquals(head, block)
            val result = c.crossCheck(liar.url, block, fullRead(c, block, emptyList()))
            assertEquals(listOf("block"), (result as ProviderCheck.Disagree).what)
        }
        // A few blocks behind, it is used, but never below a block an earlier read was pinned to.
        val fresh = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        assertEquals(head, fresh.pinFor(rpc, head))
        LyingRpc(rpc, method = "eth_blockNumber", headBehind = 5).use { liar ->
            assertEquals(head, fresh.pinFor(liar.url, head))
        }
    }

    @Test
    fun anUpdateIsInstalledOnlyWhenBothProvidersNameIt() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val c = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        if (deployment.releases.isBlank() || deployment.versionKey == 0L) return println("no release registry, skipping")
        val file = File.createTempFile("not-an-apk", ".apk").apply { writeText("nothing anyone published"); deleteOnExit() }
        // A file nobody published is refused whatever the second provider says, even if it says yes.
        LyingRpc(rpc, "isApk(uint48,bytes32)").use { liar -> assertEquals(false, c.publishedApk(deployment.versionKey, file, liar.url)) }
        // And one the main provider says yes to is still refused when the second says no or cannot be asked.
        LyingRpc(rpc, "isApk(uint48,bytes32)").use { liar ->
            val main = KlippelGoClient(liar.url, DevAccount.RIDER_2.key(), deployment)
            assertEquals(false, main.publishedApk(deployment.versionKey, file, rpc))
            assertEquals(false, main.publishedApk(deployment.versionKey, file, "http://127.0.0.1:9"))
        }
    }
}

/**
 * A JSON-RPC proxy in front of `upstream` that lies. With `function`, it changes the answer to every `eth_call` of
 * it: the last byte of its 32-byte word `word` is flipped; `once` lies to the first such call for each block and
 * calldata only, `onlyLatest` only to calls of the newest block. With `method`, it changes the `hash` of every
 * answer to that method instead, or for `eth_blockNumber` answers `headBehind` blocks less than the newest.
 * Everything else passes through as it is.
 */
class LyingRpc(
    private val upstream: String,
    function: String = "",
    private val word: Int = 0,
    private val once: Boolean = false,
    private val onlyLatest: Boolean = false,
    private val method: String = "eth_call",
    private val headBehind: Long = 0,
) : AutoCloseable {
    private val selector = if (function.isEmpty()) "" else Hash.sha3String(function).take(10)
    private val told = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val json = ObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes()
            val conn = URI(upstream).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
            val answer = conn.inputStream.use { it.readBytes() }
            val out = json.writeValueAsBytes(lie(json.readTree(body), json.readTree(answer)))
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    private fun lie(request: JsonNode, answer: JsonNode): JsonNode {
        if (request.isArray) {
            val byId = request.associateBy { it.get("id").asText() }
            answer.forEach { a -> byId[a.get("id").asText()]?.let { lieOne(it, a) } }
        } else {
            lieOne(request, answer)
        }
        return answer
    }

    private fun lieOne(request: JsonNode, answer: JsonNode) {
        if (request.get("method")?.asText() != method) return
        if (method == "eth_blockNumber") {
            val head = answer.get("result")?.asText()?.let { java.math.BigInteger(it.removePrefix("0x"), 16) } ?: return
            (answer as ObjectNode).put("result", "0x" + (head - java.math.BigInteger.valueOf(headBehind)).max(java.math.BigInteger.ZERO).toString(16))
            return
        }
        if (method != "eth_call") {
            val result = answer.get("result") as? ObjectNode ?: return
            result.put("hash", "0x" + "ab".repeat(32))
            return
        }
        val params = request.get("params") ?: return
        val data = params.get(0)?.let { it.get("data") ?: it.get("input") }?.asText() ?: return
        if (!data.startsWith(selector)) return
        val block = params.get(1)?.asText() ?: "latest"
        if (onlyLatest && block != "latest") return
        if (once && !told.add("$block:$data")) return
        val result = answer.get("result")?.asText() ?: return
        val at = 2 + 64 * (word + 1) - 2
        if (result.length < at + 2) return
        val flipped = result.substring(at, at + 2).toInt(16) xor 0x01
        (answer as ObjectNode).put("result", result.substring(0, at) + "%02x".format(flipped) + result.substring(at + 2))
    }

    override fun close() = server.stop(0)
}
