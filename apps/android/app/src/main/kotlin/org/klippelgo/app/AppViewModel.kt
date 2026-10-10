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
import org.klippelgo.chain.Coin
import org.klippelgo.chain.Deployment
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.DriverInfo
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.chain.Ride
import org.web3j.crypto.Hash
import java.math.BigDecimal
import java.math.BigInteger

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
    val fees: Map<Coin, BigInteger> = emptyMap(),
)

const val DEFAULT_RPC = "http://10.0.2.2:8545"

/**
 * Holds one account's view of the chain and refreshes it every two seconds. Rider and driver run this same code
 * on different emulators; everything one does reaches the other only through the contracts.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("klippelgo", Context.MODE_PRIVATE)
    private val deployment: Deployment = loadDeployment(app)

    private val _state = MutableStateFlow(
        UiState(
            role = prefs.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() },
            rpcUrl = prefs.getString("rpc", DEFAULT_RPC) ?: DEFAULT_RPC,
        ),
    )
    val state: StateFlow<UiState> = _state

    private var client: KlippelGoClient? = null
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
        _state.value = UiState(role = role, rpcUrl = rpcUrl, address = c.address)
        poller?.cancel()
        poller = viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.ensureRegistered(if (role == Role.RIDER) KlippelGoClient.ROLE_RIDER else KlippelGoClient.ROLE_DRIVER) }
            while (isActive) {
                refresh(c, role)
                delay(2_000)
            }
        }
    }

    fun switchRole() {
        poller?.cancel()
        client = null
        prefs.edit().remove("role").apply()
        _state.update { UiState(rpcUrl = it.rpcUrl) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- rider actions

    fun requestRide(driver: String, coin: Coin, fareReais: BigDecimal) = act {
        it.openRide(driver, coin, coin.toBaseUnits(fareReais), Hash.sha3("quote:$driver:$fareReais:${System.nanoTime()}".toByteArray()))
    }

    fun complete(id: BigInteger) = act { it.complete(id) }

    // ---------------------------------------------------------------- driver actions

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
            withContext(Dispatchers.IO) { refresh(c, role) }
        }
    }

    private fun refresh(c: KlippelGoClient, role: Role) {
        val result = runCatching {
            c.chainId()
            val balances = c.coins.associateWith { c.balanceOf(it) }
            val fees = c.coins.associateWith { c.tokenConfig(it).first }
            val ids = (if (role == Role.RIDER) c.ridesAsRider() else c.ridesAsDriver()).take(MAX_RIDES)
            val rides = ids.map { c.ride(it) }
            val rated = rides.filter { it.status == org.klippelgo.chain.RideStatus.Completed && c.hasRated(it.id) }
                .map { it.id }.toSet()
            val drivers = if (role == Role.RIDER) c.drivers() else emptyList()
            val canAccept = role == Role.DRIVER && c.canAcceptRides()
            _state.update {
                if (it.role != role) it else it.copy(
                    connected = true, balances = balances, fees = fees, rides = rides, rated = rated,
                    drivers = drivers, canAccept = canAccept,
                )
            }
        }
        if (result.isFailure) _state.update { if (it.role != role) it else it.copy(connected = false) }
    }

    private fun message(t: Throwable): String = t.message ?: t.javaClass.simpleName

    override fun onCleared() {
        poller?.cancel()
    }

    companion object {
        private const val MAX_RIDES = 10

        /** Prefers the addresses from the last deploy (bundled at build time), else a fresh testnet's. */
        private fun loadDeployment(app: Application): Deployment = runCatching {
            app.assets.open("local.json").bufferedReader().use { Deployment.fromJson(it.readText()) }
        }.getOrDefault(Deployment.LOCAL_DEFAULT)
    }
}
