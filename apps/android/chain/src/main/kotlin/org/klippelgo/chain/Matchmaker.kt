package org.klippelgo.chain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.klippelgo.core.GossipNode
import org.klippelgo.core.OfferKey
import org.klippelgo.core.newOfferKey
import org.klippelgo.core.openRoute
import org.klippelgo.core.sealRoute
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * Matches riders and drivers over gossip, as in the architecture plan: the rider gossips a request with a max
 * fare and the route's distance and duration, drivers answer with signed quotes, and the rider locks the quote
 * it picks in the escrow. Nothing here touches the chain until that lock.
 *
 * The request says how far and how long the trip is, rounded, and on which map pack it was computed. Where it
 * starts and ends stays on the rider's phone: the request carries only a salted hash of the route, which every
 * quote signs over. Once the rider has locked an offer, their phone seals the route to a key in that offer and
 * gossips it, so the chosen driver, and nobody else in the cell, learns where the ride goes.
 *
 * Gossip is open: anyone can join a topic, publish to it and replay what they heard. So every message is signed
 * by the account it speaks for and checked here before it is believed. Gossip also keeps no history, so the
 * rider announces its request again every few seconds. A request nobody has announced for a while is gone, which
 * also covers a rider whose app closed without saying so.
 *
 * Call [tick] every second or two, off the main thread.
 *
 * Not built yet, from the plan and its security review: session certificates so the wallet key does not sign
 * every message, quotes encrypted to the rider instead of visible to the cell, presence, real location cells
 * (everyone uses [DEV_CELL]) and a cost for posting requests.
 */
