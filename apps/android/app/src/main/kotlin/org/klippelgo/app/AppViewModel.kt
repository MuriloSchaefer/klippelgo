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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.klippelgo.chain.Blocker
import org.klippelgo.chain.Coin
import org.klippelgo.chain.Deployment
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.DeviceKey
import org.klippelgo.chain.SessionCertificate
import org.klippelgo.chain.SessionKey
import org.klippelgo.chain.DriverInfo
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.chain.LeftOnEscrow
import org.klippelgo.chain.Matchmaker
import org.klippelgo.chain.ReleaseCheck
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
    /** Driver: online, hearing requests. */
    val online: Boolean = false,
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
    /**
     * Both roles: what the release registry says of the release this app is, and of the latest one. Null until
     * first read. Revoked, the app starts no new ride; deprecated, or with a newer release out, it offers the update.
     */
    val release: ReleaseCheck? = null,
    /** Both roles: the latest release's APK, while it is fetched and checked for installing. */
    val update: UpdateState = UpdateState.Idle,
    /** Both roles: rides unfinished and money owed on escrows of earlier releases. Empty when nothing is left. */
    val leftOnPrevious: List<LeftOnEscrow> = emptyList(),
) {
    /**
     * Both roles: whether an update must wait. It waits until the chain was read once (the rides are not known
     * before) and while a ride of this account is unfinished, since the ride settles on the escrow it opened on,
     * which a release with other code contracts no longer finishes. An Open ride counts only when this account
     * locked it: anyone can open one naming a driver, it holds no bond of the driver's, and the rider takes it
     * back on the old escrow, so it must not hold the driver's update. Only the current role's rides are known
     * here (P25).
     */
    val updateMustWait: Boolean
        get() = connected != true || rides.any {
            when (it.status) {
                RideStatus.Accepted, RideStatus.Started, RideStatus.Disputed -> true
                RideStatus.Open -> it.rider.equals(address, ignoreCase = true)
                else -> false
            }
        }
}

const val DEFAULT_RPC = "http://10.0.2.2:8545"

