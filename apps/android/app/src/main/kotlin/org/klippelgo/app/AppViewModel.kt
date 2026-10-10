package org.klippelgo.app

import android.app.Application
import android.content.Context
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
import org.klippelgo.chain.RideRequest
import org.klippelgo.core.GossipConfig
import org.klippelgo.core.GossipNode
import org.web3j.crypto.Hash
import java.math.BigDecimal
import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

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

    private var client: KlippelGoClient? = null
    @Volatile private var match: Matchmaker? = null
    private val matchLock = Any()
    private var session = 0
    private var poller: Job? = null

    init {
        _state.value.role?.let { connect(it, _state.value.rpcUrl) }
    }

    /** Pick the role for this device. Each role uses its own seeded test account. */
    fun connect(role: Role, rpcUrl: String) {
        prefs.edit().putString("role", role.name).putString("rpc", rpcUrl).apply()
        val key = if (role == Role.RIDER) DevAccount.RIDER else DevAccount.DRIVER
        val c = KlippelGoClient(rpcUrl.trim(), key.privateKey, deployment)
        client = c
        _state.value = UiState(role = role, rpcUrl = rpcUrl, address = c.address, name = _state.value.name)
        leaveGossip()
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
        leaveGossip()
        client = null
        prefs.edit().remove("role").apply()
        _state.update { UiState(rpcUrl = it.rpcUrl, name = it.name) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- rider actions

    /** Ask nearby drivers for quotes up to `maxFareReais`. */
    fun requestRide(coin: Coin, maxFareReais: BigDecimal) = act { match?.requestRide(coin, coin.toBaseUnits(maxFareReais)) }

    fun cancelRequest(request: RideRequest) = act { match?.closeRequest(request) }

    /** Take a driver's quote: its fare is what gets locked in the escrow. */
    fun acceptOffer(request: RideRequest, offer: Offer) = act { match?.acceptOffer(request, offer) }

    /** Confirms arrival, which pays the driver, and rates them when `stars` is 1 to 5. */
    fun complete(id: BigInteger, stars: Int = 0) = act {
        it.complete(id)
        if (stars in 1..5) it.rate(id, stars)
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

    // ---------------------------------------------------------------- both

    fun cancel(id: BigInteger) = act { it.cancel(id) }

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
            val rated = rides.filter { it.status == org.klippelgo.chain.RideStatus.Completed && c.hasRated(it.id) }
                .map { it.id }.toSet()
            val drivers = if (role == Role.RIDER) c.drivers() else emptyList()
            val me = if (role == Role.DRIVER) c.driverInfo() else null
            val canAccept = me?.canAccept == true
            val blockers = if (me != null && !canAccept) c.acceptBlockers() else emptyList()
            _state.update {
                if (it.role != role) it else it.copy(
                    connected = true, balances = balances, fees = fees, bonds = bonds, rides = rides, rated = rated,
                    drivers = drivers, canAccept = canAccept, blockers = blockers, me = me,
                )
            }
        }
        if (result.isFailure) _state.update { if (it.role != role) it else it.copy(connected = false) }
    }

    private fun message(t: Throwable): String = t.message ?: t.javaClass.simpleName

    override fun onCleared() {
        leaveGossip()
    }

    companion object {
        private const val MAX_RIDES = 10

        /** Prefers the addresses from the last deploy (bundled at build time), else a fresh testnet's. */
        private fun loadDeployment(app: Application): Deployment = runCatching {
            app.assets.open("local.json").bufferedReader().use { Deployment.fromJson(it.readText()) }
        }.getOrDefault(Deployment.LOCAL_DEFAULT)
    }
}