class Matchmaker(
    private val client: KlippelGoClient,
    private val node: GossipNode,
    cell: String = DEV_CELL,
) {
    private val topic = topicFor(cell)
    private val json = ObjectMapper()

    private class Seen(val request: RideRequest, val message: ByteArray, var at: Long)
    private class Quote(val offer: Offer, val seq: Long, val message: ByteArray)

    private val requests = LinkedHashMap<BigInteger, Seen>()
    private val quotes = HashMap<BigInteger, LinkedHashMap<String, Quote>>()
    private val closed = HashMap<BigInteger, ByteArray?>()
    private val riders = HashMap<String, Boolean>()
    private val packs = HashMap<String, Boolean>()

    /** Driver: the key in this phone's offers for each request, and the hash of every quote it sent for it. */
    private val routeKeys = HashMap<BigInteger, OfferKey>()
    private val myQuotes = HashMap<BigInteger, MutableList<String>>()

    /** Rider: the sealed route announced for each request whose offer this phone took. */
    private val reveals = HashMap<BigInteger, ByteArray>()

    /** Driver: routes riders sealed to this phone's offers, opened, by quote hash. Not yet checked on the map. */
    private val revealed = HashMap<String, String>()
    private var announcedAt = 0L

    init {
        node.subscribe(topic)
    }

    /** Peers this phone is connected to. Zero means nothing sent or received is reaching anyone. */
    fun peers(): Int = node.connectedPeers().toInt()

    /** Takes in what peers gossiped since the last call and announces this account's own messages again. */
    @Synchronized
    fun tick() {
        for (message in node.drain()) {
            if (message.topic == topic) runCatching { receive(message.data) }
        }
        val now = now()
        requests.values.removeIf { it.request.expiresAt <= now }
        quotes.keys.retainAll(requests.keys)
        closed.keys.retainAll(requests.keys)
        routeKeys.keys.retainAll(requests.keys)
        myQuotes.keys.retainAll(requests.keys)
        reveals.keys.retainAll(requests.keys)

        if (System.currentTimeMillis() - announcedAt < ANNOUNCE_EVERY_MS) return
        announcedAt = System.currentTimeMillis()
        for ((id, seen) in requests) {
            val mine = seen.request.rider == client.address
            val close = closed[id]
            if (mine) {
                seen.at = announcedAt
                node.publish(topic, close ?: seen.message)
                reveals[id]?.let { node.publish(topic, it) }
            }
            if (close == null) quotes[id]?.get(client.address)?.let { node.publish(topic, it.message) }
        }
    }

    // ---------------------------------------------------------------- reads

    /** Requests that are still being announced, newest first. */
    @Synchronized
    fun openRequests(): List<RideRequest> {
        val now = System.currentTimeMillis()
        return requests.values
            .filter { it.request.id !in closed && it.request.expiresAt > now / 1000 && now - it.at < LIVE_FOR_MS }
            .map { it.request }
            .reversed()
    }

    /** Each driver's latest quote for a request, cheapest first. */
    @Synchronized
    fun offers(request: RideRequest, driver: String? = null): List<Offer> {
        val now = now()
        return quotes[request.id].orEmpty().values
            .map { it.offer }
            .filter { it.expiresAt > now && (driver == null || it.driver == driver) }
            .sortedBy { it.fare }
    }

    // ---------------------------------------------------------------- rider

    /** Asks the drivers in the cell for quotes up to `maxFare` for a trip of this length. */
    @Synchronized
    fun requestRide(coin: Coin, maxFare: BigInteger, route: RouteSummary, ttlSeconds: Long = REQUEST_TTL): RideRequest {
        val expiresAt = now() + ttlSeconds
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = requestId(client.address, coin.address, maxFare, expiresAt, nonce, route)
        val message = encode(
            "t" to "request",
            "rider" to client.address,
            "token" to coin.address.lowercase(),
            "maxFare" to maxFare.toString(),
            "expiresAt" to expiresAt,
            "nonce" to Numeric.toHexString(nonce),
            "pack" to route.packId,
            "route" to route.routeHash,
            "distanceM" to route.distanceM,
            "durationS" to route.durationS,
            "sig" to Numeric.toHexString(client.sign(id)),
        )
        val request = RideRequest(BigInteger(1, id), client.address, coin.address.lowercase(), maxFare, expiresAt, route)
        requests[request.id] = Seen(request, message, System.currentTimeMillis())
        node.publish(topic, message)
        return request
    }

    /** Withdraws this account's request. */
    @Synchronized
    fun closeRequest(request: RideRequest) {
        if (request.rider != client.address) throw ChainException("NotRider")
        val message = encode(
            "t" to "close",
            "request" to hex(request.id),
            "sig" to Numeric.toHexString(client.sign(closeHash(request.id))),
        )
        closed[request.id] = message
        node.publish(topic, message)
    }

    /**
     * Takes a quote: locks its fare and hash in the escrow, withdraws the request, and sends the chosen driver
     * the route. `quoteJson` is the quote the locked hash commits to; it goes out sealed to the key in the
     * driver's offer and is announced until the request expires, since gossip keeps no history. Returns the
     * ride id.
     */
    fun acceptOffer(request: RideRequest, offer: Offer, quoteJson: String? = null): BigInteger {
        val quoteHash = Numeric.hexStringToByteArray(offer.quoteHash)
        val id = client.openRide(offer.driver, client.coinFor(request.token), offer.fare, quoteHash)
        closeRequest(request)
        if (quoteJson != null) revealRoute(request, offer, quoteJson)
        return id
    }

    @Synchronized
    private fun revealRoute(request: RideRequest, offer: Offer, quoteJson: String) {
        val sealed = sealRoute(Numeric.hexStringToByteArray(offer.routeKey), Numeric.hexStringToByteArray(offer.quoteHash), quoteJson)
        val message = encode(
            "t" to "route",
            "request" to hex(request.id),
            "driver" to offer.driver,
            "sealed" to Base64.getEncoder().encodeToString(sealed),
            "sig" to Numeric.toHexString(client.sign(routeHash(request.id, offer.driver, sealed))),
        )
        reveals[request.id] = message
        node.publish(topic, message)
    }

    /**
     * Driver: the route a rider sealed to this phone's offer with this quote hash, or null if none arrived. It is
     * the rider's claim until checked against the map pack and the hash locked in the escrow.
     */
    @Synchronized
    fun revealedQuote(quoteHash: String): String? = revealed[quoteHash.lowercase()]

    // ---------------------------------------------------------------- driver

    /** Signs a quote for a request and gossips it. A newer quote replaces this driver's earlier one. */
    @Synchronized
    fun sendOffer(request: RideRequest, fare: BigInteger, ttlSeconds: Long = OFFER_TTL): Offer {
        val fee = client.tokenConfig(client.coinFor(request.token)).first
        if (fare > request.maxFare) throw ChainException("The quote is above the rider's max fare")
        if (fare <= fee) throw ChainException("FareTooLow")
        val expiresAt = minOf(now() + ttlSeconds, request.expiresAt)
        val seq = maxOf(System.currentTimeMillis(), (quotes[request.id]?.get(client.address)?.seq ?: 0) + 1)
        val quoteHash = client.quoteHash(request, client.address, fare, expiresAt)
        // One key per request, kept across re-quotes, for the rider to seal the route to.
        val public = routeKeys.getOrPut(request.id) { newOfferKey() }.public
        myQuotes.getOrPut(request.id) { mutableListOf() } += Numeric.toHexString(quoteHash)
        val sig = Numeric.toHexString(client.sign(offerHash(quoteHash, seq, public)))
        val message = encode(
            "t" to "offer",
            "request" to hex(request.id),
            "driver" to client.address,
            "fare" to fare.toString(),
            "expiresAt" to expiresAt,
            "seq" to seq,
            "routeKey" to Numeric.toHexString(public),
            "sig" to sig,
        )
        val offer = Offer(request.id, client.address, fare, expiresAt, Numeric.toHexString(quoteHash), sig, Numeric.toHexString(public))
        quotes.getOrPut(request.id) { LinkedHashMap() }[client.address] = Quote(offer, seq, message)
        node.publish(topic, message)
        return offer
    }

    // ---------------------------------------------------------------- incoming

    private fun receive(data: ByteArray) {
        val m = json.readTree(data)
        when (m.text("t")) {
            "request" -> receiveRequest(m, data)
            "offer" -> receiveOffer(m, data)
            "close" -> receiveClose(m, data)
            "route" -> receiveRoute(m)
        }
    }

    private fun receiveRequest(m: JsonNode, data: ByteArray) {
        val rider = m.address("rider")
        val token = m.address("token")
        val maxFare = BigInteger(m.text("maxFare"))
        val expiresAt = m.get("expiresAt").asLong()
        val route = RouteSummary(
            packId = Numeric.toHexString(m.bytes("pack", 32)),
            routeHash = Numeric.toHexString(m.bytes("route", 32)),
            distanceM = m.get("distanceM").asInt(),
            durationS = m.get("durationS").asInt(),
        )
        val id = requestId(rider, token, maxFare, expiresAt, m.bytes("nonce", 32), route)
        val key = BigInteger(1, id)
        requests[key]?.let {
            it.at = System.currentTimeMillis()
            return
        }
        val now = now()
        if (requests.size >= MAX_REQUESTS || expiresAt <= now || expiresAt > now + MAX_TTL) return
        if (maxFare.signum() <= 0 || client.coins.none { it.address.equals(token, ignoreCase = true) }) return
        if (route.distanceM <= 0 || route.durationS <= 0) return
        if (KlippelGoClient.signerOf(id, m.bytes("sig", 65)) != rider) return
        if (!riders.getOrPut(rider) { client.rolesOf(rider) and KlippelGoClient.ROLE_RIDER != 0 }) return
        // A route means something only on a map governance activated: anyone can check it against that pack.
        if (!packs.getOrPut(route.packId) { client.packIsActive(Numeric.hexStringToByteArray(route.packId)) }) return
        requests[key] = Seen(RideRequest(key, rider, token, maxFare, expiresAt, route), data, System.currentTimeMillis())
    }

    private fun receiveOffer(m: JsonNode, data: ByteArray) {
        val request = requests[BigInteger(1, m.bytes("request", 32))]?.request ?: return
        val driver = m.address("driver")
        val fare = BigInteger(m.text("fare"))
        val expiresAt = m.get("expiresAt").asLong()
        val seq = m.get("seq").asLong()
        val forRequest = quotes.getOrPut(request.id) { LinkedHashMap() }
        val known = forRequest[driver]
        if (known != null && known.seq >= seq) return
        if (known == null && forRequest.size >= MAX_OFFERS_PER_REQUEST) return
        if (expiresAt <= now() || expiresAt > request.expiresAt || fare > request.maxFare) return
        if (fare <= client.tokenConfig(client.coinFor(request.token)).first) return
        val quoteHash = client.quoteHash(request, driver, fare, expiresAt)
        val routeKey = m.bytes("routeKey", 32)
        if (KlippelGoClient.signerOf(offerHash(quoteHash, seq, routeKey), m.bytes("sig", 65)) != driver) return
        val offer = Offer(request.id, driver, fare, expiresAt, Numeric.toHexString(quoteHash), m.text("sig"), Numeric.toHexString(routeKey))
        forRequest[driver] = Quote(offer, seq, data)
    }

    private fun receiveClose(m: JsonNode, data: ByteArray) {
        val request = requests[BigInteger(1, m.bytes("request", 32))]?.request ?: return
        if (request.id in closed) return
        if (KlippelGoClient.signerOf(closeHash(request.id), m.bytes("sig", 65)) != request.rider) return
        closed[request.id] = if (request.rider == client.address) data else null
    }

    /** A route sealed for one driver. Everyone in the cell receives it; only that driver can open it. */
    private fun receiveRoute(m: JsonNode) {
        if (m.address("driver") != client.address) return
        val request = requests[BigInteger(1, m.bytes("request", 32))]?.request ?: return
        val secret = routeKeys[request.id]?.secret ?: return
        val sealed = Base64.getDecoder().decode(m.text("sealed"))
        if (KlippelGoClient.signerOf(routeHash(request.id, client.address, sealed), m.bytes("sig", 65)) != request.rider) return
        // The rider took one of this phone's quotes for the request, not necessarily the last: it opens for that one.
        for (quoteHash in myQuotes[request.id].orEmpty()) {
            if (quoteHash in revealed) return
            val quote = runCatching { openRoute(secret, Numeric.hexStringToByteArray(quoteHash), sealed) }.getOrNull() ?: continue
            revealed[quoteHash] = quote
            return
        }
    }

    // ---------------------------------------------------------------- encoding

    private fun requestId(
        rider: String,
        token: String,
        maxFare: BigInteger,
        expiresAt: Long,
        nonce: ByteArray,
        route: RouteSummary,
    ) = KlippelGoClient.abiHash(
        Bytes32(REQUEST_DOMAIN),
        Uint256(client.deployment.chainId),
        Address(client.deployment.escrow),
        Address(rider),
        Address(token),
        Uint256(maxFare),
        Uint64(expiresAt),
        Bytes32(nonce),
        Bytes32(Numeric.hexStringToByteArray(route.packId)),
        Bytes32(Numeric.hexStringToByteArray(route.routeHash)),
        Uint256(route.distanceM.toLong()),
        Uint256(route.durationS.toLong()),
    )

    private fun offerHash(quoteHash: ByteArray, seq: Long, routeKey: ByteArray) =
        KlippelGoClient.abiHash(Bytes32(OFFER_DOMAIN), Bytes32(quoteHash), Uint64(seq), Bytes32(routeKey))

    private fun routeHash(requestId: BigInteger, driver: String, sealed: ByteArray) =
        KlippelGoClient.abiHash(Bytes32(ROUTE_DOMAIN), Uint256(requestId), Address(driver), Bytes32(Hash.sha3(sealed)))

    private fun closeHash(requestId: BigInteger) = KlippelGoClient.abiHash(Bytes32(CLOSE_DOMAIN), Uint256(requestId))

    private fun encode(vararg fields: Pair<String, Any>): ByteArray = json.writeValueAsBytes(mapOf(*fields))

    private fun JsonNode.text(key: String): String = get(key)?.takeIf { it.isTextual }?.asText() ?: error("missing $key")

    private fun JsonNode.bytes(key: String, size: Int): ByteArray =
        Numeric.hexStringToByteArray(text(key)).also { require(it.size == size) }

    private fun JsonNode.address(key: String): String = Numeric.toHexString(bytes(key, 20))

    companion object {
        /** The centre of Cascavel, Paraná, the pilot city. Every phone uses this cell until the app reads its location. */
        const val DEV_CELL = "6g9fp"

        /** Port the bootstrap node listens on (`make gossip-node`). */
        const val DEFAULT_PORT = 4001

        /** How long a request and a quote stay valid, in seconds. */
        const val REQUEST_TTL = 600L
        const val OFFER_TTL = 300L
        private const val MAX_TTL = 3600L

        private const val ANNOUNCE_EVERY_MS = 2_500L
        private const val LIVE_FOR_MS = 10_000L
        private const val MAX_REQUESTS = 200
        private const val MAX_OFFERS_PER_REQUEST = 50

        private val REQUEST_DOMAIN: ByteArray = Hash.sha3("KlippelGo request v2".toByteArray())
        private val OFFER_DOMAIN: ByteArray = Hash.sha3("KlippelGo offer v2".toByteArray())
        private val CLOSE_DOMAIN: ByteArray = Hash.sha3("KlippelGo close v1".toByteArray())
        private val ROUTE_DOMAIN: ByteArray = Hash.sha3("KlippelGo sealed route v1".toByteArray())

        /** Gossip topic for matching in one geohash-5 cell. */
        fun topicFor(cell: String) = "/klippelgo/match/1/$cell"

        private fun hex(id: BigInteger) = Numeric.toHexStringWithPrefixZeroPadded(id, 64)

        private fun now(): Long = System.currentTimeMillis() / 1000
    }
}