/**
 * Holds one account's view of the chain and of the gossip network. Every two seconds it takes in gossip and asks
 * the chain whether anything this account's screen shows changed; it reads the chain in full only when something
 * did, after the person did something, and every half minute in any case. Rider and driver run this same code on
 * different emulators. They agree a fare over gossip, and everything after that
 * reaches the other only through the contracts.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    // Brought to this release's shape before anything reads it: an update never needs a fresh install.
    private val prefs = app.getSharedPreferences("klippelgo", Context.MODE_PRIVATE).also(AppData::migrate)
    private val deployment: Deployment = loadDeployment(app)
    private val updater = Updater(app)

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
        viewModelScope.launch {
            _state.map(::onDuty).distinctUntilChanged().collect { OnDutyService.keep(getApplication(), it) }
        }
        _state.value.role?.let { connect(it, _state.value.rpcUrl) }
    }

    /**
     * In a debug build, makes this phone use the local testnet's seeded accounts instead of a key of its own, as
     * scripted launches and the end-to-end tests ask for. Release builds ignore it. `e2e` picks the end-to-end
     * tests' own seeded accounts, so that phones played by hand on the same chain are other people to them.
     */
    fun useDevAccounts(on: Boolean, e2e: Boolean = false) {
        prefs.edit().putBoolean("devAccounts", on && BuildConfig.DEBUG).putBoolean("e2eAccounts", on && e2e && BuildConfig.DEBUG).apply()
    }

    /**
     * Pick the role for this device.
     *
     * A phone is one person with one account, whichever mode it is in: switching between passenger and driver
     * changes what the phone does, not who it is. The account is owned by a key the phone makes in its chip the
     * first time ([KeystoreDeviceKey]); its address follows from that key.
     *
     * In a debug build that was asked to ([useDevAccounts], or a scripted launch naming a `pair`), the account is
     * instead one of four seeded test accounts, chosen the first time from the role the phone starts in and the
     * pair (1 or 2, so that four phones can play two riders and two drivers), and kept from then on. The four are
     * the end-to-end tests' own when they asked for those ([useDevAccounts] with `e2e`).
     */
    fun connect(role: Role, rpcUrl: String, pair: Int? = null) {
        val dev = BuildConfig.DEBUG && (pair != null || prefs.getBoolean("devAccounts", false))
        val (key: DeviceKey, name) = if (dev) {
            val kept = prefs.getString("account", null)?.let { n -> DevAccount.entries.firstOrNull { it.name == n } }
            // A phone set up before accounts were tied to phones remembers only its pair.
            val second = (pair ?: prefs.getInt("pair", 1)) == 2
            val account = if (pair == null && kept != null) kept
                else DevAccount.of(role == Role.DRIVER, second, e2e = prefs.getBoolean("e2eAccounts", false))
            account.key() to account.name
        } else {
            KeystoreDeviceKey.forThisPhone(getApplication()) to DEVICE_ACCOUNT
        }
        prefs.edit().putString("role", role.name).putString("rpc", rpcUrl).putString("account", name).apply()
        val c = KlippelGoClient(rpcUrl.trim(), key, deployment)
        restoreSession(c)
        c.restoreScans(prefs.getString(scansKey(c), "") ?: "")
        savedScans = null
        seenBlock = null
        c.onNewSession = ::saveSession
        client = c
        _state.value = _state.value.let {
            // Where the phone is does not change with the role: it is told again only when it moves.
            UiState(role = role, rpcUrl = rpcUrl, address = c.address, name = it.name, minPerKm = it.minPerKm, maxBelowPercent = it.maxBelowPercent, location = it.location, online = it.online && it.role == role)
        }
        pinByHand = false
        leaveGossip()
        packBounds = null
        prepareMap()
        val mySession = session
        poller = viewModelScope.launch(Dispatchers.IO) {
            val roleBits = if (role == Role.RIDER) KlippelGoClient.ROLE_RIDER else KlippelGoClient.ROLE_DRIVER
            var registered = register(c, roleBits)
            // This loop owns its gossip node and stops it when the role or node address changes.
            val node = joinGossip(rpcUrl)
            val m = node?.let { Matchmaker(c, it) }?.also { it.trace = ::trace.takeIf { BuildConfig.DEBUG } }
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
                    // A new account registers with its first operation, which it pays for in reais: once money is in.
                    if (!registered) registered = register(c, roleBits)
                    if (chainDue(c, m)) refresh(c, m, role) else refreshGossip(c, m, role)
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

    /**
     * Whether the app is on screen. Off screen it reads nothing from the chain unless a ride is under way, which
     * still needs its state confirmed for the driver's position to go out (A10), or the driver is online, for a
     * ride opened naming them; back on screen it reads in full.
     */
    fun foreground(on: Boolean) {
        if (on && !inForeground) seenBlock = null
        inForeground = on
        // A service can start only from the foreground: one refused off screen starts here.
        if (on) OnDutyService.keep(getApplication(), onDuty(_state.value))
    }

    /** Driver: whether they are online, which only their screen knows. */
    fun setOnline(on: Boolean) = _state.update { it.copy(online = on) }

    /**
     * Whether Android must keep this phone working off screen. Anyone can open a ride naming a driver, so for a
     * driver a ride counts once they accepted it (P12). A dispute waits on jurors, with no position to send.
     */
    private fun onDuty(s: UiState) = when (s.role) {
        null -> false
        Role.DRIVER -> s.online || s.rides.any { it.status == RideStatus.Accepted || it.status == RideStatus.Started }
        Role.RIDER -> s.rides.any { it.status.isActive && it.status != RideStatus.Disputed }
    }

    @Volatile private var inForeground = true

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
        mayStartRides(it)
        _state.update { it.copy(askedAgain = false) }
        announce(match, coin, coin.toBaseUnits(maxFareReais))
    }

    /** What the rider last asked for, kept so the same request can go out again if the driver gives the ride up. */
    private var lastAsk: Pair<Coin, BigInteger>? = null

    /** Rides this phone cancelled itself. A ride cancelled any other way was given up by its driver. */
    private val cancelledHere = HashSet<BigInteger>()
    private val settled = HashSet<BigInteger>()

    /** Every request goes out here, so a revoked release asks for no ride whatever path led here (A16). */
    @Synchronized
    private fun announce(m: Matchmaker?, coin: Coin, maxFare: BigInteger) {
        client?.let(::mayStartRides)
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
        mayStartRides(it)
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
        mayStartRides(it)
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
        mayStartRides(it)
        match?.sendOffer(request, it.coinFor(request.token).toBaseUnits(fareReais))
    }

    /**
     * Takes a ride a rider locked. Every other quote this phone has out was given while the driver had this ride
     * still to take, so none of them is the next ride: a rider who locks one now is given it back ([declineVoid]).
     */
    fun accept(id: BigInteger) = act {
        mayStartRides(it)
        it.accept(id)
        val rides = _state.value.rides
        val taken = rides.firstOrNull { r -> r.id == id }?.quoteHash?.lowercase()
        val others = rides.filter { r -> r.status == RideStatus.Open && r.id != id }.associate { r -> r.quoteHash.lowercase() to r.rider.lowercase() }
        voidQuotes((match?.sentQuotes().orEmpty() + others) - setOfNotNull(taken))
    }

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
        locationProviders = providers
        requestFixes()
    }

    /**
     * A fix a second while the car moves on a started ride: the rider watches it, and the route behind it is taken
     * off the map. Otherwise as often as a position goes out to a pickup. None while it stands still.
     */
    @SuppressLint("MissingPermission")
    private fun requestFixes() {
        val manager = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val every = if (fastFixes) 1_000L else 4_000L
        for (provider in locationProviders) runCatching { manager.requestLocationUpdates(provider, every, 3f, locationListener, Looper.getMainLooper()) }
    }

    private fun fixesFor(started: Boolean) {
        if (started == fastFixes) return
        fastFixes = started
        if (locating) requestFixes()
    }

    private var locationProviders = emptyList<String>()
    @Volatile private var fastFixes = false
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
            // The hash is public once quoted, and the escrow keeps whatever hash a ride is opened with. The route
            // is this ride's only if the ride has the quote's own fare, coin, rider and driver; else it copied it.
            if (!sameTerms(checked, ride)) return@mapNotNull null
            if (!file.exists()) file.writeText(quote)
            ride.id to checked
        }.toMap()
    }

    private fun sameTerms(checked: CheckedQuote, ride: Ride): Boolean =
        BigInteger(1, checked.fare) == ride.fare &&
            Numeric.toHexString(checked.token).equals(ride.token, ignoreCase = true) &&
            Numeric.toHexString(checked.rider).equals(ride.rider, ignoreCase = true) &&
            Numeric.toHexString(checked.driver).equals(ride.driver, ignoreCase = true)

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
     * Once a second, between refreshes, while a ride is under way: the driver's phone tells the rider where it
     * is (the matchmaker sends at most every few seconds before the pickup, every second after), and the rider's
     * phone takes in what arrived and moves the car on its map. Nothing here reads the chain; which ride it is
     * about was settled by the last full read, and is believed while the checks of new blocks find nothing new.
     */
    private fun positions(m: Matchmaker?, role: Role) {
        m ?: return
        if (android.os.SystemClock.elapsedRealtime() - rideConfirmedAt > RIDE_BELIEVED_MS) return
        if (role == Role.DRIVER) {
            sharingFor?.let { sharePosition(m, it) }
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

    /** Driver: turns down a ride a rider locked. Changes nothing if it was accepted meanwhile. */
    fun decline(id: BigInteger) = act {
        cancelledHere += id
        _state.value.rides.firstOrNull { r -> r.id == id }?.let { r -> refused[r.rider] = System.currentTimeMillis() }
        it.decline(id)
    }

    // ---------------------------------------------------------------- driver's rules

    /** Riders whose ride this driver just gave up, and when. Their requests are skipped for a short while. */
    private val refused = HashMap<String, Long>()

    /**
     * Driver: quotes this phone gave before it took a ride, by hash, with the rider each was for, newest last. A
     * rider can lock a quote until it expires, after a restart too, so they are kept on the phone; the oldest go
     * past [MAX_VOID_QUOTES].
     */
    private val voided: LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        prefs.getString("voidQuotes", "")!!.split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 } }.forEach { (h, r) -> put(h, r) }
    }

    private fun keepVoided() = prefs.edit().putString("voidQuotes", voided.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    /** Driver: rides this phone is giving back because they were locked on a void quote. */
    private val declining = HashSet<BigInteger>()

    private fun voidQuotes(quotes: Map<String, String>) = synchronized(voided) {
        quotes.keys.forEach(voided::remove)
        voided.putAll(quotes)
        while (voided.size > MAX_VOID_QUOTES) voided.remove(voided.keys.first())
        keepVoided()
    }

    /**
     * Driver: gives back each ride locked on a void quote by the rider it was for: they locked a driver who was
     * free when they quoted and has taken another ride since. Quotes go out in the clear, so a ride anyone else
     * opens with the same hash is left for its rider to withdraw (P12), and so is a second one by the same rider:
     * each decline costs this driver gas. `decline` reverts once a ride is accepted, so one that lands after the
     * driver took the ride after all changes nothing. Runs on its own, so a slow decline does not hold up the
     * refresh and the position it sends.
     */
    private fun declineVoid(c: KlippelGoClient, rides: List<Ride>) {
        val void = synchronized(voided) { HashMap(voided) }
        val due = rides.filter { r -> r.status == RideStatus.Open && r.driver == c.address && void[r.quoteHash.lowercase()] == r.rider.lowercase() }
            .distinctBy { it.quoteHash.lowercase() }
            .filter { synchronized(declining) { declining.add(it.id) } }
        if (due.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (r in due) {
                runCatching { c.decline(r.id) }
                    .onSuccess { synchronized(voided) { voided.remove(r.quoteHash.lowercase()); keepVoided() } }
                synchronized(declining) { declining.remove(r.id) }
            }
        }
    }

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

    // ---------------------------------------------------------------- releases

    /**
     * Refuses a new ride (asking, quoting, locking, accepting) on a release the registry revoked. Rides under way
     * go on, and money can still be withdrawn. The registry is asked now if it was not read yet, so the first
     * screen that can start a ride never runs ahead of it. A release the registry does not name, or a registry
     * that cannot be read, refuses nothing: the RPC must not be able to switch the app off.
     */
    private fun mayStartRides(c: KlippelGoClient) {
        val check = _state.value.release ?: c.releaseCheck().also { r -> _state.update { it.copy(release = r) } }
        if (!check.mayStartRides) throw IllegalStateException(getApplication<Application>().getString(R.string.release_revoked_refused))
    }

    /** The update mirror: the one this build names, or the one next to the chain node, where the map packs are. */
    private fun apkMirror(): String = BuildConfig.APK_MIRROR.ifBlank { mirror() }

    /**
     * Fetches the latest release's APK, checks it against the registry and asks Android's installer to install
     * it. The person confirms in the installer; Android accepts only an APK signed with this app's key.
     */
    fun update() {
        val c = client ?: return
        val latest = _state.value.release?.takeIf { it.updateAvailable }?.latest ?: return
        if (_state.value.update is UpdateState.Downloading) return
        // A ride settles on the escrow it opened on. A release with other code contracts would leave this one
        // where its app cannot finish it, so the update waits until no ride of this account is unfinished.
        if (_state.value.updateMustWait) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            // Checked already and held back for a ride: hand that file to the installer now.
            if ((_state.value.update as? UpdateState.Ready)?.version == latest) {
                updater.installIntent(latest)?.let { app.startActivity(it); return@launch }
            }
            if (!updater.mayInstall()) {
                app.startActivity(updater.allowInstallsIntent())
                return@launch
            }
            _state.update { it.copy(update = UpdateState.Downloading(0, 0)) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    updater.download(apkMirror(), latest, { file -> c.publishedApk(latest, file) }) { done, total ->
                        _state.update { it.copy(update = UpdateState.Downloading(done, total)) }
                    }
                }
            }
            result.fold(
                {
                    _state.update { it.copy(update = UpdateState.Ready(latest)) }
                    // A ride may have started while the APK came down: the checked file is kept, and the
                    // installer opens only once no ride of this account is unfinished (the button again).
                    if (!_state.value.updateMustWait) updater.installIntent(latest)?.let(app::startActivity)
                },
                { e ->
                    val text = when (e) {
                        is ApkRefused -> app.getString(R.string.update_refused)
                        is NoApk -> app.getString(R.string.update_missing)
                        else -> app.getString(R.string.update_failed, message(e))
                    }
                    _state.update { it.copy(update = UpdateState.Failed(text)) }
                },
            )
        }
    }

    /** Takes what an earlier release's escrow owes this account, in each coin it owes. */
    fun withdrawPrevious(escrow: String) = act { c ->
        val left = _state.value.leftOnPrevious.firstOrNull { it.escrow.equals(escrow, ignoreCase = true) } ?: return@act
        left.owed.keys.forEach { coin -> c.withdrawFrom(left.escrow, coin) }
    }

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

    /**
     * Registers the account in its role if it is not yet. Only once it holds reais: its first operation pays the
     * network in them, and creates the account on chain. Gives whether it is registered.
     */
    private fun register(c: KlippelGoClient, roles: Int): Boolean = runCatching {
        if (c.rolesOf() and roles == roles) return@runCatching true
        if (c.coins.all { c.balanceOf(it).signum() == 0 }) return@runCatching false
        c.ensureRegistered(roles)
        true
    }.getOrDefault(false)

    /**
     * The session key gossip is signed with, kept in app-private storage so that a restart does not ask the
     * account's key (and so the person) for a new certificate. It moves no money: see [SessionKey].
     */
    private fun restoreSession(c: KlippelGoClient) {
        val key = prefs.getString("session.key", null) ?: return
        runCatching {
            val cert = SessionCertificate(
                account = prefs.getString("session.account", null)!!,
                session = prefs.getString("session.address", null)!!,
                expiresAt = prefs.getLong("session.expiresAt", 0),
                signature = org.web3j.utils.Numeric.hexStringToByteArray(prefs.getString("session.signature", null)!!),
            )
            c.resumeSession(SessionKey.restore(key, cert))
        }
    }

    private fun saveSession(s: SessionKey) {
        prefs.edit()
            .putString("session.key", org.web3j.utils.Numeric.toHexStringWithPrefixZeroPadded(s.keyPair.privateKey, 64))
            .putString("session.account", s.certificate.account)
            .putString("session.address", s.certificate.session)
            .putLong("session.expiresAt", s.certificate.expiresAt)
            .putString("session.signature", org.web3j.utils.Numeric.toHexString(s.certificate.signature))
            .apply()
    }

    /**
     * The newest block the screen reflects, the rides and accounts whose events would change it, and when the
     * chain was last read in full. Set by each full read ([refresh]).
     */
    @Volatile private var seenBlock: Long? = null
    @Volatile private var watchRides: Set<BigInteger> = emptySet()
    @Volatile private var watchAccounts: Set<String> = emptySet()
    @Volatile private var fullReadAt = 0L

    /**
     * Whether the chain should be read in full now: never read yet, read half a minute ago, or something about
     * this account happened in the blocks since ([KlippelGoClient.changedSince]: one query for the newest block,
     * and one batch of log queries when there is a new one). When nothing did, what the last read said about the
     * ride under way still holds, and the time it is believed from moves to now (A10).
     */
    private fun chainDue(c: KlippelGoClient, m: Matchmaker?): Boolean {
        if (!inForeground && !onDuty(_state.value)) return false
        val seen = seenBlock ?: return true
        val checkedAt = android.os.SystemClock.elapsedRealtime()
        if (checkedAt - fullReadAt > FULL_READ_EVERY_MS) return true
        val (head, changed) = runCatching { c.changedSince(seen, watchRides, watchAccounts) }
            .getOrElse { trace("the change check failed: ${it.message}"); return true }
        if (changed) {
            trace("a change in blocks $seen to $head")
            return true
        }
        synchronized(matchLock) {
            if (client !== c || match !== m) return false
            seenBlock = head
            if (m != null) rideConfirmedAt = checkedAt
        }
        return false
    }

    /**
     * The cells whose requests this phone hears. A driver hears those around them while their position is inside
     * the map pack, and the whole pack's otherwise, or the pack's middle when the pack is large. A rider hears only
     * the cells of their own requests, which the matchmaker follows by itself.
     */
    private fun area(role: Role): Set<String> {
        if (role != Role.DRIVER) return emptySet()
        val b = packBounds ?: return emptySet()
        val here = _state.value.location
        if (here != null && here.latE7 in b.southWest.latE7..b.northEast.latE7 && here.lonE7 in b.southWest.lonE7..b.northEast.lonE7) {
            return Matchmaker.around(here)
        }
        val large = b.northEast.latE7 - b.southWest.latE7 > 4 * Matchmaker.AROUND_E7 || b.northEast.lonE7 - b.southWest.lonE7 > 4 * Matchmaker.AROUND_E7
        if (!large) return Matchmaker.covering(b)
        return Matchmaker.around(Coordinate((b.southWest.latE7 + b.northEast.latE7) / 2, (b.southWest.lonE7 + b.northEast.lonE7) / 2))
    }

    /** What the scans last saved to the phone, so they are written again only when they changed. */
    private var savedScans: String? = null

    private fun scansKey(c: KlippelGoClient) = "scans.${c.address}"

    /** Reads the chain in full, after taking in gossip. */
    private fun refresh(c: KlippelGoClient, m: Matchmaker?, role: Role) {
        refreshGossip(c, m, role)
        refreshChain(c, m, role)
    }

    /** Takes in gossip and shows it. Reads the chain only for the tariff, at most every half minute. */
    private fun refreshGossip(c: KlippelGoClient, m: Matchmaker?, role: Role) {
        // Gossip first and on its own: matching must keep running while the chain node is unreachable.
        runCatching {
            m?.listen(area(role), _state.value.location.takeIf { role == Role.DRIVER })
            m?.tick()
        }
        val open = m?.openRequests().orEmpty()
        val request = if (role == Role.RIDER) open.firstOrNull { it.rider == c.address } else null
        val offers = request?.let { m?.offers(it) }.orEmpty()
        if (BuildConfig.DEBUG) {
            // The screen shows an offer only from a driver the last full read said may accept rides.
            val may = _state.value.drivers.filter { it.canAccept }.mapTo(HashSet()) { it.address }
            val hidden = offers.map { it.driver }.filterNot { it in may }.toSet()
            if (hidden != hiddenOffers) trace("offers hidden, their drivers not among the ${may.size} who may accept: $hidden")
            hiddenOffers = hidden
        }
        // The tariff as the client last read it, which is at most half a minute old; until a first read
        // succeeds, only the rules that need no tariff apply, and a failed read keeps the one already shown.
        val tariff = runCatching { c.tariff }.getOrNull() ?: _state.value.tariff
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
    }

    private fun refreshChain(c: KlippelGoClient, m: Matchmaker?, role: Role) {
        val result = runCatching {
            // The block this read starts at: events after it are looked for by the next check.
            val head = c.blockNumber().toLong()
            val startedAt = android.os.SystemClock.elapsedRealtime()
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
            // Only as far back as the newest few go: a new phone does not read the whole chain first.
            val recent = (if (role == Role.RIDER) c.ridesAsRider(MAX_RIDES) else c.ridesAsDriver(MAX_RIDES)).take(MAX_RIDES)
            val ids = (recent + underWay.map { it.id }).distinct().sortedDescending()
            // One batch of reads; after a reorganisation of the chain an id found earlier can be someone else's.
            val rides = c.rides(ids).filter { if (role == Role.RIDER) it.rider == c.address else it.driver == c.address }
            if (role == Role.DRIVER) declineVoid(c, rides)
            val rated = c.rated(rides.filter { it.status == RideStatus.Completed }.map { it.id })
            val drivers = if (role == Role.RIDER) c.drivers() else emptyList()
            val me = if (role == Role.DRIVER) c.driverInfo() else null
            val canAccept = me?.canAccept == true
            val blockers = if (me != null && !canAccept) c.acceptBlockers() else emptyList()
            if (role == Role.RIDER) {
                // The ride this phone last saw under way, if it has since been cancelled.
                val before = _state.value.rides.firstOrNull { it.status.isActive }
                rides.firstOrNull { it.id == before?.id && it.status == RideStatus.Cancelled }?.let { afterCancelled(m, it) }
            }
            // Not while a ride on the same quote goes on: nothing stops a rider from opening one twice (P12).
            if (role == Role.RIDER) {
                val going = rides.filterNot { it.status.isFinal }.mapTo(HashSet()) { it.quoteHash }
                rides.filter { it.status.isFinal && it.quoteHash !in going }.forEach { m?.forgetRide(it.quoteHash) }
            }
            val rideRoutes = if (role == Role.DRIVER) rideRoutes(c, m, rides) else ownRoutes(c, rides)
            // Rider: is the driver still busy with someone else, and if not, where are they.
            val mine = rides.firstOrNull { it.status.isActive }.takeIf { role == Role.RIDER }
            val driverRides = mine?.let { runCatching { c.underWay(it.driver) }.getOrNull() }
            // A read that failed says nothing: keep what was known, never "nobody ahead".
            val ridesAhead = mine?.let { r -> driverRides?.let { c.ridesAhead(r, it) } ?: _state.value.ridesAhead } ?: 0
            val driverAt = mine?.takeIf { ridesAhead == 0 }?.let { m?.driverPosition(it.quoteHash) }?.let { Coordinate(it.latE7, it.lonE7) }
            // Only for the session this refresh belongs to: one left behind by a change of account must not
            // hand its ride to the next.
            synchronized(matchLock) {
                if (m != null && match === m) {
                    sharingFor = underWay.firstOrNull()
                    fixesFor(role == Role.DRIVER && underWay.any { it.status == RideStatus.Started })
                    rideConfirmedAt = readAt
                    watchingQuote = mine?.takeIf { ridesAhead == 0 }?.quoteHash
                }
            }
            // What would change this screen: the rides on it that can still change, and for a waiting rider the
            // rides their driver is busy with, which come before theirs; and rides opened naming either. The
            // driver taking on another ride before this rider's is accepted shows at the next full read.
            val unfinished = rides.filter { !it.status.isFinal }.map { it.id }.toSet() + underWay.map { it.id } +
                driverRides.orEmpty().map { it.id }
            val accounts = setOfNotNull(c.address, mine?.driver)
            // Only for the session this read belongs to: a read left running by a change of account must not
            // tell the next one that it is up to date.
            synchronized(matchLock) {
                if (client === c && match === m) {
                    watchRides = unfinished
                    watchAccounts = accounts
                    seenBlock = head
                    fullReadAt = startedAt
                }
            }
            c.keepScansOf(accounts)
            c.savedScans().takeIf { it != savedScans }?.let {
                prefs.edit().putString(scansKey(c), it).apply()
                savedScans = it
            }
            val links = if (role == Role.DRIVER) links(rides, rideRoutes) else emptyList()
            // Read with every full read, which a release event in the change check also starts.
            val release = c.releaseCheck()
            val leftOnPrevious = c.leftOnPreviousReleases()
            _state.update {
                // A read left running by a change of account does not put that account's rides on the new screen.
                if (it.role != role || client !== c) it else it.copy(
                    connected = true, balances = balances, fees = fees, bonds = bonds, rides = rides, rated = rated,
                    taxBps = taxBps, infrastructure = infrastructure,
                    // Only for rides this phone paid for from their first transaction: a part is not shown as the whole.
                    infrastructureSpent = rides.mapNotNull { r -> c.gasSpent(r.id)?.let { wei -> r.id to c.infrastructureCost(wei) } }.toMap(),
                    drivers = drivers, canAccept = canAccept, blockers = blockers, me = me,
                    rideRoutes = rideRoutes, links = links, ridesAhead = ridesAhead, driverAt = driverAt,
                    release = release, leftOnPrevious = leftOnPrevious,
                )
            }
            // A ride that just ended leaves the rider where it took them.
            pinWhereThePhoneIs()
        }
        trace(result.fold({ "full read at block $seenBlock: ${_state.value.rides.size} rides, ${_state.value.drivers.count { it.canAccept }} drivers who may accept" }, { "full read failed: ${it.message}" }))
        if (result.isFailure) {
            sharingFor = null
            watchingQuote = null
            seenBlock = null
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

    /** Drivers of the offers last hidden from the rider, so each change is traced once. */
    private var hiddenOffers: Set<String> = emptySet()

    /**
     * A line for finding out why something did not show, in debug builds only, under the tag the end-to-end
     * tests collect when one fails. Only what gossip and the chain show anyone goes here (A12).
     */
    private fun trace(what: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(TRACE_TAG, what)
    }

    override fun onCleared() {
        OnDutyService.keep(getApplication(), false)
        stopLocation()
        leaveGossip()
        pack?.close()
    }

    companion object {
        private const val MAX_RIDES = 10

        /** The log tag of [trace]. */
        const val TRACE_TAG = "KlippelGo"

        /** How often the chain is read in full even when no event said anything changed. */
        private const val FULL_READ_EVERY_MS = 30_000L

        /** Void quotes kept: far more than a driver sends in the life of a quote. */
        private const val MAX_VOID_QUOTES = 200

        /** What the preferences call the account owned by this phone's own key, as against a seeded test account. */
        private const val DEVICE_ACCOUNT = "device"

        /** How often the network's gas price is asked for again. */
        private const val GAS_PRICE_EVERY_MS = 30_000L

        /** How long a driver who gave a ride up is not shown that rider's requests. */
        private const val SKIP_AFTER_REFUSAL_MS = 3 * 60_000L

        /**
         * The addresses this build was pinned to (`deployments/<network>.json`, bundled at build time). Only a
         * build for the local testnet falls back to a fresh testnet's: any other is built with its file or not at
         * all, so it can never run against addresses it was not released with.
         */
        private fun loadDeployment(app: Application): Deployment = runCatching {
            app.assets.open("deployment.json").bufferedReader().use { Deployment.fromJson(it.readText()) }
        }.getOrElse { e ->
            if (BuildConfig.LOCAL_NETWORK) Deployment.LOCAL_DEFAULT
            else throw IllegalStateException("this ${BuildConfig.NETWORK} build carries no deployment", e)
        }
    }
}
