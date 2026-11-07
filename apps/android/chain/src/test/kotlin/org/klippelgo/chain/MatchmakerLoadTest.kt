package org.klippelgo.chain

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.klippelgo.core.Coordinate
import org.klippelgo.core.GossipConfig
import org.klippelgo.core.GossipNode
import org.klippelgo.core.newOfferKey
import org.klippelgo.core.regionCell
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

/**
 * What a flood of offers costs one phone's matchmaker (apps limits P4 and P5): the chain reads it makes, how long a
 * tick takes, and how long the screen waits for the matchmaker while it works. The load is real gossip through a
 * router of its own: a rider's request, offers from the testnet's real drivers, and offers from throwaway accounts
 * nobody registered, as anyone can make for free. Ticks come every 2 s, as in the app; the real drivers announce
 * again every 2.5 s, as their phones do; each throwaway account's offers go out once, or, in the `resend` runs, again
 * every tick for 20 s.
 *
 * A benchmark, not a test: it runs only with KLIPPELGO_BENCH=1 and KLIPPELGO_RPC set, and appends one JSON line per
 * scenario to KLIPPELGO_BENCH_OUT, labelled KLIPPELGO_BENCH_LABEL (core/docs/benchmark.md).
 */
class MatchmakerLoadTest {
    private val rpc = System.getenv("KLIPPELGO_RPC").orEmpty()
    private val deployment = System.getenv("KLIPPELGO_DEPLOYMENT").orEmpty()
        .takeIf { it.isNotBlank() }?.let { Deployment.fromJson(File(it).readText()) } ?: Deployment.LOCAL_DEFAULT
    private val label = System.getenv("KLIPPELGO_BENCH_LABEL") ?: "now"
    private val out = System.getenv("KLIPPELGO_BENCH_OUT")?.let(::File)
    private val json = ObjectMapper()

    @Test
    fun aFloodOfOffers() {
        if (System.getenv("KLIPPELGO_BENCH") != "1" || rpc.isBlank()) return println("KLIPPELGO_BENCH=1 and KLIPPELGO_RPC not set, skipping")
        for (throwaway in listOf(0, 100, 400)) {
            run("rider", throwaway)
            run("driver", throwaway)
        }
        // A flood that goes on: new throwaway accounts every second for 20 s, while the real drivers announce again.
        run("rider", 0, sustainedPerSecond = 50)
        run("rider", 0, sustainedPerSecond = 200)
        // The same forged offers sent again every tick for 20 s, so they wait as long as an honest newcomer does.
        run("rider", 100, resend = true)
        run("rider", 400, resend = true)
    }

