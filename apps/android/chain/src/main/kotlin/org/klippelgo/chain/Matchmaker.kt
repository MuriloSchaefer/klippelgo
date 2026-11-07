package org.klippelgo.chain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.klippelgo.core.Bounds
import org.klippelgo.core.Coordinate
import org.klippelgo.core.GossipMessage
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
import kotlin.math.abs

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
 * by the account it speaks for and checked here before it is believed. Gossip also keeps no history, so each
 * message is announced again, less often as it ages ([Cadence]). A request nobody has announced for a while is
 * gone, which also covers a rider whose app closed without saying so.
 *
 * Gossip is split into cells of about 5 km (geohash 5), one topic each. A request goes to the cell its pickup is
 * in, and its offers, close and sealed route follow it there. A driver hears the cells around them ([listen]); a
 * rider, only the cells of their own requests. So a phone receives its neighbourhood's traffic, not the city's.
 * A driver's positions, the most frequent message by far, go to a topic of the ride's own ([rideTopic]), which
 * only that ride's rider listens to.
 *
 * Call [tick] every second or two, off the main thread.
 *
 * Not built yet, from the plan and its security review: quotes encrypted to the rider instead of visible to the
 * cell, presence, and a cost for posting requests.
 */
class Matchmaker(
    private val client: KlippelGoClient,
    private val node: GossipNode,
) {
    private val json = ObjectMapper()

    /** A request held: `heard` is when it first arrived, `at` when it last did, `middle` its pickup region's. */
    private class Seen(val request: RideRequest, val message: ByteArray, var at: Long, val middle: Coordinate? = null) {
        val heard = at
        val cadence = Cadence(OPEN_GAP_MOST_MS, at)
        /** Rider: until when the close goes out, and how often. Set when this phone closes the request. */
        var closing: Cadence? = null
        var closeUntil = 0L
    }
    private class Quote(val offer: Offer, val seq: Long, val message: ByteArray) {
        val cadence = Cadence(OPEN_GAP_MOST_MS, System.currentTimeMillis())
    }

    private val requests = LinkedHashMap<BigInteger, Seen>()
    private val quotes = HashMap<BigInteger, LinkedHashMap<String, Quote>>()
    private val closed = HashMap<BigInteger, ByteArray?>()
    /** Requests forgotten once closed, until they expire, so that a replay does not bring them back. The newest [MAX_GONE]. */
    private val gone = LinkedHashMap<BigInteger, Long>()
    /** Whether the policy lets each rider open a ride, and when the chain was last asked. The newest [MAX_ACCOUNTS_KNOWN]. */
    private val riders = LinkedHashMap<String, Pair<Boolean, Long>>()
    /** Whether governance activated each pack, and when the chain was last asked. The newest [MAX_PACKS_KNOWN]. */
    private val packs = LinkedHashMap<String, Pair<Boolean, Long>>()
    /** Whether the policy lets each driver accept, and when the chain was last asked. The newest [MAX_ACCOUNTS_KNOWN]. */
    private val drivers = LinkedHashMap<String, Pair<Boolean, Long>>()

    /**
     * What a message needs the chain to say before it is believed. Each is asked outside the lock, once per tick
     * however many messages need it, and only while that tick's reads last ([tick]).
     */
    private sealed interface Fact {
        /**
         * Whether the account confirms this session key's certificate. `known`: one of a known request's parties.
         * The same certificate in two messages is one fact: compared by what it says, not by its byte array.
         */
        class Certificate(val certificate: SessionCertificate, val known: Boolean) : Fact {
            private val id = listOf(certificate.account.lowercase(), certificate.session.lowercase(), certificate.expiresAt, Numeric.toHexString(certificate.signature), known)
            override fun equals(other: Any?) = other is Certificate && other.id == id
            override fun hashCode() = id.hashCode()
        }
        data class MayOpen(val rider: String) : Fact
        data class MayAccept(val driver: String) : Fact
        data class PackActive(val pack: String) : Fact
    }

    /** Thrown by a check that needs a [Fact] nobody has asked for yet: the message waits for the answer. */
    private class Waiting(val fact: Fact) : Exception(null, null, false, false)

    /** What the chain said this tick, for the messages that were waiting on it. */
    private val answered = HashMap<Fact, Boolean>()

    /** Offers given up to make room, by request and driver: their sequence number, so a replay cannot take it back. */
    private val evicted = LinkedHashMap<String, Long>()
    /** Where this phone is, for telling near requests from far ones. It stays on the phone. */
    private var here: Coordinate? = null

    /** Driver: the key in this phone's offers for each request, and the hash of every quote it sent for it. */
    private val routeKeys = HashMap<BigInteger, OfferKey>()
    private val myQuotes = HashMap<BigInteger, MutableList<String>>()
    /** Driver: the rider each quote this phone sent was for, by quote hash. Kept after its request is gone. */
    private val quotedFor = LinkedHashMap<String, String>()

    /** Rider: the sealed route announced for each request whose offer this phone took, until the driver shows they have it. */
    private class Reveal(val message: ByteArray, val cadence: Cadence)
    private val reveals = HashMap<BigInteger, Reveal>()

    /** Driver: routes riders sealed to this phone's offers, opened, by quote hash. Not yet checked on the map. */
    private val revealed = HashMap<String, String>()

    /** Driver: the key each rider gave for sealing this driver's position to them, by quote hash. */
    private val riderKeys = HashMap<String, ByteArray>()
    private var positionSentAt = 0L

    /**
     * Rider: for each ride this phone locked, by quote hash, the key positions are sealed to and whose they are.
     * This phone listens on each one's [rideTopic]. The newest [MAX_WATCHES].
     */
    private class Watch(val key: OfferKey, val driver: String, val request: BigInteger, var last: DriverPosition? = null, var newest: Long = 0)
    private val watching = LinkedHashMap<String, Watch>()

    /**
     * Told what happens to the phone's subscriptions and to each offer it receives, for finding out why something
     * did not show. Only public gossip goes through it. The app sets it in debug builds only.
     */
    @Volatile var trace: ((String) -> Unit)? = null

    private fun note(what: String) {
        trace?.invoke(what)
    }

    /** What this matchmaker holds now, in one line per thing, for the same purpose as [trace]. */
    @Synchronized
    fun describe(): String = buildString {
        appendLine("account ${client.address}, peers ${peers()}")
        appendLine("area ${area.sorted()}")
        appendLine("subscribed ${subscribed.sorted()}")
        for ((id, seen) in requests) {
            val r = seen.request
            val whose = if (r.rider == client.address) "mine" else if (id in myQuotes) "quoted" else "heard"
            val offers = quotes[id].orEmpty().values.joinToString { "${it.offer.driver}@${it.offer.fare}" }
            appendLine("request ${short(id)} $whose cell ${cellOf(r)} closed ${id in closed} heard ${System.currentTimeMillis() - seen.at} ms ago, offers [$offers]")
        }
        for ((quote, w) in watching) appendLine("watching ${quote.take(10)} driver ${w.driver}")
    }

    /** The cells whose requests this phone wants to hear ([listen]), and the cells it is subscribed to. */
    private val area = HashSet<String>()
    private val subscribed = HashSet<String>()
    private val rideTopics = HashSet<String>()

    /** Peers this phone is connected to. Zero means nothing sent or received is reaching anyone. */
    fun peers(): Int = node.connectedPeers().toInt()

    /**
     * The cells whose requests this phone hears: a driver's neighbourhood ([around]). A rider's phone needs none:
     * it hears the cells of its own requests whatever this says. Requests from a cell left behind are
     * forgotten, unless this phone quoted them. `at` is where the phone is, if it knows: when its list is full it
     * keeps the requests nearest there.
     */
    @Synchronized
    fun listen(cells: Set<String>, at: Coordinate? = null) {
        here = at
        if (cells == area) return
        area.clear()
        area += cells
        resubscribe()
    }

    /** Where this phone's release matches: its topics are this and a cell. */
    private val matchPrefix = topicFor(client.deployment.gossipRelease, "")

    private fun topicFor(cell: String) = matchPrefix + cell

    /** Subscribes to the cells this phone needs now and leaves the others. */
    private fun resubscribe() {
        val wanted = HashSet(area)
        for ((id, seen) in requests) if (seen.request.rider == client.address || id in myQuotes) wanted += cellOf(seen.request)
        requests.values.removeIf { cellOf(it.request) !in wanted }
        for (cell in wanted - subscribed) runCatching { node.subscribe(topicFor(cell)) }.onSuccess { subscribed += cell; note("subscribed to $cell") }
        for (cell in subscribed - wanted) runCatching { node.unsubscribe(topicFor(cell)) }.onSuccess { subscribed -= cell; note("left $cell") }
        val rides = watching.keys.mapTo(HashSet(), ::rideTopic)
        for (topic in rides - rideTopics) runCatching { node.subscribe(topic) }.onSuccess { rideTopics += topic }
        for (topic in rideTopics - rides) runCatching { node.unsubscribe(topic) }.onSuccess { rideTopics -= topic }
    }

    /** Takes in what peers gossiped since the last call and announces this account's own messages again. */
    fun tick() = synchronized(ticking) {
        // Every check that needs no chain first, under the lock; then what the chain must say, outside it, so a flood
        // of messages that need reads never holds up the screen; then the messages whose answer came, under the lock
        // again. A message can need a second fact once the first is known (a certificate, then the rider's role): a
        // few rounds. One tick at a time, however many loops call it, so the reads per tick bound the reads.
        var waiting = synchronized(this) { node.drain().mapNotNull { m -> take(m, last = false)?.let { m to it } } }
        var reads = MAX_CHECKS_PER_TICK
        var knownReads = MAX_KNOWN_CHECKS_PER_TICK
        val answers = HashMap<Fact, Boolean>()
        for (round in 1..FACT_ROUNDS) {
            if (waiting.isEmpty()) break
            val facts = waiting.map { it.second }.distinct()
            // The facts that have waited longest first, then at random: an honest party announces again every few
            // seconds, so their certificate waits tick after tick, while a forgery with a fresh key waits once. Nor
            // can the order a flood arrives in put it first.
            val order = synchronized(this) {
                if (round == 1) {
                    val nowMs = System.currentTimeMillis()
                    // A fact not seen for longer than anyone waits between two announcements is forgotten: a forgery
                    // sent once drops out, while an honest party keeps its count. When the record is full, newcomers
                    // are not added: those that have waited keep their place, and a flood cannot wipe it.
                    waitedFor.values.removeIf { nowMs - it.second > LIVE_FOR_MS }
                    for (f in facts) {
                        val w = waitedFor[f]
                        if (w != null) waitedFor[f] = w.first + 1 to nowMs
                        else if (waitedFor.size < MAX_WAITING_KNOWN) waitedFor[f] = 1 to nowMs
                    }
                }
                val ranked = facts.shuffled().sortedByDescending { waitedFor[it]?.first ?: 0 }
                // A few reads go to facts drawn at random from all that wait, so the order is never worse than chance:
                // not for a newcomer a full record left out, nor against forgeries sent again as often as it is.
                val drawn = facts.shuffled().take(RANDOM_READS)
                drawn + ranked.filterNot { it in drawn }
            }
            for (fact in order) {
                if (fact in answers) continue
                val budget = if (fact is Fact.Certificate && fact.known) knownReads else reads
                if (budget <= 0) continue
                if (fact is Fact.Certificate && fact.known) knownReads-- else reads--
                runCatching { ask(fact) }.onSuccess { answers[fact] = it }
            }
            waiting = synchronized(this) {
                answered.putAll(answers)
                answers.keys.forEach { waitedFor.remove(it) }
                // Only what an answer came for is taken again; the rest is dropped now, and taken when announced again.
                waiting.mapNotNull { (m, fact) ->
                    if (fact !in answers) { note("a message dropped: no reads left for ${fact::class.simpleName}"); null }
                    else take(m, last = round == FACT_ROUNDS)?.let { m to it }
                }
            }
        }
        synchronized(this) {
            answered.clear()
            housekeep()
        }
    }

    /** Serialises [tick]: separate from the lock the screen's reads take, which a tick lets go while it asks the chain. */
    private val ticking = Any()

    /**
     * Facts that waited for a read and did not get one: in how many ticks, and when they were last seen. The
     * longest-waiting are asked first.
     */
    private val waitedFor = HashMap<Fact, Pair<Int, Long>>()

    /** Takes in one gossiped message. Returns the fact it waits for, or null once it was taken in or dropped. */
    private fun take(message: GossipMessage, last: Boolean): Fact? {
        val cell = message.topic.removePrefix(matchPrefix)
        try {
            if (message.topic.startsWith(matchPrefix) && cell in subscribed) receive(cell, message.data)
            else if (message.topic in rideTopics) receiveOnRide(message.topic, message.data)
        } catch (w: Waiting) {
            if (!last) return w.fact
            note("a message in $cell dropped: no reads left for ${w.fact::class.simpleName}")
        } catch (e: Exception) {
            note("a message in $cell failed: ${e.message}")
        }
        return null
    }

    /** What the chain says of `fact`. Called outside the lock. */
    private fun ask(fact: Fact): Boolean = when (fact) {
        is Fact.Certificate -> client.certified(fact.certificate)
        is Fact.MayOpen -> client.canOpenRides(fact.rider)
        is Fact.MayAccept -> client.canAcceptRides(fact.driver)
        is Fact.PackActive -> client.packIsActive(Numeric.hexStringToByteArray(fact.pack))
    }

    /**
     * The answer to `fact` this phone has, or [Waiting] to ask the chain. Answers about accounts and packs are kept
     * a few minutes, and only so many of them; a certificate's, by the client for good ([KlippelGoClient.certified]).
     */
    private fun know(fact: Fact): Boolean {
        val now = System.currentTimeMillis()
        val kept: LinkedHashMap<String, Pair<Boolean, Long>>? = when (fact) {
            // A certificate the client can answer without the chain (confirmed, refused a moment ago, or its session
            // key's lookups used up) costs no read: a forgery sent again is free to drop.
            is Fact.Certificate -> client.certifiedWithoutReading(fact.certificate)?.let { return it } ?: null
            is Fact.MayOpen -> riders
            is Fact.MayAccept -> drivers
            is Fact.PackActive -> packs
        }
        val key = when (fact) {
            is Fact.MayOpen -> fact.rider
            is Fact.MayAccept -> fact.driver
            is Fact.PackActive -> fact.pack
            else -> ""
        }
        kept?.get(key)?.takeIf { now - it.second < ACCOUNT_CHECK_MS }?.let { return it.first }
        val answer = answered[fact] ?: throw Waiting(fact)
        if (kept != null) {
            kept.remove(key)
            kept[key] = answer to now
            while (kept.size > (if (fact is Fact.PackActive) MAX_PACKS_KNOWN else MAX_ACCOUNTS_KNOWN)) kept.remove(kept.keys.first())
        }
        return answer
    }

    /** Forgets what expired and announces this account's own messages again. */
    private fun housekeep() {
        val nowS = now()
        val nowMs = System.currentTimeMillis()
        requests.entries.removeIf { (id, seen) -> seen.request.expiresAt <= nowS || forgettable(id, seen, nowMs) }
        gone.values.removeIf { it <= nowS }
        quotes.keys.retainAll(requests.keys)
        closed.keys.retainAll(requests.keys)
        routeKeys.keys.retainAll(requests.keys)
        myQuotes.keys.retainAll(requests.keys)
        reveals.keys.retainAll(requests.keys)
        resubscribe()

        for ((id, seen) in requests) {
            val topic = topicFor(cellOf(seen.request))
            val close = closed[id]
            if (seen.request.rider == client.address) {
                seen.at = nowMs
                val closing = seen.closing
                if (close == null) {
                    if (seen.cadence.due(nowMs)) node.publish(topic, seen.message)
                } else if (closing != null && nowMs < seen.closeUntil && closing.due(nowMs)) {
                    node.publish(topic, close)
                }
                reveals[id]?.let { if (it.cadence.due(nowMs)) node.publish(topic, it.message) }
            }
            // Only while the rider is still heard: a close this phone missed must not keep its offer going.
            if (id !in closed && nowMs - seen.at < LIVE_FOR_MS) {
                quotes[id]?.get(client.address)?.let { if (it.cadence.due(nowMs)) node.publish(topic, it.message) }
            }
        }
    }

    /**
     * Whether a request can go: closed, or not announced for a while. Not one of this phone's own, nor one it
     * quoted, which a sealed route may still arrive for. Closed ones are remembered in [gone].
     */
    private fun forgettable(id: BigInteger, seen: Seen, nowMs: Long): Boolean {
        if (seen.request.rider == client.address || id in myQuotes) return false
        if (id in closed) {
            gone[id] = seen.request.expiresAt
            while (gone.size > MAX_GONE) gone.remove(gone.keys.first())
            return true
        }
        return nowMs - seen.at >= LIVE_FOR_MS
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
        // Into the pickup's cell, and listening there for the offers.
        resubscribe()
        node.publish(topicFor(cellOf(request)), message)
        note("request ${short(request.id)} out in ${cellOf(request)}")
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
        val now = System.currentTimeMillis()
        requests[request.id]?.let {
            it.closing = Cadence(CLOSE_GAP_MOST_MS, now)
            it.closeUntil = now + LIVE_FOR_MS
        }
        node.publish(topicFor(cellOf(request)), message)
    }

    /**
     * Takes a quote: locks its fare and hash in the escrow, withdraws the request, and sends the chosen driver
     * the route. `quoteJson` is the quote the locked hash commits to; it goes out sealed to the key in the
     * driver's offer and is announced, less and less often, until the driver's first position shows they have it
     * or the request expires, since gossip keeps no history. Returns the ride id.
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
        val watch = watching.getOrPut(offer.quoteHash.lowercase()) { Watch(newOfferKey(), offer.driver, request.id) }
        while (watching.size > MAX_WATCHES) watching.remove(watching.keys.first())
        val message = encode(
            "t" to "route",
            "request" to hex(request.id),
            "driver" to offer.driver,
            "sealed" to Base64.getEncoder().encodeToString(sealed),
            "key" to Numeric.toHexString(watch.key.public),
            "sig" to Numeric.toHexString(client.sign(routeHash(request.id, offer.driver, sealed, watch.key.public))),
        )
        reveals[request.id] = Reveal(message, Cadence(ROUTE_GAP_MOST_MS, System.currentTimeMillis()))
        resubscribe()
        node.publish(topicFor(cellOf(request)), message)
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
     * key, on the ride's own topic. Sent at most every few seconds on the way to the pickup, and every second
     * once the rider is in the car (`onTheRide`); call it as often as fixes arrive.
     */
    @Synchronized
    fun sharePosition(quoteHash: String, riderKey: ByteArray, latE7: Int, lonE7: Int, onTheRide: Boolean = false) {
        val topic = rideTopic(quoteHash)
        val now = System.currentTimeMillis()
        if (now - positionSentAt < (if (onTheRide) POSITION_ON_THE_RIDE_EVERY_MS else POSITION_EVERY_MS)) return
        positionSentAt = now
        node.publish(topic, positionMessage(quoteHash, riderKey, latE7, lonE7, now))
    }

    internal fun positionMessage(quoteHash: String, riderKey: ByteArray, latE7: Int, lonE7: Int, now: Long): ByteArray {
        val quote = Numeric.hexStringToByteArray(quoteHash)
        val sealed = sealRoute(riderKey, quote, "$latE7,$lonE7,$now")
        return encode(
            "t" to "position",
            "quote" to quoteHash.lowercase(),
            "sealed" to Base64.getEncoder().encodeToString(sealed),
            "sig" to Numeric.toHexString(client.sign(positionHash(quote, sealed))),
        )
    }

    /** Rider: this ride is over, so its driver has nothing more to say. Leaves its topic at the next tick. */
    @Synchronized
    fun forgetRide(quoteHash: String) {
        watching.remove(quoteHash.lowercase())
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
        // Forgotten since it was on screen: closed, or its rider went quiet. A quote would have nowhere to live.
        if (request.id !in requests) throw ChainException("RequestGone")
        val fee = client.tokenConfig(client.coinFor(request.token)).first
        if (!client.driverMayQuote(request, fare)) throw ChainException("The quote is outside what the fare guide lets a driver ask for this trip")
        if (fare <= fee + client.taxOn(fare)) throw ChainException("FareTooLow")
        val expiresAt = minOf(now() + ttlSeconds, request.expiresAt)
        val seq = maxOf(System.currentTimeMillis(), (quotes[request.id]?.get(client.address)?.seq ?: 0) + 1)
        val quoteHash = client.quoteHash(request, client.address, fare, expiresAt)
        // One key per request, kept across re-quotes, for the rider to seal the route to.
        val public = routeKeys.getOrPut(request.id) { newOfferKey() }.public
        myQuotes.getOrPut(request.id) { mutableListOf() } += Numeric.toHexString(quoteHash)
        quotedFor[Numeric.toHexString(quoteHash).lowercase()] = request.rider.lowercase()
        while (quotedFor.size > MAX_SENT_QUOTES) quotedFor.remove(quotedFor.keys.first())
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
        // Kept listening in the request's cell for the route, even if the driver moves out of it.
        resubscribe()
        node.publish(topicFor(cellOf(request)), message)
        note("offer for ${short(request.id)} at $fare out in ${cellOf(request)}")
        return offer
    }

    /** Driver: every quote this phone sent since it started, newest last: its hash and the rider it was for. */
    @Synchronized
    fun sentQuotes(): Map<String, String> = LinkedHashMap(quotedFor)

    // ---------------------------------------------------------------- incoming

    private fun receive(cell: String, data: ByteArray) {
        val m = json.readTree(data)
        when (m.text("t")) {
            "request" -> receiveRequest(m, data, cell)
            "offer" -> receiveOffer(m, data)
            "close" -> receiveClose(m, data)
            "route" -> receiveRoute(m)
        }
    }

    /** A ride's topic carries that ride's positions and nothing else. */
    private fun receiveOnRide(topic: String, data: ByteArray) {
        val m = json.readTree(data)
        if (m.text("t") == "position" && rideTopic(m.text("quote")) == topic) receivePosition(m)
    }

    private fun receiveRequest(m: JsonNode, data: ByteArray, cell: String) {
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
        // A request is heard only in the cell its pickup is in, so it reaches the drivers near it and no others.
        if (cellOf(route.pickupCell) != cell) return
        val id = requestId(rider, token, maxFare, expiresAt, m.bytes("nonce", 32), route)
        val key = BigInteger(1, id)
        requests[key]?.let {
            it.at = System.currentTimeMillis()
            // Someone replays a request this phone closed: say so again, at the close's own slow pace.
            if (it.closing != null) it.closeUntil = it.at + LIVE_FOR_MS
            return
        }
        if (key in gone) return
        val now = now()
        if (expiresAt <= now || expiresAt > now + MAX_TTL) return
        if (maxFare.signum() <= 0 || client.coins.none { it.address.equals(token, ignoreCase = true) }) return
        if (route.distanceM <= 0 || route.durationS <= 0) return
        val pickup = regionBounds(route.pickupCell) ?: return
        if (regionBounds(route.dropoffCell) == null) return
        val middle = Coordinate((pickup.southWest.latE7 + pickup.northEast.latE7) / 2, (pickup.southWest.lonE7 + pickup.northEast.lonE7) / 2)
        // Before any signature or chain read: a full list decides first, so a request it would not keep costs nothing.
        val full = requests.size >= MAX_REQUESTS
        if (full && leastWanted()?.let { band(middle) > band(it.middle) } != false) return
        // The rider's price must be within the fare guide's range for a trip of the stated length.
        if (!client.riderMayAsk(client.coinFor(token), maxFare, route)) return
        if (!speaksFor(m, id, rider)) return
        // Whoever could not lock the ride anyway (no rider role, or blocked by Instituto Klippel) is not shown.
        // The answer is kept for a few minutes, not for good, so a block or an unblock reaches a running phone.
        if (!know(Fact.MayOpen(rider))) return
        // A route means something only on a map governance activated: anyone can check it against that pack.
        if (!know(Fact.PackActive(route.packId.lowercase()))) return
        // Only now, with the newcomer proved: nothing unsigned can push a request out (A1).
        if (full) leastWanted()?.let { requests.remove(it.request.id) }
        requests[key] = Seen(RideRequest(key, rider, token, maxFare, expiresAt, route), data, System.currentTimeMillis(), middle)
    }

    /** The request a full list gives up first: the farthest, and of those the one heard longest ago. Never one [forgettable] spares. */
    private fun leastWanted(): Seen? =
        requests.entries
            .filter { (id, seen) -> seen.request.rider != client.address && id !in myQuotes }
            .maxWithOrNull(compareBy({ band(it.value.middle) }, { -it.value.heard }))?.value

    private fun band(middle: Coordinate?): Int = distanceBand(here, middle)

    private fun receiveOffer(m: JsonNode, data: ByteArray) {
        val id = BigInteger(1, m.bytes("request", 32))
        // Offers for other riders' requests are most of what a rider hears, and a driver shows only their own:
        // only those for this phone's own request are worth checking.
        val request = requests[id]?.request?.takeIf { it.rider == client.address } ?: return
        val driver = m.address("driver")
        val fare = BigInteger(m.text("fare"))
        val expiresAt = m.get("expiresAt").asLong()
        val seq = m.get("seq").asLong()
        fun drop(why: String) = note("offer from $driver for ${short(id)} at $fare dropped: $why")
        val forRequest = quotes.getOrPut(request.id) { LinkedHashMap() }
        val known = forRequest[driver]
        if (known != null && known.seq >= seq) return
        // An offer given up to make room cannot come back as the same or an older quote.
        if (known == null && (evicted["${request.id}/$driver"] ?: Long.MIN_VALUE) >= seq) return drop("given up for room")
        if (expiresAt <= now() || expiresAt > request.expiresAt) return drop("expiry $expiresAt")
        if (!client.driverMayQuote(request, fare)) return drop("outside the driver's band")
        // The escrow refuses a fare that the fee and the tax would eat whole.
        if (fare <= client.tokenConfig(client.coinFor(request.token)).first + client.taxOn(fare)) return drop("under the fee and the tax")
        val quoteHash = client.quoteHash(request, driver, fare, expiresAt)
        val routeKey = m.bytes("routeKey", 32)
        if (!speaksFor(m, offerHash(quoteHash, seq, routeKey), driver)) return drop("signature or certificate")
        // Only a driver the policy lets accept holds a slot: one needs the role, the stake and the score, so filling
        // the slots takes real accounts, not throwaway keys (P5).
        if (!know(Fact.MayAccept(driver))) return drop("the policy does not let this driver accept")
        if (known == null && !roomFor(request.id, forRequest, fare)) return drop("the request has $MAX_OFFERS_PER_REQUEST offers")
        val offer = Offer(request.id, driver, fare, expiresAt, Numeric.toHexString(quoteHash), m.text("sig"), Numeric.toHexString(routeKey))
        forRequest[driver] = Quote(offer, seq, data)
        note("offer from $driver for ${short(id)} at $fare taken")
    }

    /**
     * Whether a signed offer from a new driver who may accept fits, making room if it must: expired offers go first,
     * then, for a cheaper offer, the dearest. Whatever is given up keeps its sequence number in [evicted], so it
     * cannot be replayed back. It needs no read: every offer held already passed [Fact.MayAccept].
     */
    private fun roomFor(request: BigInteger, offers: MutableMap<String, Quote>, fare: BigInteger): Boolean {
        val now = now()
        offers.values.removeIf { it.offer.expiresAt <= now }
        if (offers.size < MAX_OFFERS_PER_REQUEST) return true
        val out = offers.values.maxByOrNull { it.offer.fare }?.takeIf { it.offer.fare > fare } ?: return false
        offers.remove(out.offer.driver)
        evicted["$request/${out.offer.driver}"] = out.seq
        while (evicted.size > MAX_EVICTED) evicted.remove(evicted.keys.first())
        return true
    }

    private fun receiveClose(m: JsonNode, data: ByteArray) {
        val request = requests[BigInteger(1, m.bytes("request", 32))]?.request ?: return
        if (request.id in closed) return
        if (!speaksFor(m, closeHash(request.id), request.rider, known = true)) return
        closed[request.id] = if (request.rider == client.address) data else null
    }

    /** A route sealed for one driver. Everyone in the cell receives it; only that driver can open it. */
    private fun receiveRoute(m: JsonNode) {
        if (m.address("driver") != client.address) return
        val request = requests[BigInteger(1, m.bytes("request", 32))]?.request ?: return
        val secret = routeKeys[request.id]?.secret ?: return
        val sealed = Base64.getDecoder().decode(m.text("sealed"))
        val riderKey = m.bytes("key", 32)
        if (!speaksFor(m, routeHash(request.id, client.address, sealed, riderKey), request.rider, known = true)) return
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
        if (!speaksFor(m, positionHash(quote, sealed), watch.driver, known = true)) return
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
        // The driver seals to a key that came with the route, so a position they signed says the route arrived.
        reveals.remove(watch.request)
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

    /**
     * A message as JSON. A signed one carries the certificate of the session key that signed it, so a peer can
     * tell which account it speaks for ([speaksFor]).
     */
    private fun encode(vararg fields: Pair<String, Any>): ByteArray {
        val map = linkedMapOf(*fields)
        if ("sig" in map) {
            val c = client.sessionKey().certificate
            map["cert"] = mapOf(
                "account" to c.account,
                "session" to c.session,
                "expiresAt" to c.expiresAt,
                "sig" to Numeric.toHexString(c.signature),
            )
        }
        return json.writeValueAsBytes(map)
    }

    /**
     * Whether `m` was signed over `hash` for `account`: its signature recovers a session key, and its certificate
     * says that key speaks for that account, which the account itself confirms on chain ([KlippelGoClient.certified]).
     * Everything here but that confirmation needs no chain. A certificate this phone has not confirmed yet waits for
     * one of the tick's reads ([Fact.Certificate]): of the requests' and offers' reads, or, for a party of a request
     * this phone already holds (`known`: closes, routes, positions), of a smaller share of their own. So a flood of
     * forgeries costs a bounded number of reads, and an account this phone knows gets through it.
     */
    private fun speaksFor(m: JsonNode, hash: ByteArray, account: String, known: Boolean = false): Boolean {
        val session = KlippelGoClient.signerOf(hash, m.bytes("sig", 65)) ?: return false
        val c = m.get("cert")?.takeIf { it.isObject } ?: return false
        val certificate = runCatching {
            SessionCertificate(
                account = c.address("account"),
                session = c.address("session"),
                expiresAt = c.get("expiresAt").asLong(),
                signature = Numeric.hexStringToByteArray(c.text("sig")).also { require(it.size <= MAX_CERTIFICATE_SIGNATURE) },
            )
        }.getOrNull() ?: return false
        if (!certificate.account.equals(account, ignoreCase = true) || !certificate.session.equals(session, ignoreCase = true)) return false
        // Expired, or claiming too long a life: refused without asking anyone.
        val nowS = now()
        if (certificate.expiresAt <= nowS || certificate.expiresAt > nowS + 2 * SessionKey.LIFETIME_SECONDS) return false
        return know(Fact.Certificate(certificate, known))
    }

    private fun JsonNode.text(key: String): String = get(key)?.takeIf { it.isTextual }?.asText() ?: error("missing $key")

    private fun JsonNode.bytes(key: String, size: Int): ByteArray =
        Numeric.hexStringToByteArray(text(key)).also { require(it.size == size) }

    private fun JsonNode.address(key: String): String = Numeric.toHexString(bytes(key, 20))

    companion object {
        /** Characters of geohash in a gossip cell: about 5 km. A request's regions have one more, so they lie inside one cell. */
        const val CELL_PRECISION = 5

        /**
         * How far around the middle of a driver's cell they hear requests from, in degrees × 10⁷ each way: past the
         * edge of the cell (0.022° from its middle) and short of the far edge of its neighbours (0.066°), so the
         * cell and its eight neighbours.
         */
        const val AROUND_E7 = 450_000

        /** Under the height and width of a cell (0.044°): sampling a box this often meets every cell it touches. */
        private const val CELL_STEP_E7 = 200_000

        /** Port the bootstrap node listens on (`make gossip-node`). */
        const val DEFAULT_PORT = 4001

        /** How long a request and a quote stay valid, in seconds. */
        const val REQUEST_TTL = 600L
        const val OFFER_TTL = 300L

        /** Quotes remembered with their rider: more than a driver sends in the life of one. */
        const val MAX_SENT_QUOTES = 200
        private const val MAX_TTL = 3600L

        /** The first gap before a message goes out again. Each one after is twice the last, up to these. */
        internal const val ANNOUNCE_EVERY_MS = 2_500L
        private const val OPEN_GAP_MOST_MS = 5_000L
        private const val CLOSE_GAP_MOST_MS = 10_000L
        private const val ROUTE_GAP_MOST_MS = 60_000L

        /** Unheard for this long, a request is gone. Over two announcements at the widest gap, on a two-second loop. */
        private const val LIVE_FOR_MS = 15_000L
        private const val MAX_REQUESTS = 200

        /** Width of the distance bands of [distanceBand], about 2 km: a region is about one, so nearer than this means little. */
        private const val BAND_E7 = 200_000

        /** Closed requests remembered: twice what the busiest cells at the target announce in a request's life. */
        private const val MAX_GONE = 2_048
        /** Riders and drivers whose permission this phone keeps, each. */
        private const val MAX_ACCOUNTS_KNOWN = 1_024
        private const val MAX_PACKS_KNOWN = 64
        /** Offers given up for room whose sequence number is kept: 50 per request for 80 requests. */
        private const val MAX_EVICTED = 4_096

        /** Far more riders new to this phone than nine busy cells bring between two ticks; what a flood gets through. */
        private const val MAX_CHECKS_PER_TICK = 64

        /** Reads a tick may spend confirming certificates of a held request's parties: its close, route and positions. */
        private const val MAX_KNOWN_CHECKS_PER_TICK = 16

        /** Facts remembered as waiting, to ask the longest-waiting first, and for how many ticks unseen. */
        private const val MAX_WAITING_KNOWN = 4_096

        /** Reads a tick gives to facts drawn at random, before the longest-waiting. */
        private const val RANDOM_READS = 8

        /** How many facts one message may wait on in a tick: a certificate, a permission, a pack. */
        private const val FACT_ROUNDS = 3

        /** How long what the chain said of an account or a pack is believed before it is asked again. */
        private const val ACCOUNT_CHECK_MS = 5 * 60_000L
        private const val MAX_OFFERS_PER_REQUEST = 50

        // Signed by a session key since these versions, with its certificate alongside (A2: a new version).
        private val REQUEST_DOMAIN: ByteArray = Hash.sha3("KlippelGo request v4".toByteArray())
        private val OFFER_DOMAIN: ByteArray = Hash.sha3("KlippelGo offer v3".toByteArray())
        private val CLOSE_DOMAIN: ByteArray = Hash.sha3("KlippelGo close v2".toByteArray())
        private val ROUTE_DOMAIN: ByteArray = Hash.sha3("KlippelGo sealed route v3".toByteArray())
        private val POSITION_DOMAIN: ByteArray = Hash.sha3("KlippelGo sealed position v2".toByteArray())

        /** A certificate's signature is one signer and one signature, ABI encoded: well under this. */
        private const val MAX_CERTIFICATE_SIGNATURE = 1024

        /** How often a driver's position goes out at most, and how long a rider treats the last one as current. */
        private const val POSITION_EVERY_MS = 4_000L

        /** The same once the ride has started: the rider watches the trip itself go by. A little under a second, so a fix a second is never held back. */
        private const val POSITION_ON_THE_RIDE_EVERY_MS = 900L
        private const val POSITION_FRESH_MS = 30_000L

        private const val TOPIC_PREFIX = "/klippelgo/match/"
        private const val RIDE_TOPIC_PREFIX = "/klippelgo/ride/1/"

        /** Rides whose driver's positions a rider's phone listens for. More than one person has under way. */
        private const val MAX_WATCHES = 8

        /**
         * How far `middle` is from `here`, in bands of [BAND_E7]: the larger of the two axes, which is enough to
         * order requests. Without a position every request is as near as any, so only newer counts.
         */
        internal fun distanceBand(here: Coordinate?, middle: Coordinate?): Int {
            if (here == null || middle == null) return 0
            return (maxOf(abs(here.latE7.toLong() - middle.latE7), abs(here.lonE7.toLong() - middle.lonE7)) / BAND_E7).toInt()
        }

        /** Gossip topic of one ride, by the hash of its quote: its rider listens, its driver sends. */
        fun rideTopic(quoteHash: String) = RIDE_TOPIC_PREFIX + quoteHash.lowercase()

        /**
         * Gossip topic for matching in one geohash-5 cell, among phones on one release (`Deployment.gossipRelease`):
         * phones built against other code contracts never hear each other's requests.
         */
        fun topicFor(release: String, cell: String) = "$TOPIC_PREFIX$release/$cell"

        /** The gossip cell of a request: the one its pickup region is in. */
        fun cellOf(request: RideRequest): String = cellOf(request.route.pickupCell)

        /** The gossip cell a region cell (`regionCell` in the core) lies in: a geohash's prefix is the cell holding it. */
        fun cellOf(region: String): String = region.take(CELL_PRECISION)

        /** The gossip cell a point is in. */
        fun cellAt(at: Coordinate): String = cellOf(regionCell(at))

        /**
         * The cells a driver at `at` hears: the cell they are in and its eight neighbours. It depends on the cell
         * alone, not on where in it they are: what a phone subscribes to is seen by the router and by its peers,
         * and a set that moved with the driver would trace them to the metre each time it changed (A9).
         */
        fun around(at: Coordinate): Set<String> {
            val cell = cellAt(at)
            // A geohash's first child is its south-west corner and its last the north-east one.
            val sw = regionBounds(cell + "0")?.southWest ?: return setOf(cell)
            val ne = regionBounds(cell + "z")?.northEast ?: return setOf(cell)
            val mid = Coordinate((sw.latE7 + ne.latE7) / 2, (sw.lonE7 + ne.lonE7) / 2)
            return covering(Bounds(Coordinate(mid.latE7 - AROUND_E7, mid.lonE7 - AROUND_E7), Coordinate(mid.latE7 + AROUND_E7, mid.lonE7 + AROUND_E7)))
        }

        /** Every cell a box touches. */
        fun covering(box: Bounds): Set<String> {
            val cells = LinkedHashSet<String>()
            var lat = box.southWest.latE7
            while (true) {
                var lon = box.southWest.lonE7
                while (true) {
                    cells += cellAt(Coordinate(lat, lon))
                    if (lon >= box.northEast.lonE7) break
                    lon = minOf(lon + CELL_STEP_E7, box.northEast.lonE7)
                }
                if (lat >= box.northEast.latE7) break
                lat = minOf(lat + CELL_STEP_E7, box.northEast.latE7)
            }
            return cells
        }

        private fun hex(id: BigInteger) = Numeric.toHexStringWithPrefixZeroPadded(id, 64)

        private fun short(id: BigInteger) = hex(id).take(10)

        private fun now(): Long = System.currentTimeMillis() / 1000
    }
}

/**
 * When a message goes out again: first after [Matchmaker.ANNOUNCE_EVERY_MS], then each gap twice the last, up to `most`. A
 * message is sent once when it is made, so this counts from then. Gossip keeps no history, so what is announced
 * must be said again for peers who missed it or came later, and those are fewer the older it is.
 */
internal class Cadence(private val most: Long, start: Long) {
    private var gap = Matchmaker.ANNOUNCE_EVERY_MS
    private var next = start + gap

    fun due(now: Long): Boolean {
        if (now < next) return false
        gap = minOf(gap * 2, most)
        next = now + gap
        return true
    }
}
