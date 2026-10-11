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
    /** Driver: where this phone is, once the driver has allowed reading it. */
    val location: Coordinate? = null,
    /** Driver: the checked route of each ride the driver was chosen for, by ride id. */
    val rideRoutes: Map<BigInteger, CheckedQuote> = emptyMap(),
    /** Driver: the way to the first pickup and between rides taken one after the other. */
    val links: List<List<Coordinate>> = emptyList(),
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
     * Pick the role for this device. Each role uses a seeded test account: the first rider or driver, or the
     * second when `pair` is 2, so that four phones can play two riders and two drivers. The pair sticks to the
     * phone until another is given.
     */
    fun connect(role: Role, rpcUrl: String, pair: Int = prefs.getInt("pair", 1)) {
        prefs.edit().putString("role", role.name).putString("rpc", rpcUrl).putInt("pair", pair).apply()
        val key = when (role) {
            Role.RIDER -> if (pair == 2) DevAccount.RIDER_2 else DevAccount.RIDER
            Role.DRIVER -> if (pair == 2) DevAccount.DRIVER_2 else DevAccount.DRIVER
        }
        val c = KlippelGoClient(rpcUrl.trim(), key.privateKey, deployment)
        client = c
        _state.value = UiState(role = role, rpcUrl = rpcUrl, address = c.address, name = _state.value.name)
        leaveGossip()
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
        val quote = pending?.let { (route, salt) -> packId?.let { id -> quoteJson(id, route, salt, terms) } }
        // The chosen driver gets the route sealed to the key in their offer; nobody else in the cell can read it.
        val rideId = match?.acceptOffer(request, offer, quote) ?: return@act
        if (quote != null) {
            File(getApplication<Application>().filesDir, "rides").apply { mkdirs() }.resolve("$rideId.quote.json").writeText(quote)
        }
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
        _state.update { s ->
            val p = opened.getOrNull()?.pack ?: return@update s.copy(map = MapState.Failed(message(opened.exceptionOrNull()!!)))
            val centre = p.bounds()?.let {
                Coordinate((it.southWest.latE7 + it.northEast.latE7) / 2, (it.southWest.lonE7 + it.northEast.lonE7) / 2)
            } ?: Coordinate(0, 0)
            s.copy(map = MapState.Ready(p.region(), p.version().toInt(), p.basemapPath(), centre), pickup = s.pickup ?: centre)
        }
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
        if (at == _state.value.pickup) return
        _state.update { it.copy(pickup = at, route = null, routeError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching { pack?.pack?.reverse(at)?.name }.getOrNull()
            _state.update { if (it.pickup == at) it.copy(pickupName = name) else it }
        }
        _state.value.destination?.let(::chooseDestination)
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
        val from = _state.value.pickup ?: return
        val router = pack ?: return
        routeJob?.cancel()
        _state.update { it.copy(destination = place, route = null, routing = true, routeError = null, places = emptyList()) }
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

    // ---------------------------------------------------------------- driver's map

    /**
     * Starts following this phone's position, once the driver has allowed it. The position stays on the phone:
     * it places the driver on their own map and is where the way to the next pickup starts.
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
        for (provider in providers) manager.requestLocationUpdates(provider, 2_000L, 5f, locationListener, Looper.getMainLooper())
    }

    private var locating = false
    private val locationListener = LocationListener { fix ->
        _state.update { it.copy(location = Coordinate((fix.latitude * 1e7).toInt(), (fix.longitude * 1e7).toInt())) }
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
        it.cancel(id)
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
        val requests = if (role == Role.DRIVER) open else emptyList()
        val myOffers = requests.mapNotNull { r -> m?.offers(r, c.address)?.firstOrNull()?.let { r.id to it } }.toMap()
        _state.update {
            if (it.role != role) it else it.copy(
                request = request, offers = offers, requests = requests, myOffers = myOffers,
                gossipConnected = m?.let { g -> g.peers() > 0 },
            )
        }
        val result = runCatching {
            c.chainId()
            val balances = c.coins.associateWith { c.balanceOf(it) }
            val configs = c.coins.associateWith { c.tokenConfig(it) }
            val fees = configs.mapValues { it.value.first }
            val bonds = configs.mapValues { it.value.second to it.value.third }
            val ids = (if (role == Role.RIDER) c.ridesAsRider() else c.ridesAsDriver()).take(MAX_RIDES)
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
            val rideRoutes = if (role == Role.DRIVER) rideRoutes(c, m, rides) else emptyMap()
            val links = if (role == Role.DRIVER) links(rides, rideRoutes) else emptyList()
            _state.update {
                if (it.role != role) it else it.copy(
                    connected = true, balances = balances, fees = fees, bonds = bonds, rides = rides, rated = rated,
                    drivers = drivers, canAccept = canAccept, blockers = blockers, me = me,
                    rideRoutes = rideRoutes, links = links,
                )
            }
        }
        if (result.isFailure) _state.update { if (it.role != role) it else it.copy(connected = false) }
    }

    private fun message(t: Throwable): String = t.message ?: t.javaClass.simpleName

    override fun onCleared() {
        stopLocation()
        leaveGossip()
        pack?.close()
    }

    companion object {
        private const val MAX_RIDES = 10

        /** Prefers the addresses from the last deploy (bundled at build time), else a fresh testnet's. */
        private fun loadDeployment(app: Application): Deployment = runCatching {
            app.assets.open("local.json").bufferedReader().use { Deployment.fromJson(it.readText()) }
        }.getOrDefault(Deployment.LOCAL_DEFAULT)
    }
}