    /**
     * One phone, `side` rider (whose request it is) or driver (who hears it in the cell), takes in the offers of
     * two real drivers and of `throwaway` accounts, three each, and is measured until nothing new arrives.
     */
    private fun run(side: String, throwaway: Int, sustainedPerSecond: Int = 0, resend: Boolean = false) {
        val pack = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment).latestPack("br-pr-cascavel") ?: error("no map pack published")
        val route = RouteSummary(
            packId = Numeric.toHexString(pack),
            routeHash = Numeric.toHexString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            distanceM = 8_500,
            durationS = 1_320,
            pickupCell = regionCell(PICKUP),
            dropoffCell = regionCell(DROPOFF),
        )
        val bootstrap = GossipNode.start(GossipConfig(listen = "/ip4/127.0.0.1/tcp/0", bootstrap = emptyList(), router = true))
        fun gossip() = GossipNode.start(GossipConfig(listen = null, bootstrap = bootstrap.listenAddresses(), router = false))
        CountingRpc(rpc).use { counted ->
            val riderClient = KlippelGoClient(if (side == "rider") counted.url else rpc, DevAccount.RIDER_2.key(), deployment)
            val rider = Matchmaker(riderClient, gossip())
            val phone = if (side == "rider") rider
                else Matchmaker(KlippelGoClient(counted.url, DevAccount.DRIVER.key(), deployment), gossip()).apply { listen(Matchmaker.around(PICKUP)) }
            val drivers = listOf(DevAccount.DRIVER_2, DevAccount.E2E_DRIVER).map { KlippelGoClient(rpc, it.key(), deployment) }
            val publisher = gossip()
            val topic = Matchmaker.topicFor(deployment.gossipRelease, Matchmaker.cellOf(route.pickupCell))
            publisher.subscribe(topic)

            val coin = riderClient.brz
            val request = rider.requestRide(coin, coin.toBaseUnits(BigDecimal("25.00")), route)
            val listed = poll("the phone to hold the request") {
                rider.tick()
                if (phone !== rider) phone.tick()
                phone.openRequests().firstOrNull { it.id == request.id }
            }
            // What getting here cost is not the offers'.
            counted.reset()

            // The offers, all published at once: three each from the real drivers and the throwaway accounts.
            val fare = coin.toBaseUnits(BigDecimal("23.40"))
            val real = drivers.flatMap { d -> (1..3).map { signedOffer(d, listed, fare + BigInteger.valueOf(it.toLong()), it.toLong()) } }
            fun throwaways(n: Int, from: Int) = (from until from + n).flatMap { i ->
                val c = KlippelGoClient(rpc, SoftwareDeviceKey(BigInteger(1, ByteArray(32).also { SecureRandom().nextBytes(it) })), deployment)
                (1..3).map { signedOffer(c, listed, fare - BigInteger.valueOf((i % 500).toLong() + 1), it.toLong()) }
            }
            val fake = throwaways(throwaway, 0)
            var sent = real.size + fake.size
            // The flood first, as an attacker would send it, then the real drivers' offers.
            (fake + real).forEach { publisher.publish(topic, it) }

            // Meanwhile the screen asks the matchmaker what to show, as the app does, and times each answer.
            val waits = java.util.Collections.synchronizedList(ArrayList<Long>())
            val screen = java.util.concurrent.atomic.AtomicBoolean(true)
            val asker = Thread {
                while (screen.get()) {
                    val t = System.nanoTime()
                    phone.openRequests()
                    phone.offers(listed)
                    waits += System.nanoTime() - t
                    Thread.sleep(5)
                }
            }.apply { start() }
            val ticks = ArrayList<Long>()
            val started = System.nanoTime()
            var announced = started
            var flooded = 0
            var realHeldAfter: Long? = null
            val realDrivers = drivers.map { it.address }.toSet()
            var quietSince = System.nanoTime()
            var lastCount = -1L
            while (System.nanoTime() - started < 90_000_000_000L) {
                val now = System.nanoTime()
                // The real drivers' phones announce their offers again, as Matchmaker.tick does, every 2.5 s or so.
                if (now - announced > 2_500_000_000L) { real.forEach { publisher.publish(topic, it) }; announced = now }
                // New throwaway accounts at that rate, a tick's worth at a time. Each one's offers go out once.
                if (sustainedPerSecond > 0 && now - started < 20_000_000_000L) {
                    val n = sustainedPerSecond * TICK_MS.toInt() / 1_000
                    throwaways(n, 1_000 + flooded).forEach { publisher.publish(topic, it); sent++ }
                    flooded += n
                }
                if (resend && now - started < 20_000_000_000L) fake.forEach { publisher.publish(topic, it); sent++ }
                val t = System.nanoTime()
                phone.tick()
                if (phone !== rider) rider.tick()
                ticks += System.nanoTime() - t
                if (realHeldAfter == null && rider.offers(request).count { it.driver in realDrivers } == realDrivers.size) realHeldAfter = System.nanoTime() - started
                val count = counted.total.get() + rider.offers(request).size
                if (count != lastCount) { lastCount = count; quietSince = System.nanoTime() }
                // Nothing new for 6 s, and the flood over: what was going to arrive has.
                if (System.nanoTime() - quietSince > 6_000_000_000L && ticks.size > 5 && System.nanoTime() - started > (if (sustainedPerSecond > 0 || resend) 26_000_000_000L else 0L)) break
                // As the app's loop: a tick every two seconds, however long the last one took.
                Thread.sleep(maxOf(0L, TICK_MS - (System.nanoTime() - now) / 1_000_000))
            }
            screen.set(false)
            asker.join()
            val held = rider.offers(request)
            val seconds = (System.nanoTime() - started) / 1e9
            val result = linkedMapOf(
                "label" to label,
                "side" to side,
                "throwaway_accounts" to throwaway + flooded,
                "sustained_per_second" to sustainedPerSecond,
                "resent_every_tick" to resend,
                "offers_sent" to sent,
                "seconds" to Math.round(seconds * 10) / 10.0,
                "rpc_requests" to counted.total.get(),
                "rpc_by_method" to counted.byMethod.toSortedMap(),
                "ticks" to ticks.size,
                "tick_ms_p50" to ms(percentile(ticks, 50)),
                "tick_ms_max" to ms(ticks.maxOrNull() ?: 0),
                "screen_wait_ms_p50" to ms(percentile(waits, 50)),
                "screen_wait_ms_p99" to ms(percentile(waits, 99)),
                "screen_wait_ms_max" to ms(waits.maxOrNull() ?: 0),
                "rider_offers_held" to held.size,
                "rider_offers_from_real_drivers" to held.count { it.driver in realDrivers },
                "real_offers_held_after_ms" to realHeldAfter?.let { ms(it) },
            )
            val line = json.writeValueAsString(result)
            println(line)
            out?.appendText(line + "\n")
            rider.closeRequest(request)
        }
    }

    private fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p / 100.0).toInt()]
    }

    private fun ms(nanos: Long) = Math.round(nanos / 10_000.0) / 100.0

    private fun <T : Any> poll(what: String, timeoutMs: Long = 60_000, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            check()?.let { return it }
            Thread.sleep(300)
        }
        error("Timed out waiting for $what")
    }

    private companion object {
        val PICKUP = Coordinate(-249_555_000, -534_552_000)
        val DROPOFF = Coordinate(-249_800_000, -534_300_000)
        /** How often the app ticks the matchmaker: `AppViewModel`'s loop. */
        const val TICK_MS = 2_000L
    }
}

