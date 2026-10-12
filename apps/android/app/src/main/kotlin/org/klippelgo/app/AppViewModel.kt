package org.klippelgo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.klippelgo.chain.Blocker
import org.klippelgo.chain.Coin
import org.klippelgo.chain.Deployment
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.DriverInfo
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.chain.Matchmaker
import org.klippelgo.chain.Offer
import org.klippelgo.chain.Ride
import org.klippelgo.chain.RideStatus
import org.klippelgo.chain.RideRequest
import org.klippelgo.chain.RouteSummary
import org.klippelgo.chain.Tariff
import org.klippelgo.core.Bounds
import org.klippelgo.core.CheckedQuote
import org.klippelgo.core.Coordinate
import org.klippelgo.core.EscrowRef
import org.klippelgo.core.GossipConfig
import org.klippelgo.core.GossipNode
import org.klippelgo.core.MeasuredRoute
import org.klippelgo.core.OfferTerms
import org.klippelgo.core.Place
import org.klippelgo.core.VehicleKind
import org.klippelgo.core.coarseRoute
import org.klippelgo.core.quoteJson
import org.klippelgo.core.regionBounds
import org.klippelgo.core.regionCell
import org.klippelgo.core.routeHash
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom

enum class Role { RIDER, DRIVER }

/**
 * Where a request's trip starts and ends, as far as a driver is told: two regions of about a square kilometre,
 * with the name of the neighbourhood each is in when this phone's map pack knows one.
 */
data class TripRegions(val pickup: Bounds, val dropoff: Bounds, val pickupName: String?, val dropoffName: String?)

data class UiState(
    val role: Role? = null,
    val rpcUrl: String = DEFAULT_RPC,
    val address: String = "",
    val connected: Boolean? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val balances: Map<Coin, BigInteger> = emptyMap(),
    val drivers: List<DriverInfo> = emptyList(),
    val rides: List<Ride> = emptyList(),
    val rated: Set<BigInteger> = emptySet(),
    val canAccept: Boolean = false,
    /** Driver: what the accept policy still asks of this account. Empty while `canAccept`. */
    val blockers: List<Blocker> = emptyList(),
    val fees: Map<Coin, BigInteger> = emptyMap(),
    /** What each side locks on top of the fare, per coin: the rider's bond, then the driver's. */
    val bonds: Map<Coin, Pair<BigInteger, BigInteger>> = emptyMap(),
    /** First name typed when the account was created. It never leaves this phone. */
    val name: String = "",
    /** Rider, right after creating the account: offer to add balance before the first ride. */
    val showFunding: Boolean = false,
    /** Driver: this account's own reputation. */
    val me: DriverInfo? = null,
    /** Rider: the request waiting for quotes, and the quotes received so far, cheapest first. */
    val request: RideRequest? = null,
    val offers: List<Offer> = emptyList(),
    /** Driver: riders' open requests, and this driver's current quote for each. */
    val requests: List<RideRequest> = emptyList(),
    val myOffers: Map<BigInteger, Offer> = emptyMap(),
    /** Whether this phone has any gossip peer. Without one, requests and offers reach nobody. */
    val gossipConnected: Boolean? = null,
    /** The map pack on this phone: maps, search and routes all come from it. */
    val map: MapState = MapState.Checking,
    /** Rider: where the car should come, set by moving the map under the pin, and what the map calls it. */
    val pickup: Coordinate? = null,
    val pickupName: String? = null,
    /** Rider: what the destination search found, the place picked, and the route to it once measured. */
    val places: List<Place> = emptyList(),
    val destination: Place? = null,
    val route: MeasuredRoute? = null,
    val routing: Boolean = false,
    val routeError: String? = null,
    /** Rider: the driver gave the ride up, so the same request went out again for other drivers. */
    val askedAgain: Boolean = false,
    /** The price guide governance set: what a trip is recommended to cost and how far anyone may move from it. */
    val tariff: Tariff? = null,
    /** Driver: requests under this many reais per km are skipped. Zero switches the rule off. */
    val minPerKm: BigDecimal = BigDecimal.ZERO,
    /** Driver: requests priced more than this many percent under the recommendation are skipped. */
    val maxBelowPercent: Int = 10,
    /** Driver: how many open requests those rules and a recent refusal are hiding right now. */
    val skippedRequests: Int = 0,
    /** Rider: how many rides the driver has to finish before this phone's ride. Zero when it is their current one. */
    val ridesAhead: Int = 0,
    /** Rider: where the driver is, while this phone's ride is the one they are on and they are saying so. */
    val driverAt: Coordinate? = null,
    /** Driver: the pickup and drop-off regions of each open request, by request id. */
    val regions: Map<BigInteger, TripRegions> = emptyMap(),
    /** Where this phone is, once its owner has allowed reading it: the driver on their map, the rider's pickup pin. */
    val location: Coordinate? = null,
    /** Driver: the checked route of each ride the driver was chosen for, by ride id. */
    val rideRoutes: Map<BigInteger, CheckedQuote> = emptyMap(),
    /** Driver: the way to the first pickup and between rides taken one after the other. */
    val links: List<List<Coordinate>> = emptyList(),
    /** The government's tax on a ride, as a share of the fare in basis points. It comes out of the driver's part. */
    val taxBps: Int = 0,
    /**
     * What this side's transactions for one ride are expected to cost the network, in reais: the gas they take,
     * at today's gas price and the rate governance set for the chain's coin. Null until both are known.
     */
    val infrastructure: BigDecimal? = null,
    /** What this phone's transactions for each ride on screen actually cost the network, in reais, by ride id. */
    val infrastructureSpent: Map<BigInteger, BigDecimal> = emptyMap(),
)

