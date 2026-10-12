package org.klippelgo.tests

import org.klippelgo.chain.Coin
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.DriverPosition
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.chain.Matchmaker
import org.klippelgo.chain.Offer
import org.klippelgo.chain.Ride
import org.klippelgo.chain.RideRequest
import org.klippelgo.chain.RouteSummary
import org.klippelgo.core.CheckedQuote
import org.klippelgo.core.Coordinate
import org.klippelgo.core.EscrowRef
import org.klippelgo.core.GossipConfig
import org.klippelgo.core.GossipNode
import org.klippelgo.core.MeasuredRoute
import org.klippelgo.core.OfferTerms
import org.klippelgo.core.VehicleKind
import org.klippelgo.core.attributesRequest
import org.klippelgo.core.coarseRoute
import org.klippelgo.core.quoteJson
import org.klippelgo.core.regionCell
import org.klippelgo.core.routeHash
import org.klippelgo.core.routeRequest
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Another person in the ride: an account with its own key, its own gossip node and its own view of the chain,
 * and no screen. It is the only thing in the tests that is not the real app, and even so it is the app's own
 * client code (`:chain` and the Rust core) talking to the same chain and the same gossip network. A peer rider
 * gets its routes from a real router on the same map pack the phones use.
 *
 * It does what the app's view model does for a person, one call per thing a person would do.
 */
class Peer(private val stack: Stack, val account: DevAccount) : AutoCloseable {
    val client = KlippelGoClient(stack.rpc, account.privateKey, stack.deployment)
    val address: String get() = client.address
    private val node = GossipNode.start(GossipConfig(listen = null, bootstrap = listOf(stack.gossip), router = false))
    val match = Matchmaker(client, node)

    /** The route and salt behind each request this peer made. Never gossiped, as on a phone. */
    private val routes = ConcurrentHashMap<BigInteger, Pair<MeasuredRoute, ByteArray>>()

    @Volatile private var open = true

    // The app's two-second loop, a little faster so a test waits less.
    private val ticker = Thread {
        while (open) {
            runCatching { match.tick() }
            Thread.sleep(400)
        }
    }.apply {
        isDaemon = true
        start()
    }

    val coin: Coin get() = client.brz

    fun balance(coin: Coin = this.coin): BigInteger = client.balanceOf(coin)

    /** Whether this peer's messages can reach anyone. */
    fun connected(): Boolean = match.peers() > 0

    // ---------------------------------------------------------------- as a rider

    /** The fastest way between two points, found by Valhalla on the pack's tiles and measured by the verifier. */
    fun route(from: Coordinate, to: Coordinate): MeasuredRoute {
        val route = valhalla("route", routeRequest(from, to, VehicleKind.CAR))
        val attributes = valhalla("trace_attributes", attributesRequest(route, VehicleKind.CAR))
        return stack.pack.measureRoute(route, attributes, VehicleKind.CAR)
    }