/**
 * An offer as a driver's phone signs it (`Matchmaker.sendOffer`), from `driver`, for `request`, published by hand:
 * for tests and benchmarks that need offers from accounts no phone would quote for.
 */
internal fun signedOffer(driver: KlippelGoClient, request: RideRequest, fare: BigInteger, seq: Long): ByteArray {
    val expiresAt = minOf(System.currentTimeMillis() / 1000 + Matchmaker.OFFER_TTL, request.expiresAt)
    val quoteHash = driver.quoteHash(request, driver.address, fare, expiresAt)
    val routeKey = newOfferKey().public
    val hash = KlippelGoClient.abiHash(Bytes32(Hash.sha3("KlippelGo offer v3".toByteArray())), Bytes32(quoteHash), Uint64(seq), Bytes32(routeKey))
    val c = driver.sessionKey().certificate
    return ObjectMapper().writeValueAsBytes(
        linkedMapOf(
            "t" to "offer",
            "request" to Numeric.toHexStringWithPrefixZeroPadded(request.id, 64),
            "driver" to driver.address,
            "fare" to fare.toString(),
            "expiresAt" to expiresAt,
            "seq" to seq,
            "routeKey" to Numeric.toHexString(routeKey),
            "sig" to Numeric.toHexString(driver.sign(hash)),
            "cert" to mapOf("account" to c.account, "session" to c.session, "expiresAt" to c.expiresAt, "sig" to Numeric.toHexString(c.signature)),
        ),
    )
}

/** A JSON-RPC proxy in front of `upstream` that passes everything through and counts it, by method and selector. */
class CountingRpc(private val upstream: String) : AutoCloseable {
    val total = AtomicLong()
    val byMethod = ConcurrentHashMap<String, Long>()
    private val json = ObjectMapper()
    private val names = listOf(
        "tokenConfig(address)", "taxBps()", "tariff()", "isValidSignature(bytes32,bytes)", "hasPermission(address,address,bytes4)",
        "isActive(bytes32,uint64)", "nativeCoinPrice()",
    ).associateBy { Hash.sha3String(it).take(10) }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes()
            val request = json.readTree(body)
            (if (request.isArray) request.toList() else listOf(request)).forEach { r ->
                total.incrementAndGet()
                val method = r.get("method").asText()
                val key = if (method == "eth_call") {
                    val data = r.get("params")?.get(0)?.let { it.get("data") ?: it.get("input") }?.asText().orEmpty()
                    "eth_call " + (names[data.take(10)] ?: data.take(10))
                } else method
                byMethod.merge(key, 1L, Long::plus)
            }
            val conn = URI(upstream).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
            val answer = conn.inputStream.use { it.readBytes() }
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, answer.size.toLong())
            ex.responseBody.use { it.write(answer) }
        }
        executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        total.set(0)
        byMethod.clear()
    }

    override fun close() = server.stop(0)
}