const val DEFAULT_RPC = "http://10.0.2.2:8545"

/**
 * Holds one account's view of the chain and of the gossip network, and refreshes it every two seconds. Rider and
 * driver run this same code on different emulators. They agree a fare over gossip, and everything after that
 * reaches the other only through the contracts.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("klippelgo", Context.MODE_PRIVATE)
    private val deployment: Deployment = loadDeployment(app)

    private val _state = MutableStateFlow(
        UiState(
            role = prefs.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() },
            rpcUrl = prefs.getString("rpc", DEFAULT_RPC) ?: DEFAULT_RPC,
            name = prefs.getString("name", "") ?: "",
            minPerKm = BigDecimal(prefs.getString("minPerKm", "0") ?: "0"),
            maxBelowPercent = prefs.getInt("maxBelowPercent", 10),
        ),
    )
    val state: StateFlow<UiState> = _state

    private val maps = MapStore(app)
    @Volatile private var pack: OpenPack? = null
    private var packId: ByteArray? = null
    private var mapJob: Job? = null
    private var searchJob: Job? = null
    private var routeJob: Job? = null

    /** The route behind this phone's open request, with the salt its hash was made with. Never gossiped. */
    private var pending: Pair<MeasuredRoute, ByteArray>? = null

    private var client: KlippelGoClient? = null
    @Volatile private var match: Matchmaker? = null
    private val matchLock = Any()
    private var session = 0
    private var poller: Job? = null

    init {
        _state.value.role?.let { connect(it, _state.value.rpcUrl) }
    }

    /**
     * Pick the role for this device.
     *
     * A phone is one person with one account, whichever mode it is in: switching between passenger and driver
     * changes what the phone does, not who it is. The account is one of four seeded test accounts. It is chosen
     * the first time, from the role the phone starts in and `pair` (1 or 2, so that four phones can play two
     * riders and two drivers), and kept from then on. A launch that names a `pair` chooses again.
     */
    fun connect(role: Role, rpcUrl: String, pair: Int? = null) {
        val kept = prefs.getString("account", null)?.let { name -> DevAccount.entries.firstOrNull { it.name == name } }
        // A phone set up before accounts were tied to phones remembers only its pair.
        val second = (pair ?: prefs.getInt("pair", 1)) == 2
        val key = if (pair == null && kept != null) kept else when (role) {
            Role.RIDER -> if (second) DevAccount.RIDER_2 else DevAccount.RIDER
            Role.DRIVER -> if (second) DevAccount.DRIVER_2 else DevAccount.DRIVER
        }
        prefs.edit().putString("role", role.name).putString("rpc", rpcUrl).putString("account", key.name).apply()
        val c = KlippelGoClient(rpcUrl.trim(), key.privateKey, deployment)
        client = c
        _state.value = _state.value.let {
            // Where the phone is does not change with the role: it is told again only when it moves.
            UiState(role = role, rpcUrl = rpcUrl, address = c.address, name = it.name, minPerKm = it.minPerKm, maxBelowPercent = it.maxBelowPercent, location = it.location)
        }
        pinByHand = false
        leaveGossip()
        packBounds = null
        prepareMap()
        val mySession = session
        poller = viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.ensureRegistered(if (role == Role.RIDER) KlippelGoClient.ROLE_RIDER else KlippelGoClient.ROLE_DRIVER) }
            // This loop owns its gossip node and stops it when the role or node address changes.
            val node = joinGossip(rpcUrl)
            val m = node?.let { Matchmaker(c, it) }
            try {
                // A newer connect may have replaced this loop while its node was starting.
                synchronized(matchLock) { if (session == mySession) match = m }
                // Positions have a loop of their own: a refresh reads the chain and can take seconds.
                launch {
                    while (isActive) {
                        delay(1_000)
                        runCatching { positions(m, role) }
                    }
                }
                while (isActive) {
                    refresh(c, m, role)
                    delay(2_000)
                }
            } finally {
                synchronized(matchLock) { if (match === m) match = null }
                node?.stop()
            }
        }
    }

    /** The bootstrap node runs next to the chain node, so its address comes from the node address. */
    private fun joinGossip(rpcUrl: String): GossipNode? = runCatching {
        val host = InetAddress.getByName(URI(rpcUrl.trim()).host)
        val address = "/${if (host is Inet6Address) "ip6" else "ip4"}/${host.hostAddress}/tcp/${Matchmaker.DEFAULT_PORT}"
        GossipNode.start(GossipConfig(listen = null, bootstrap = listOf(address), router = false))
    }.onFailure { e -> _state.update { it.copy(error = message(e)) } }.getOrNull()

    private fun leaveGossip() {
        poller?.cancel()
        synchronized(matchLock) {
            session++
            match = null
        }
        sharingFor = null
        watchingQuote = null
    }

    /** Finishes the create-account screen: keeps the name on this phone and connects in the picked role. */
    fun createAccount(name: String, role: Role, rpcUrl: String) {
        prefs.edit().putString("name", name.trim()).apply()
        _state.update { it.copy(name = name.trim()) }
        connect(role, rpcUrl)
        _state.update { it.copy(showFunding = role == Role.RIDER) }
    }

    fun fundingDone() = _state.update { it.copy(showFunding = false) }

    /** Back to the welcome screen. */
    fun signOut() {
        stopLocation()
        leaveGossip()
        mapJob?.cancel()
        client = null
        prefs.edit().remove("role").apply()
        _state.update { UiState(rpcUrl = it.rpcUrl, name = it.name) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- rider actions

    /**
     * Ask nearby drivers for quotes up to `maxFareReais` for the measured route. The request says how far and how
     * long the trip is, rounded, and carries a salted hash of the route. Where it starts and ends stays here.
     */
    fun requestRide(coin: Coin, maxFareReais: BigDecimal) = act {
        _state.update { it.copy(askedAgain = false) }
        announce(match, coin, coin.toBaseUnits(maxFareReais))
    }

    /** What the rider last asked for, kept so the same request can go out again if the driver gives the ride up. */
    private var lastAsk: Pair<Coin, BigInteger>? = null

    /** Rides this phone cancelled itself. A ride cancelled any other way was given up by its driver. */
    private val cancelledHere = HashSet<BigInteger>()
    private val settled = HashSet<BigInteger>()

    @Synchronized
    private fun announce(m: Matchmaker?, coin: Coin, maxFare: BigInteger) {
        val route = _state.value.route ?: throw IllegalStateException("No route yet")
        val id = packId ?: throw IllegalStateException("No map pack")
        // A new salt each time: the same trip asked twice shows as two unrelated hashes.
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val coarse = coarseRoute(route.distanceM, route.durationS)
        val summary = RouteSummary(
            packId = Numeric.toHexString(id),
            routeHash = Numeric.toHexString(routeHash(id, route, salt)),
            distanceM = coarse.distanceM.toInt(),
            durationS = coarse.durationS.toInt(),
            // Roughly where the trip starts and ends, so a driver can judge it. Never the points themselves.
            pickupCell = regionCell(route.pickup),
            dropoffCell = regionCell(route.dropoff),
        )
        pending = route to salt
        lastAsk = coin to maxFare
        m?.requestRide(coin, maxFare, summary)
    }

    /**
     * Rider: what follows a ride that was just cancelled. If the driver gave it up, by declining or by cancelling
     * after accepting, the rider has everything back and still wants to go: the same request goes out again to
     * the other drivers. If the rider cancelled, the destination goes with the ride.
     */
    @Synchronized
    private fun afterCancelled(m: Matchmaker?, ride: Ride) {
        // Two refreshes can see the same cancellation; it is acted on once.
        if (!settled.add(ride.id)) return
        val ask = lastAsk
        if (ride.id in cancelledHere || ask == null || _state.value.route == null) {
            _state.update { it.copy(destination = null, route = null, places = emptyList(), askedAgain = false) }
            return
        }
        val asked = runCatching { announce(m, ask.first, ask.second) }
        _state.update { it.copy(askedAgain = asked.isSuccess, error = asked.exceptionOrNull()?.let(::message)) }
    }

    /**
     * Rider: asks again at another price when no driver is biting. The request out there is withdrawn and the
     * same trip goes out at the new price, which the fare guide's range still bounds.
     */
    fun changePrice(request: RideRequest, price: BigInteger) = act {
        val coin = it.coinFor(request.token)
        _state.update { s -> s.copy(askedAgain = false) }
        match?.closeRequest(request)
        announce(match, coin, price)
    }

    fun cancelRequest(request: RideRequest) = act {
        _state.update { it.copy(askedAgain = false) }
        match?.closeRequest(request)
    }

    /**
     * Take a driver's quote: its fare is what gets locked in the escrow. The full quote, with the route the
     * locked hash commits to, is kept on this phone as the ride's evidence and sent sealed to the chosen driver.
     */
    fun acceptOffer(request: RideRequest, offer: Offer) = act {
        val terms = OfferTerms(offer.fare.toString(), request.token, request.rider, offer.driver, offer.expiresAt.toULong())
        // Lock only an offer for the request this phone has out now. Asking again, at another price or from
        // another pickup, replaces the route and salt kept here; an offer made for the request before that
        // commits to another route, and locking it would hold the money on one route and reveal another.
        if (offer.requestId != request.id) throw IllegalStateException("This offer is for another request. Pick an offer again.")
        val (route, salt) = pending ?: throw IllegalStateException("This request is no longer the one out. Pick an offer again.")
        val id = packId ?: throw IllegalStateException("No map pack")
        if (!routeHash(id, route, salt).contentEquals(Numeric.hexStringToByteArray(request.route.routeHash))) {
            throw IllegalStateException("This offer was for a request that has been replaced. Pick an offer again.")
        }
        val quote = quoteJson(id, route, salt, terms)
        // The chosen driver gets the route sealed to the key in their offer; nobody else in the cell can read it.
        val rideId = match?.acceptOffer(request, offer, quote) ?: return@act
        File(getApplication<Application>().filesDir, "rides").apply { mkdirs() }.resolve("$rideId.quote.json").writeText(quote)
    }

    /**
     * Confirms arrival, which pays the driver, and rates them when `stars` is 1 to 5.
     *
     * The receipt hash is a stand-in. The real one is the hash of the trip receipt both phones sign over their
     * GPS logs (`TripLog` and `MapPack.draftReceipt` in the core), which needs the encrypted ride channel the
     * phones do not have yet.
     */
    fun complete(id: BigInteger, stars: Int = 0) = act {
        it.complete(id, Hash.sha3("receipt:$id:${System.currentTimeMillis()}".toByteArray()))
        if (stars in 1..5) it.rate(id, stars)
        _state.update { s -> s.copy(destination = null, route = null, places = emptyList()) }
        // The rider is somewhere else now: the pin goes back to following the phone.
        pinByHand = false
    }

    // ---------------------------------------------------------------- map

    /** The pack mirror runs next to the chain node, so its address comes from the node address. */
    private fun mirror(): String = "http://${URI(_state.value.rpcUrl.trim()).host}:${MapStore.MIRROR_PORT}"

    /** Finds out which pack governance activated for the region and opens it if this phone has it. */
    private fun prepareMap() {
        val c = client ?: return
        mapJob?.cancel()
        pack?.close()
        pack = null
        mapJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(map = MapState.Checking) }
            // The chain node may still be starting, and the pack may be published later: keep asking.
            var id: ByteArray? = null
            while (isActive && id == null) {
                val asked = runCatching { c.latestPack(MapStore.PILOT_REGION) }
                id = asked.getOrNull()
                if (id == null) {
                    if (asked.isSuccess) _state.update { it.copy(map = MapState.NotPublished) }
                    delay(5_000)
                }
            }
            packId = id ?: return@launch
            if (maps.isInstalled(id)) {
                openMap(c, id)
            } else {
                val bytes = runCatching { maps.size(mirror(), id) }.getOrDefault(0)
                _state.update { it.copy(map = MapState.NotDownloaded(bytes)) }
            }
        }
    }

    private fun openMap(c: KlippelGoClient, id: ByteArray) {
        val opened = runCatching { maps.open(id, c) }
        pack = opened.getOrNull()
        packBounds = null
        _state.update { s ->
            val p = opened.getOrNull()?.pack ?: return@update s.copy(map = MapState.Failed(message(opened.exceptionOrNull()!!)))
            val centre = p.bounds()?.let {
                Coordinate((it.southWest.latE7 + it.northEast.latE7) / 2, (it.southWest.lonE7 + it.northEast.lonE7) / 2)
            } ?: Coordinate(0, 0)
            packBounds = p.bounds()
            s.copy(map = MapState.Ready(p.region(), p.version().toInt(), p.basemapPath(), centre), pickup = s.pickup ?: centre)
        }
        pinWhereThePhoneIs()
        // Name the spot the pin starts on; after that the name follows the rider's hand.
        val at = _state.value.pickup ?: return
        val name = runCatching { pack?.pack?.reverse(at)?.name }.getOrNull() ?: return
        _state.update { if (it.pickup == at && it.pickupName == null) it.copy(pickupName = name) else it }
    }

    /** Fetches the region's pack from the mirror, checks it and opens it. */
    fun downloadMap() {
        val c = client ?: return
        val id = packId ?: return
        if (_state.value.map is MapState.Downloading) return
        mapJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(map = MapState.Downloading(0, 0)) }
            val result = runCatching {
                maps.download(mirror(), id) { done, total -> _state.update { it.copy(map = MapState.Downloading(done, total)) } }
            }
            if (result.isSuccess) {
                openMap(c, id)
            } else {
                _state.update { it.copy(map = MapState.Failed(message(result.exceptionOrNull()!!))) }
            }
        }
    }

    /** Rider moved the map: the pin is the pickup point. A route already measured is measured again from it. */
    fun setPickup(at: Coordinate) {
        pinByHand = true
        if (at == _state.value.pickup) return
        _state.update { it.copy(pickup = at, route = null, routeError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching { pack?.pack?.reverse(at)?.name }.getOrNull()
            _state.update { if (it.pickup == at) it.copy(pickupName = name) else it }
        }
        _state.value.destination?.let(::chooseDestination)
    }

    /** Rider: whether the pin is where the rider put it. Until then, and again after a ride, it follows the phone. */
    @Volatile private var pinByHand = false

    /** The area the open map pack covers. A position outside it is no pickup. */
    @Volatile private var packBounds: Bounds? = null

    /**
     * Rider: the pin starts where the phone is and follows it, until the rider puts it somewhere else or starts
     * asking for a ride. After a ride it follows again, so the next one starts where the last one ended. The
     * position stays on this phone: it becomes a pickup like one picked by hand, and leaves only as those do (A9).
     */
    private fun pinWhereThePhoneIs() {
        val bounds = packBounds ?: return
        var moved: Coordinate? = null
        // Decided and done in one step: the rider's hand on the map, or a request going out, may come in between.
        _state.update { s ->
            moved = null
            val here = s.location ?: return@update s
            if (s.role != Role.RIDER || pinByHand || s.destination != null || s.request != null || s.rides.any { it.status.isActive }) return@update s
            if (here == s.pickup) return@update s
            if (here.latE7 !in bounds.southWest.latE7..bounds.northEast.latE7 || here.lonE7 !in bounds.southWest.lonE7..bounds.northEast.lonE7) return@update s
            moved = here
            s.copy(pickup = here, route = null, routeError = null)
        }
        val at = moved ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching { pack?.pack?.reverse(at)?.name }.getOrNull()
            _state.update { if (it.pickup == at) it.copy(pickupName = name) else it }
        }
    }

    /**
     * Rider picked the pickup by name, from the search, instead of moving the map under the pin. A route
     * already measured is measured again from it.
     */
    fun choosePickup(place: Place) {
        searchJob?.cancel()
        pinByHand = true
        _state.update { it.copy(pickup = place.at, pickupName = place.name, places = emptyList(), route = null, routeError = null) }
        _state.value.destination?.let(::chooseDestination)
    }

    /**
     * Rider: moves the pickup of a request that is already out. A request commits to its route, so it cannot
     * be moved: the one out there is withdrawn and the same destination is asked again from the new pickup, at
     * the price the fare guide recommends for the new route. The UI asks the rider before calling this.
     *
     * The new route is measured first. If there is none, the request out there stays as it is.
     */
    fun changePickup(request: RideRequest, place: Place) {
        val c = client ?: return
        val role = _state.value.role ?: return
        val router = pack ?: return
        val destination = _state.value.destination ?: return
        routeJob?.cancel()
        searchJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, places = emptyList()) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val route = router.route(place.at, destination.at, VehicleKind.CAR)
                    // The rider may have backed out while the route was being measured. Then there is nothing to move.
                    if (_state.value.destination != destination) return@runCatching
                    // The request expired or was replaced meanwhile: say so, instead of moving one that is not out.
                    if (_state.value.request?.id != request.id) throw IllegalStateException("This request is no longer the one out. Try again.")
                    val coin = c.coinFor(request.token)
                    val trip = coarseRoute(route.distanceM, route.durationS)
                    val price = coin.fromCentavos((_state.value.tariff ?: c.tariff).recommended(trip.distanceM.toInt(), trip.durationS.toInt()))
                    match?.closeRequest(request)
                    pinByHand = true
                    _state.update { it.copy(pickup = place.at, pickupName = place.name, route = route, routeError = null, askedAgain = false) }
                    announce(match, coin, price)
                }
            }
            _state.update { it.copy(busy = false, error = result.exceptionOrNull()?.let(::message)) }
            withContext(Dispatchers.IO) { refresh(c, match, role) }
        }
    }

    /** Looks the typed text up in the pack's own search index. Nothing leaves the phone. */
    fun searchPlaces(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150) // let the rider finish the word
            val found = runCatching { pack?.pack?.search(query, _state.value.pickup, 8u) }.getOrNull().orEmpty()
            if (isActive) _state.update { it.copy(places = found) }
        }
    }

    /** Routes from the pickup pin to the place, on this phone, and measures the route with the verifier. */
    fun chooseDestination(place: Place) {
        if (_state.value.pickup == null) return
        val router = pack ?: return
        routeJob?.cancel()
        _state.update { it.copy(destination = place, route = null, routing = true, routeError = null, places = emptyList()) }
        // Read once the destination is set: from then on the pin no longer follows the phone, so this is the
        // pickup the screen goes on naming.
        val from = _state.value.pickup ?: return
        routeJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { router.route(from, place.at, VehicleKind.CAR) }
            if (isActive) {
                _state.update {
                    it.copy(route = result.getOrNull(), routing = false, routeError = result.exceptionOrNull()?.let(::message))
                }
            }
        }
    }

    fun clearDestination() {
        routeJob?.cancel()
        _state.update { it.copy(destination = null, route = null, routing = false, routeError = null, places = emptyList()) }
    }

    /** Adds test reais, the local testnet's stand-in for paying a PIX. */
    fun addMoney(coin: Coin, reais: BigDecimal) = act { it.mint(coin, coin.toBaseUnits(reais)) }

    // ---------------------------------------------------------------- driver actions

    /** Quote a price for a rider's request. Sending again replaces the earlier quote. */
    fun sendOffer(request: RideRequest, fareReais: BigDecimal) = act {
        match?.sendOffer(request, it.coinFor(request.token).toBaseUnits(fareReais))
    }

    fun accept(id: BigInteger) = act { it.accept(id) }

    fun start(id: BigInteger) = act { it.start(id, Hash.sha3("pickup:$id:${System.currentTimeMillis()}".toByteArray())) }

    // ---------------------------------------------------------------- where this phone is

    /**
     * Starts following this phone's position, once its owner has allowed it. It places the driver on their
     * own map and is where the way to the next pickup starts; a driver's leaves the phone only sealed to the
     * rider of the ride under way ([sharePosition]). A rider's is where the pickup pin starts
     * ([pinWhereThePhoneIs]) and is never sent as a position.
     */
    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locating) return
        val app = getApplication<Application>()
        val granted = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return
        locating = true
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let(locationListener::onLocationChanged)
        // A fix a second while the car moves: on a ride the rider watches it, and the route behind it is taken
        // off the map. None while it stands still.
        for (provider in providers) manager.requestLocationUpdates(provider, 1_000L, 3f, locationListener, Looper.getMainLooper())
    }

    private var locating = false
    private val locationListener = LocationListener { fix ->
        _state.update { it.copy(location = Coordinate((fix.latitude * 1e7).toInt(), (fix.longitude * 1e7).toInt())) }
        pinWhereThePhoneIs()
    }

    private fun stopLocation() {
        if (!locating) return
        locating = false
        (getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
    }

    private fun quoteFile(quoteHash: String) =
        File(getApplication<Application>().filesDir, "rides").apply { mkdirs() }.resolve("${quoteHash.lowercase()}.route.json")

    /**
     * Driver: the route of each ride this phone was chosen for. The rider seals it to the key in the driver's
     * offer; here it is opened, checked on this phone's own map pack, and accepted only if it hashes to the
     * quote locked in the escrow, which is the one the driver signed. Without the map pack there is nothing to
     * check it against, and nothing is shown.
     */
    private fun rideRoutes(c: KlippelGoClient, m: Matchmaker?, rides: List<Ride>): Map<BigInteger, CheckedQuote> {
        val open = pack?.pack ?: return emptyMap()
        val escrow = EscrowRef(c.deployment.chainId.toULong(), c.deployment.escrow)
        val known = _state.value.rideRoutes
        return rides.filter { it.status.isActive }.mapNotNull { ride ->
            known[ride.id]?.let { return@mapNotNull ride.id to it }
            val file = quoteFile(ride.quoteHash)
            val quote = m?.revealedQuote(ride.quoteHash) ?: file.takeIf { it.exists() }?.readText() ?: return@mapNotNull null
            val checked = runCatching { open.checkQuote(escrow, quote) }.getOrNull() ?: return@mapNotNull null
            if (!checked.quoteHash.contentEquals(Numeric.hexStringToByteArray(ride.quoteHash))) return@mapNotNull null
            if (!file.exists()) file.writeText(quote)
            ride.id to checked
        }.toMap()
    }

    /**
     * Rider: the route of each ride under way, read back from the quote this phone saved when it locked the fare,
     * so the map still shows it after the app was closed.
     */
    private fun ownRoutes(c: KlippelGoClient, rides: List<Ride>): Map<BigInteger, CheckedQuote> {
        val open = pack?.pack ?: return emptyMap()
        val escrow = EscrowRef(c.deployment.chainId.toULong(), c.deployment.escrow)
        val known = _state.value.rideRoutes
        return rides.filter { it.status.isActive }.mapNotNull { ride ->
            known[ride.id]?.let { return@mapNotNull ride.id to it }
            val file = File(getApplication<Application>().filesDir, "rides/${ride.id}.quote.json").takeIf { it.exists() } ?: return@mapNotNull null
            val checked = runCatching { open.checkQuote(escrow, file.readText()) }.getOrNull() ?: return@mapNotNull null
            ride.id to checked
        }.toMap()
    }

    /**
     * Driver: lets the rider of the ride this phone is on see where it is: the oldest of all the rides it
     * accepted and has not finished, never just of the ones on screen. Only that rider: the position is
     * sealed to a key they sent with their route, and riders whose turn has not come get nothing. The key is kept
     * next to the saved route, so it survives the app being closed.
     */
    private fun sharePosition(m: Matchmaker?, current: Ride?) {
        // A refresh still running for a driver session the phone has left sends nothing: a rider's position is no driver's.
        val s = _state.value
        if (s.role != Role.DRIVER || match !== m) return
        val here = s.location ?: return
        current ?: return
        val file = File(getApplication<Application>().filesDir, "rides").apply { mkdirs() }.resolve("${current.quoteHash.lowercase()}.riderkey")
        val key = m?.riderKey(current.quoteHash)?.also { if (!file.exists()) file.writeBytes(it) }
            ?: file.takeIf { it.exists() }?.readBytes() ?: return
        runCatching { m?.sharePosition(current.quoteHash, key, here.latE7, here.lonE7, onTheRide = current.status == RideStatus.Started) }
    }

    /**
     * Driver: the ride positions are being sent for. Rider: the quote of the ride whose driver is being watched.
     * Each refresh sets them from the chain, with the time, and a refresh that fails clears them: between
     * refreshes they are believed for a few seconds and no longer, so a driver's position stops going out soon
     * after the ride ends even when the chain cannot be read (A10).
     */
    @Volatile private var sharingFor: Ride? = null
    @Volatile private var watchingQuote: String? = null
    @Volatile private var rideConfirmedAt = 0L

    /** How long what a refresh said about the ride under way is acted on without another: about two refreshes. */
    private val RIDE_BELIEVED_MS = 6_000L

    /**
     * Once a second, between refreshes, while a ride has started: the driver's phone tells the rider where it
     * is, and the rider's phone takes in what arrived and moves the car on its map. Nothing here reads the
     * chain; which ride it is about was settled by the last refresh.
     */
    private fun positions(m: Matchmaker?, role: Role) {
        m ?: return
        if (android.os.SystemClock.elapsedRealtime() - rideConfirmedAt > RIDE_BELIEVED_MS) return
        if (role == Role.DRIVER) {
            sharingFor?.takeIf { it.status == RideStatus.Started }?.let { sharePosition(m, it) }
            return
        }
        val quote = watchingQuote ?: return
        m.tick()
        val at = m.driverPosition(quote)?.let { Coordinate(it.latE7, it.lonE7) } ?: return
        _state.update { if (it.role != role || it.driverAt == at) it else it.copy(driverAt = at) }
    }

    /**
     * Driver: the stretches between rides, routed on this phone. From where the driver is to the first pickup
     * while that ride has not started, and from each ride's drop-off to the pickup of the one after.
     */
    private fun links(rides: List<Ride>, routes: Map<BigInteger, CheckedQuote>): List<List<Coordinate>> {
        val router = pack ?: return emptyList()
        // Oldest first: the ride being driven, then the ones taken after it.
        val queue = rides.filter { it.status.isActive && it.id in routes }.sortedBy { it.id }
        val stops = mutableListOf<Pair<Coordinate, Coordinate>>()
        val first = queue.firstOrNull() ?: return emptyList()
        val here = _state.value.location
        if (here != null && first.status != RideStatus.Started) stops += here to routes.getValue(first.id).pickup
        queue.zipWithNext { a, b -> stops += routes.getValue(a.id).dropoff to routes.getValue(b.id).pickup }
        return stops.mapNotNull { (from, to) ->
            // Rounded to about 100 m, so a moving driver does not ask for a new route every two seconds.
            val key = listOf(from.latE7 / 10_000, from.lonE7 / 10_000, to.latE7 / 10_000, to.lonE7 / 10_000)
            linkCache.getOrPut(key) { runCatching { router.route(from, to, VehicleKind.CAR).line }.getOrNull() }
        }.also { if (linkCache.size > 64) linkCache.clear() }
    }

    private val linkCache = HashMap<List<Int>, List<Coordinate>?>()

    // ---------------------------------------------------------------- both

    fun cancel(id: BigInteger) = act {
        cancelledHere += id
        // A driver who gives a ride up does not want the same rider's request straight back.
        val ride = _state.value.rides.firstOrNull { r -> r.id == id }
        if (ride != null && ride.driver == it.address) refused[ride.rider] = System.currentTimeMillis()
        it.cancel(id)
    }

    // ---------------------------------------------------------------- driver's rules

    /** Riders whose ride this driver just gave up, and when. Their requests are skipped for a short while. */
    private val refused = HashMap<String, Long>()

    /** Skip requests under this many reais per km. Zero switches the rule off. */
    fun setMinPerKm(reais: BigDecimal) {
        val value = reais.max(BigDecimal.ZERO)
        prefs.edit().putString("minPerKm", value.toPlainString()).apply()
        _state.update { it.copy(minPerKm = value) }
    }

    /** Skip requests priced more than this many percent under the recommendation. */
    fun setMaxBelowPercent(percent: Int) {
        val limit = (_state.value.tariff?.riderRangeBps ?: 1_000) / 100
        val value = percent.coerceIn(0, limit)
        prefs.edit().putInt("maxBelowPercent", value).apply()
        _state.update { it.copy(maxBelowPercent = value) }
    }

    private val regionCache = HashMap<BigInteger, TripRegions>()

    /** The two regions of a request, named from this phone's map pack when it has one. */
    private fun regionsOf(request: RideRequest): TripRegions? = regionCache[request.id] ?: run {
        val pickup = regionBounds(request.route.pickupCell) ?: return null
        val dropoff = regionBounds(request.route.dropoffCell) ?: return null
        fun name(b: Bounds): String? {
            val middle = Coordinate((b.southWest.latE7 + b.northEast.latE7) / 2, (b.southWest.lonE7 + b.northEast.lonE7) / 2)
            return runCatching { pack?.pack?.locality(middle) }.getOrNull()
        }
        val known = pack != null
        TripRegions(pickup, dropoff, name(pickup), name(dropoff)).also {
            // Without the map pack there are no names yet: look again once it is there.
            if (known) regionCache[request.id] = it
            if (regionCache.size > 256) regionCache.clear()
        }
    }

    /**
     * Whether a request gets past this driver's rules: it pays enough per km, it is not too far under the
     * recommended price, and it is not from a rider whose ride this driver gave up in the last few minutes.
     */
    private fun wanted(c: KlippelGoClient, tariff: Tariff?, request: RideRequest): Boolean {
        val s = _state.value
        val since = refused[request.rider]
        if (since != null && System.currentTimeMillis() - since < SKIP_AFTER_REFUSAL_MS) return false
        val coin = runCatching { c.coinFor(request.token) }.getOrNull() ?: return true
        val price = coin.toReais(request.maxFare)
        val perKm = price.multiply(BigDecimal(1000)).divide(BigDecimal(maxOf(request.route.distanceM, 1)), 2, java.math.RoundingMode.HALF_UP)
        if (perKm < s.minPerKm) return false
        if (tariff != null) {
            val recommended = coin.fromCentavos(tariff.recommended(request.route.distanceM, request.route.durationS))
            val lowest = recommended - recommended * BigInteger.valueOf(s.maxBelowPercent.toLong()) / BigInteger.valueOf(100)
            if (request.maxFare < lowest) return false
        }
        return true
    }

    fun rate(id: BigInteger, stars: Int) = act { it.rate(id, stars) }

    private fun act(block: (KlippelGoClient) -> Unit) {
        val c = client ?: return
        val role = _state.value.role ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { block(c) } }
            _state.update { it.copy(busy = false, error = result.exceptionOrNull()?.let(::message)) }
            withContext(Dispatchers.IO) { refresh(c, match, role) }
        }
    }

    private fun refresh(c: KlippelGoClient, m: Matchmaker?, role: Role) {
        // Gossip first and on its own: matching must keep running while the chain node is unreachable.
        runCatching { m?.tick() }
        val open = m?.openRequests().orEmpty()
        val request = if (role == Role.RIDER) open.firstOrNull { it.rider == c.address } else null
        val offers = request?.let { m?.offers(it) }.orEmpty()
        // The tariff comes from the chain once; until it has, only the rules that need no tariff apply.
        val tariff = _state.value.tariff ?: runCatching { c.tariff }.getOrNull()
        val asked = if (role == Role.DRIVER) open else emptyList()
        val requests = asked.filter { wanted(c, tariff, it) }
        val myOffers = requests.mapNotNull { r -> m?.offers(r, c.address)?.firstOrNull()?.let { r.id to it } }.toMap()
        _state.update {
            if (it.role != role) it else it.copy(
                request = request, offers = offers, requests = requests, myOffers = myOffers,
                tariff = tariff, skippedRequests = asked.size - requests.size,
                regions = requests.mapNotNull { r -> regionsOf(r)?.let { r.id to it } }.toMap(),
                gossipConnected = m?.let { g -> g.peers() > 0 },
            )
        }
        val result = runCatching {
            c.chainId()
            val balances = c.coins.associateWith { c.balanceOf(it) }
            val configs = c.coins.associateWith { c.tokenConfig(it) }
            val fees = configs.mapValues { it.value.first }
            val taxBps = c.taxBps()
            val infrastructure = infrastructureEstimate(c, role)
            val bonds = configs.mapValues { it.value.second to it.value.third }
            // A driver's unfinished rides always show, however many newer ones name them: anyone can open a
            // ride naming a driver, and that must not push the one they are driving off the screen.
            // When the rides were read, on a clock nobody can set: what follows can take long, and the time a
            // position may still go out for a ride counts from here.
            val readAt = android.os.SystemClock.elapsedRealtime()
            val underWay = if (role == Role.DRIVER) c.underWay() else emptyList()
            val recent = (if (role == Role.RIDER) c.ridesAsRider() else c.ridesAsDriver()).take(MAX_RIDES)
            val ids = (recent + underWay.map { it.id }).distinct().sortedDescending()
            val rides = ids.map { c.ride(it) }
            val rated = rides.filter { it.status == RideStatus.Completed && c.hasRated(it.id) }
                .map { it.id }.toSet()
            val drivers = if (role == Role.RIDER) c.drivers() else emptyList()
            val me = if (role == Role.DRIVER) c.driverInfo() else null
            val canAccept = me?.canAccept == true
            val blockers = if (me != null && !canAccept) c.acceptBlockers() else emptyList()
            if (role == Role.RIDER) {
                // The ride this phone last saw under way, if it has since been cancelled.
                val before = _state.value.rides.firstOrNull { it.status.isActive }
                rides.firstOrNull { it.id == before?.id && it.status == RideStatus.Cancelled }?.let { afterCancelled(m, it) }
            }
            val rideRoutes = if (role == Role.DRIVER) rideRoutes(c, m, rides) else ownRoutes(c, rides)
            // Rider: is the driver still busy with someone else, and if not, where are they.
            val mine = rides.firstOrNull { it.status.isActive }.takeIf { role == Role.RIDER }
            // A read that failed says nothing: keep what was known, never "nobody ahead".
            val ridesAhead = mine?.let { runCatching { c.ridesAhead(it) }.getOrDefault(_state.value.ridesAhead) } ?: 0
            val driverAt = mine?.takeIf { ridesAhead == 0 }?.let { m?.driverPosition(it.quoteHash) }?.let { Coordinate(it.latE7, it.lonE7) }
            if (role == Role.DRIVER) sharePosition(m, underWay.firstOrNull())
            // Only for the session this refresh belongs to: one left behind by a change of account must not
            // hand its ride to the next.
            synchronized(matchLock) {
                if (m != null && match === m) {
                    sharingFor = underWay.firstOrNull()
                    rideConfirmedAt = readAt
                    // Only once the ride has started is the driver heard every second; before, each refresh is enough.
                    watchingQuote = mine?.takeIf { ridesAhead == 0 && it.status == RideStatus.Started }?.quoteHash
                }
            }
            val links = if (role == Role.DRIVER) links(rides, rideRoutes) else emptyList()
            _state.update {
                if (it.role != role) it else it.copy(
                    connected = true, balances = balances, fees = fees, bonds = bonds, rides = rides, rated = rated,
                    taxBps = taxBps, infrastructure = infrastructure,
                    // Only for rides this phone paid for from their first transaction: a part is not shown as the whole.
                    infrastructureSpent = rides.mapNotNull { r -> c.gasSpent(r.id)?.let { wei -> r.id to c.infrastructureCost(wei) } }.toMap(),
                    drivers = drivers, canAccept = canAccept, blockers = blockers, me = me,
                    rideRoutes = rideRoutes, links = links, ridesAhead = ridesAhead, driverAt = driverAt,
                )
            }
            // A ride that just ended leaves the rider where it took them.
            pinWhereThePhoneIs()
        }
        if (result.isFailure) {
            sharingFor = null
            watchingQuote = null
            _state.update { if (it.role != role) it else it.copy(connected = false) }
        }
    }

    private var gasPriceAt = 0L
    private var gasPrice: BigInteger? = null

    /**
     * What a ride's transactions on this side should cost the network, in reais. The gas price moves, so it is
     * asked again now and then, not on every refresh. Nothing when governance set no rate for the chain's coin.
     */
    private fun infrastructureEstimate(c: KlippelGoClient, role: Role): BigDecimal? {
        if (c.nativeCoinCentavos.signum() == 0) return null
        val now = System.currentTimeMillis()
        if (gasPrice == null || now - gasPriceAt > GAS_PRICE_EVERY_MS) {
            gasPrice = runCatching { c.gasPrice() }.getOrNull() ?: gasPrice
            gasPriceAt = now
        }
        val price = gasPrice ?: return null
        val gas = if (role == Role.RIDER) KlippelGoClient.RIDER_RIDE_GAS else KlippelGoClient.DRIVER_RIDE_GAS
        return c.infrastructureCost(gas * price)
    }

    private fun message(t: Throwable): String = t.message ?: t.javaClass.simpleName

    override fun onCleared() {
        stopLocation()
        leaveGossip()
        pack?.close()
    }

    companion object {
        private const val MAX_RIDES = 10

        /** How often the network's gas price is asked for again. */
        private const val GAS_PRICE_EVERY_MS = 30_000L

        /** How long a driver who gave a ride up is not shown that rider's requests. */
        private const val SKIP_AFTER_REFUSAL_MS = 3 * 60_000L

        /** Prefers the addresses from the last deploy (bundled at build time), else a fresh testnet's. */
        private fun loadDeployment(app: Application): Deployment = runCatching {
            app.assets.open("local.json").bufferedReader().use { Deployment.fromJson(it.readText()) }
        }.getOrDefault(Deployment.LOCAL_DEFAULT)
    }
}