    private fun valhalla(action: String, body: String): String {
        val request = HttpRequest.newBuilder(URI("${stack.valhalla}/$action"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { error("No router at ${stack.valhalla}. Bring the stack up: tests/stack.sh up") }
        check(response.statusCode() == 200) { "Valhalla refused the $action: ${response.body()}" }
        return response.body()
    }

    /** What the fare guide recommends for a route, in the coin's base units. */
    fun recommended(route: MeasuredRoute, coin: Coin = this.coin): BigInteger {
        val coarse = coarseRoute(route.distanceM, route.durationS)
        return coin.fromCentavos(client.tariff.recommended(coarse.distanceM.toInt(), coarse.durationS.toInt()))
    }

    /** Asks the drivers around for quotes on a route, at `price` or at what the fare guide recommends. */
    fun ask(route: MeasuredRoute, price: BigInteger? = null, coin: Coin = this.coin): RideRequest {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val request = match.requestRide(coin, price ?: recommended(route, coin), summary(route, salt))
        routes[request.id] = route to salt
        return request
    }

    /** What a request says about a route: its length, rounded, the two regions, and a salted hash of the rest. */
    fun summary(route: MeasuredRoute, salt: ByteArray): RouteSummary {
        val coarse = coarseRoute(route.distanceM, route.durationS)
        return RouteSummary(
            packId = Numeric.toHexString(stack.packId),
            routeHash = Numeric.toHexString(routeHash(stack.packId, route, salt)),
            distanceM = coarse.distanceM.toInt(),
            durationS = coarse.durationS.toInt(),
            pickupCell = regionCell(route.pickup),
            dropoffCell = regionCell(route.dropoff),
        )
    }

    fun offers(request: RideRequest): List<Offer> = match.offers(request)

    fun awaitOffer(request: RideRequest, driver: String, timeoutMs: Long = 30_000): Offer =
        waitUntil("${short(driver)}'s offer", timeoutMs) { match.offers(request, driver.lowercase()).firstOrNull() }

    /**
     * Takes a driver's offer: locks the fare in the escrow and sends the driver the route, sealed to the key in
     * their offer. `reveal` replaces the route sent, for a rider who reveals something other than what was locked.
     */
    fun take(request: RideRequest, offer: Offer, reveal: String? = null): BigInteger {
        val (route, salt) = routes[request.id] ?: error("this peer did not make that request")
        return match.acceptOffer(request, offer, reveal ?: quote(request, offer, route, salt))
    }

    fun quote(request: RideRequest, offer: Offer, route: MeasuredRoute, salt: ByteArray): String =
        quoteJson(stack.packId, route, salt, OfferTerms(offer.fare.toString(), request.token, request.rider, offer.driver, offer.expiresAt.toULong()))

    fun withdraw(request: RideRequest) = match.closeRequest(request)

    /** Confirms arrival, which pays the driver. */
    fun complete(ride: BigInteger) {
        client.complete(ride, Hash.sha3("receipt of ride $ride".toByteArray()))
    }

    fun driverPosition(ride: BigInteger): DriverPosition? = match.driverPosition(client.ride(ride).quoteHash)

    // ---------------------------------------------------------------- as a driver

    fun requests(): List<RideRequest> = match.openRequests()

    fun awaitRequest(rider: String, timeoutMs: Long = 30_000): RideRequest =
        waitUntil("${short(rider)}'s request", timeoutMs) { match.openRequests().firstOrNull { it.rider == rider.lowercase() } }

    /** Quotes `fare` for a request, or the rider's own price. */
    fun offer(request: RideRequest, fare: BigInteger? = null): Offer = match.sendOffer(request, fare ?: request.maxFare)

    /** The ride a rider opened naming this driver for this offer, once it is on chain. */
    fun awaitRide(offer: Offer, timeoutMs: Long = 30_000): Ride = waitUntil("the ride locked on ${short(offer.quoteHash)}", timeoutMs) {
        client.ridesAsDriver().take(10).map(client::ride).firstOrNull { it.quoteHash.equals(offer.quoteHash, ignoreCase = true) }
    }

    /**
     * The route the rider sealed to this driver's offer, checked the way the app checks it: on this peer's own
     * copy of the pack, and against the hash locked in the escrow.
     */
    fun awaitRoute(ride: Ride, timeoutMs: Long = 30_000): CheckedQuote {
        val quote = waitUntil("the route sealed for ride ${ride.id}", timeoutMs) { match.revealedQuote(ride.quoteHash) }
        val checked = stack.pack.checkQuote(EscrowRef(stack.deployment.chainId.toULong(), stack.deployment.escrow), quote)
        check(checked.quoteHash.contentEquals(Numeric.hexStringToByteArray(ride.quoteHash))) { "the route revealed is not the one locked in the escrow" }
        return checked
    }

    fun accept(ride: BigInteger) = client.accept(ride)

    fun start(ride: BigInteger) {
        client.start(ride, Hash.sha3("pickup of ride $ride".toByteArray()))
    }

    /**
     * Tells the rider of this ride where the driver is. Goes out at most every few seconds, as from a phone, or
     * every second with `onTheRide`, as a phone does once the ride has started.
     */
    fun sharePosition(ride: BigInteger, at: Coordinate, onTheRide: Boolean = false) {
        val quoteHash = client.ride(ride).quoteHash
        val key = match.riderKey(quoteHash) ?: error("the rider's position key has not arrived")
        match.sharePosition(quoteHash, key, at.latE7, at.lonE7, onTheRide)
    }

    // ---------------------------------------------------------------- either

    fun ride(id: BigInteger): Ride = client.ride(id)

    fun cancel(ride: BigInteger) = client.cancel(ride)

    fun rate(ride: BigInteger, stars: Int = 5) = client.rate(ride, stars)

    override fun close() {
        open = false
        ticker.join(2_000)
        node.stop()
    }

    companion object {
        private val http = HttpClient.newHttpClient()

        fun short(hex: String) = if (hex.length > 12) hex.take(6) + "…" + hex.takeLast(4) else hex
    }
}

/** Places in Cascavel the tests ride between. They are on the pilot pack's roads. */
object Places {
    val CENTRE = Coordinate(-249_555_000, -534_552_000)
    val CATHEDRAL = Coordinate(-249_570_000, -534_590_000)
    val LAKE = Coordinate(-249_640_000, -534_360_000)
    val NORTH = Coordinate(-249_300_000, -534_600_000)
    val WEST = Coordinate(-249_560_000, -534_900_000)
}
