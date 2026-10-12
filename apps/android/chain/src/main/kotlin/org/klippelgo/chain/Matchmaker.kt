package org.klippelgo.chain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.klippelgo.core.Coordinate
import org.klippelgo.core.GossipNode
import org.klippelgo.core.regionBounds
import org.klippelgo.core.regionCell
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
 * Matches riders and drivers over gossip, as in the architecture plan: the rider gossips a request with a price
 * and the route's distance and duration, drivers answer with signed quotes, and the rider locks the quote
 * it picks in the escrow. Nothing here touches the chain until that lock.
 *
 * The request says how far and how long the trip is, rounded, and on which map pack it was computed. Where it
 * starts and ends stays on the rider's phone: the request names only the two regions, about a square kilometre
 * each, and carries a salted hash of the route, which every quote signs over. Once the rider has locked an offer, their phone seals the route to a key in that offer and
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

    /** Driver: the key each rider gave for sealing this driver's position to them, by quote hash. */
    private val riderKeys = HashMap<String, ByteArray>()
    private var positionSentAt = 0L

    /** Rider: for each ride this phone locked, by quote hash, the key positions are sealed to and whose they are. */
    private class Watch(val key: OfferKey, val driver: String, var last: DriverPosition? = null, var newest: Long = 0)
    private val watching = HashMap<String, Watch>()
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
        if (!client.riderMayAsk(coin, maxFare, route)) throw ChainException("The price is outside the range the fare guide allows for this trip")
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
            "from" to route.pickupCell,
            "to" to route.dropoffCell,
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
        // A key of the rider's own goes with the route, for the driver to seal their position to once this ride
        // is the one they are on.
        val watch = watching.getOrPut(offer.quoteHash.lowercase()) { Watch(newOfferKey(), offer.driver) }
        val message = encode(
            "t" to "route",
            "request" to hex(request.id),
            "driver" to offer.driver,
            "sealed" to Base64.getEncoder().encodeToString(sealed),
            "key" to Numeric.toHexString(watch.key.public),
            "sig" to Numeric.toHexString(client.sign(routeHash(request.id, offer.driver, sealed, watch.key.public))),
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

    /** Driver: the key the rider of this quote gave for positions, or null if their route never arrived. */
    @Synchronized
    fun riderKey(quoteHash: String): ByteArray? = riderKeys[quoteHash.lowercase()]

    /**
     * Driver: tells the rider of the ride this phone is on where it is. The position is sealed to the rider's
     * key. The cell sees that this driver is sending something for the ride with this quote hash, which ties it
     * to the ride on chain, and not where the driver is. Sent at most every few seconds on the way to the
     * pickup, and every second once the rider is in the car (`onTheRide`); call it as often as fixes arrive.
     */
    @Synchronized
    fun sharePosition(quoteHash: String, riderKey: ByteArray, latE7: Int, lonE7: Int, onTheRide: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - positionSentAt < (if (onTheRide) POSITION_ON_THE_RIDE_EVERY_MS else POSITION_EVERY_MS)) return
        positionSentAt = now
        val quote = Numeric.hexStringToByteArray(quoteHash)
        val sealed = sealRoute(riderKey, quote, "$latE7,$lonE7,$now")
        node.publish(
            topic,
            encode(
                "t" to "position",
                "quote" to quoteHash.lowercase(),
                "sealed" to Base64.getEncoder().encodeToString(sealed),
                "sig" to Numeric.toHexString(client.sign(positionHash(quote, sealed))),
            ),
        )
    }

    /**
     * Rider: where the driver of the ride locked with this quote last said they were, or null when they have
     * said nothing in the last half minute. A driver only says so while this ride is the one they are on.
     */
    @Synchronized
    fun driverPosition(quoteHash: String): DriverPosition? =
        watching[quoteHash.lowercase()]?.last?.takeIf { System.currentTimeMillis() - it.atMs < POSITION_FRESH_MS }

    // ---------------------------------------------------------------- driver

    /** Signs a quote for a request and gossips it. A newer quote replaces this driver's earlier one. */
    @Synchronized
    fun sendOffer(request: RideRequest, fare: BigInteger, ttlSeconds: Long = OFFER_TTL): Offer {
        val fee = client.tokenConfig(client.coinFor(request.token)).first
        if (!client.driverMayQuote(request, fare)) throw ChainException("The quote is outside what the fare guide lets a driver ask for this trip")
        if (fare <= fee + client.taxOn(fare)) throw ChainException("FareTooLow")
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
            "position" -> receivePosition(m)
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
            pickupCell = m.text("from"),
            dropoffCell = m.text("to"),
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
        if (regionBounds(route.pickupCell) == null || regionBounds(route.dropoffCell) == null) return
        // The rider's price must be within the fare guide's range for a trip of the stated length.
        if (!client.riderMayAsk(client.coinFor(token), maxFare, route)) return
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
        if (expiresAt <= now() || expiresAt > request.expiresAt) return
        if (!client.driverMayQuote(request, fare)) return
        // The escrow refuses a fare that the fee and the tax would eat whole.
        if (fare <= client.tokenConfig(client.coinFor(request.token)).first + client.taxOn(fare)) return
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
        val riderKey = m.bytes("key", 32)
        if (KlippelGoClient.signerOf(routeHash(request.id, client.address, sealed, riderKey), m.bytes("sig", 65)) != request.rider) return
        // The rider took one of this phone's quotes for the request, not necessarily the last: it opens for that one.
        for (quoteHash in myQuotes[request.id].orEmpty()) {
            if (quoteHash in revealed) return
            val quote = runCatching { openRoute(secret, Numeric.hexStringToByteArray(quoteHash), sealed) }.getOrNull() ?: continue
            // The route must start and end in the regions the request showed everyone, or the driver priced a
            // different trip from the one being revealed.
            if (!startsAndEndsIn(quote, request.route)) return
            revealed[quoteHash] = quote
            riderKeys[quoteHash] = riderKey
            return
        }
    }

    /** A driver's position, sealed for the rider of one ride. Only that rider's phone can open it. */
    private fun receivePosition(m: JsonNode) {
        val quoteHash = m.text("quote").lowercase()
        val watch = watching[quoteHash] ?: return
        val quote = Numeric.hexStringToByteArray(quoteHash)
        val sealed = Base64.getDecoder().decode(m.text("sealed"))
        if (KlippelGoClient.signerOf(positionHash(quote, sealed), m.bytes("sig", 65)) != watch.driver) return
        val parts = runCatching { openRoute(watch.key.secret, quote, sealed) }.getOrNull()?.split(',') ?: return
        if (parts.size != 3) return
        val position = runCatching { DriverPosition(parts[0].toInt(), parts[1].toInt(), parts[2].toLong()) }.getOrNull() ?: return
        // A place that is not on the Earth is not drawn: the map would refuse it.
        if (position.latE7 !in -900_000_000..900_000_000 || position.lonE7 !in -1_800_000_000..1_800_000_000) return
        // Gossip can deliver out of order, and a replayed message is an old one: only a position the driver
        // stamped later than every one before it counts. Their clock orders their own messages and nothing
        // else. How fresh a position is goes by when it arrived here, so a clock that runs ahead or behind
        // neither lets an old message pass as new nor makes a live driver look silent.
        if (position.atMs <= watch.newest) return
        watch.newest = position.atMs
        watch.last = position.copy(atMs = System.currentTimeMillis())
    }

    private fun startsAndEndsIn(quote: String, route: RouteSummary): Boolean = runCatching {
        val q = json.readTree(quote)
        fun cell(key: String) = regionCell(Coordinate(q.get(key).get(0).asInt(), q.get(key).get(1).asInt()))
        cell("pickup") == route.pickupCell && cell("dropoff") == route.dropoffCell
    }.getOrDefault(false)

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
        Bytes32(Hash.sha3(route.pickupCell.toByteArray())),
        Bytes32(Hash.sha3(route.dropoffCell.toByteArray())),
    )

    private fun offerHash(quoteHash: ByteArray, seq: Long, routeKey: ByteArray) =
        KlippelGoClient.abiHash(Bytes32(OFFER_DOMAIN), Bytes32(quoteHash), Uint64(seq), Bytes32(routeKey))

    private fun routeHash(requestId: BigInteger, driver: String, sealed: ByteArray, riderKey: ByteArray) =
        KlippelGoClient.abiHash(Bytes32(ROUTE_DOMAIN), Uint256(requestId), Address(driver), Bytes32(Hash.sha3(sealed)), Bytes32(riderKey))

    private fun positionHash(quoteHash: ByteArray, sealed: ByteArray) =
        KlippelGoClient.abiHash(Bytes32(POSITION_DOMAIN), Bytes32(quoteHash), Bytes32(Hash.sha3(sealed)))

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

        private val REQUEST_DOMAIN: ByteArray = Hash.sha3("KlippelGo request v3".toByteArray())
        private val OFFER_DOMAIN: ByteArray = Hash.sha3("KlippelGo offer v2".toByteArray())
        private val CLOSE_DOMAIN: ByteArray = Hash.sha3("KlippelGo close v1".toByteArray())
        private val ROUTE_DOMAIN: ByteArray = Hash.sha3("KlippelGo sealed route v2".toByteArray())
        private val POSITION_DOMAIN: ByteArray = Hash.sha3("KlippelGo sealed position v1".toByteArray())

        /** How often a driver's position goes out at most, and how long a rider treats the last one as current. */
        private const val POSITION_EVERY_MS = 4_000L

        /** The same once the ride has started: the rider watches the trip itself go by. A little under a second, so a fix a second is never held back. */
        private const val POSITION_ON_THE_RIDE_EVERY_MS = 900L
        private const val POSITION_FRESH_MS = 30_000L

        /** Gossip topic for matching in one geohash-5 cell. */
        fun topicFor(cell: String) = "/klippelgo/match/1/$cell"

        private fun hex(id: BigInteger) = Numeric.toHexStringWithPrefixZeroPadded(id, 64)

        private fun now(): Long = System.currentTimeMillis() / 1000
    }
}
