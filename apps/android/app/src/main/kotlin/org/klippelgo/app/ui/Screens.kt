package org.klippelgo.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.klippelgo.app.AppViewModel
import org.klippelgo.app.DEFAULT_RPC
import org.klippelgo.app.R
import org.klippelgo.app.Role
import org.klippelgo.app.UiState
import org.klippelgo.app.setAppLanguage
import org.klippelgo.chain.Coin
import org.klippelgo.chain.Offer
import org.klippelgo.chain.Ride
import org.klippelgo.chain.RideRequest
import org.klippelgo.chain.RideStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

@Composable
fun KlippelGoApp(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            when (state.role) {
                null -> RolePicker(state.rpcUrl, onPick = vm::connect)
                Role.RIDER -> RiderHome(state, vm)
                Role.DRIVER -> DriverHome(state, vm)
            }
        }
        state.error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.dismiss)) } },
                text = { Text(msg) },
            )
        }
    }
}

// ------------------------------------------------------------------- role picker

@Composable
private fun RolePicker(initialRpc: String, onPick: (Role, String) -> Unit) {
    var rpc by rememberSaveable { mutableStateOf(initialRpc.ifBlank { DEFAULT_RPC }) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("KlippelGo", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.local_testnet), color = MaterialTheme.colorScheme.primary)
        LanguagePicker()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.choose_role), style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onPick(Role.RIDER, rpc) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.role_rider)) }
        OutlinedButton(onClick = { onPick(Role.DRIVER, rpc) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.role_driver))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = rpc,
            onValueChange = { rpc = it },
            label = { Text(stringResource(R.string.rpc_label)) },
            supportingText = { Text(stringResource(R.string.rpc_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.dev_warning), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LanguagePicker() {
    val current = AppCompatDelegate.getApplicationLocales()[0]?.language ?: "pt"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.language))
        listOf("pt-BR" to "PT", "en" to "EN", "es" to "ES").forEach { (tag, label) ->
            FilterChip(
                selected = current == tag.substringBefore('-'),
                onClick = { setAppLanguage(tag) },
                label = { Text(label) },
            )
        }
    }
}

// ------------------------------------------------------------------- shared pieces

@Composable
private fun Header(state: UiState, vm: AppViewModel) {
    val roleLabel = stringResource(if (state.role == Role.RIDER) R.string.role_rider_short else R.string.role_driver_short)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KlippelGo · $roleLabel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = vm::switchRole) { Text(stringResource(R.string.switch_role)) }
        }
        Text(shortAddress(state.address), style = MaterialTheme.typography.bodySmall)
        val net = when (state.connected) {
            true -> stringResource(R.string.connected, state.rpcUrl)
            false -> stringResource(R.string.not_connected, state.rpcUrl)
            null -> ""
        }
        Text(
            net,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.connected == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )
        if (state.gossipConnected != null) {
            Text(
                stringResource(if (state.gossipConnected) R.string.gossip_connected else R.string.gossip_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.gossipConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.balances) + ":", fontWeight = FontWeight.SemiBold)
            state.balances.forEach { (coin, units) -> Text("${brl(coin, units)} ${coin.symbol}") }
        }
        if (state.busy) Text(stringResource(R.string.sending), style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun statusLabel(status: RideStatus): String = stringResource(
    when (status) {
        RideStatus.Open -> R.string.status_open
        RideStatus.Accepted -> R.string.status_accepted
        RideStatus.Started -> R.string.status_started
        RideStatus.Completed -> R.string.status_completed
        RideStatus.Cancelled, RideStatus.None -> R.string.status_cancelled
        RideStatus.Disputed -> R.string.status_disputed
        RideStatus.Resolved -> R.string.status_resolved
    },
)

@Composable
private fun RideCard(
    ride: Ride,
    coin: Coin,
    counterparty: String,
    highlighted: Boolean,
    actions: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(stringResource(R.string.ride_number, ride.id.toString()), fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(statusLabel(ride.status), color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(R.string.fare, "${brl(coin, ride.fare)} ${coin.symbol}"))
            Text(stringResource(R.string.driver_receives, brl(coin, ride.driverPayout)))
            Text(stringResource(R.string.institute_receives, brl(coin, ride.fee)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.counterparty, shortAddress(counterparty)), style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}

@Composable
private fun Stars(label: String, onRate: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row {
            (1..5).forEach { n -> TextButton(onClick = { onRate(n) }) { Text("★".repeat(n)) } }
        }
    }
}

// ------------------------------------------------------------------- rider

@Composable
private fun RiderHome(state: UiState, vm: AppViewModel) {
    Header(state, vm)
    val active = state.rides.firstOrNull { it.status.isActive }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (active != null) {
            item {
                val coin = coinOf(state, active.token)
                RideCard(active, coin, active.driver, highlighted = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active.status == RideStatus.Started) {
                            Button(onClick = { vm.complete(active.id) }, enabled = !state.busy) {
                                Text(stringResource(R.string.action_complete))
                            }
                        }
                        if (active.status == RideStatus.Open || active.status == RideStatus.Accepted) {
                            OutlinedButton(onClick = { vm.cancel(active.id) }, enabled = !state.busy) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
            }
        } else if (state.request != null) {
            item { RiderRequest(state, vm, state.request) }
        } else {
            item { RequestRideForm(state, vm) }
        }

        item { SectionTitle(stringResource(R.string.history)) }
        val past = state.rides.filter { !it.status.isActive }
        if (past.isEmpty()) item { Text(stringResource(R.string.no_rides)) }
        items(past, key = { it.id.toString() }) { ride ->
            RideCard(ride, coinOf(state, ride.token), ride.driver, highlighted = false) {
                if (ride.status == RideStatus.Completed) {
                    if (ride.id in state.rated) {
                        Text(stringResource(R.string.rated), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Stars(stringResource(R.string.rate_driver)) { vm.rate(ride.id, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRideForm(state: UiState, vm: AppViewModel) {
    var fareText by rememberSaveable { mutableStateOf("23,40") }
    var coinSymbol by rememberSaveable { mutableStateOf("BRZ") }
    val coins = state.balances.keys.toList()
    val coin = coins.firstOrNull { it.symbol == coinSymbol } ?: coins.firstOrNull()
    val fare = parseFare(fareText)
    val fee = coin?.let { state.fees[it] }
    val fareUnits = if (coin != null && fare != null) runCatching { coin.toBaseUnits(fare) }.getOrNull() else null
    val valid = fareUnits != null && fee != null && fareUnits > fee

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.request_ride))
        OutlinedTextField(
            value = fareText,
            onValueChange = { fareText = it },
            label = { Text(stringResource(R.string.max_fare_label)) },
            isError = fareText.isNotBlank() && !valid,
            supportingText = { Text(stringResource(if (valid) R.string.max_fare_hint else R.string.invalid_fare)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.coin_label))
            coins.forEach { c ->
                FilterChip(selected = c == coin, onClick = { coinSymbol = c.symbol }, label = { Text(c.symbol) })
            }
        }
        Button(
            onClick = { if (coin != null && fare != null) vm.requestRide(coin, fare) },
            enabled = valid && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.request_ride))
        }
    }
}

/** The rider's open request and the drivers' quotes for it, cheapest first, then best rated. */
@Composable
private fun RiderRequest(state: UiState, vm: AppViewModel, request: RideRequest) {
    val coin = coinOf(state, request.token)
    val drivers = state.drivers.filter { it.canAccept }.associateBy { it.address }
    val offers = state.offers.filter { it.driver in drivers }
        .sortedWith(compareBy<Offer> { it.fare }.thenByDescending { drivers.getValue(it.driver).score })

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.request_sent), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.up_to, "${brl(coin, request.maxFare)} ${coin.symbol}"))
                OutlinedButton(onClick = { vm.cancelRequest(request) }, enabled = !state.busy) {
                    Text(stringResource(R.string.action_cancel_request))
                }
            }
        }

        SectionTitle(stringResource(R.string.offers_title))
        if (offers.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.waiting_offers))
            }
        }
        offers.forEach { offer ->
            val d = drivers.getValue(offer.driver)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(brl(coin, offer.fare), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val score = if (d.ratings == 0) stringResource(R.string.driver_new) else "★ %.2f".format(d.score / 100.0)
                    Text(
                        "${shortAddress(d.address)} · $score · " + stringResource(R.string.driver_rides, d.completedRides),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { vm.acceptOffer(request, offer) }, enabled = !state.busy) {
                    Text(stringResource(R.string.action_take_offer))
                }
            }
        }
    }
}

// ------------------------------------------------------------------- driver

@Composable
private fun DriverHome(state: UiState, vm: AppViewModel) {
    Header(state, vm)
    val active = state.rides.filter { it.status.isActive }
    val past = state.rides.filter { !it.status.isActive }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(if (state.canAccept) R.string.can_accept else R.string.cannot_accept),
                color = if (state.canAccept) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        item { SectionTitle(stringResource(R.string.requests_title)) }
        if (state.requests.isEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.waiting_rides))
                }
            }
        }
        items(state.requests, key = { "request-${it.id}" }) { request -> DriverQuote(state, vm, request) }
        if (active.isNotEmpty()) item { SectionTitle(stringResource(R.string.incoming_title)) }
        items(active, key = { it.id.toString() }) { ride ->
            RideCard(ride, coinOf(state, ride.token), ride.rider, highlighted = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (ride.status) {
                        RideStatus.Open -> Button(onClick = { vm.accept(ride.id) }, enabled = !state.busy && state.canAccept) {
                            Text(stringResource(R.string.action_accept))
                        }
                        RideStatus.Accepted -> {
                            Button(onClick = { vm.start(ride.id) }, enabled = !state.busy) { Text(stringResource(R.string.action_start)) }
                            OutlinedButton(onClick = { vm.cancel(ride.id) }, enabled = !state.busy) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.history)) }
        if (past.isEmpty()) item { Text(stringResource(R.string.no_rides)) }
        items(past, key = { it.id.toString() }) { ride ->
            RideCard(ride, coinOf(state, ride.token), ride.rider, highlighted = false) {
                if (ride.status == RideStatus.Completed) {
                    if (ride.id in state.rated) {
                        Text(stringResource(R.string.rated), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Stars(stringResource(R.string.rate_rider)) { vm.rate(ride.id, it) }
                    }
                }
            }
        }
    }
}

/** A rider's request, with the price field the driver quotes from. It starts at the rider's max fare. */
@Composable
private fun DriverQuote(state: UiState, vm: AppViewModel, request: RideRequest) {
    val coin = coinOf(state, request.token)
    val mine = state.myOffers[request.id]
    var priceText by rememberSaveable { mutableStateOf(fareInput(coin, mine?.fare ?: request.maxFare)) }
    val fee = state.fees[coin]
    val price = parseFare(priceText)?.let { runCatching { coin.toBaseUnits(it) }.getOrNull() }
    val valid = price != null && fee != null && price > fee && price <= request.maxFare

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.up_to, "${brl(coin, request.maxFare)} ${coin.symbol}"), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.rider_label, shortAddress(request.rider)), style = MaterialTheme.typography.bodySmall)
            if (mine != null) {
                Text(stringResource(R.string.your_offer, brl(coin, mine.fare)), color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = priceText,
                onValueChange = { priceText = it },
                label = { Text(stringResource(R.string.your_price_label)) },
                isError = priceText.isNotBlank() && !valid,
                supportingText = {
                    if (valid) {
                        Text(stringResource(R.string.you_receive, brl(coin, price!! - fee!!)))
                    } else {
                        Text(stringResource(R.string.invalid_offer))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { parseFare(priceText)?.let { vm.sendOffer(request, it) } },
                enabled = valid && price != mine?.fare && state.canAccept && !state.busy,
            ) {
                Text(stringResource(if (mine == null) R.string.action_send_offer else R.string.action_update_offer))
            }
        }
    }
}

// ------------------------------------------------------------------- formatting

private fun coinOf(state: UiState, token: String): Coin =
    state.balances.keys.firstOrNull { it.address.equals(token, ignoreCase = true) } ?: Coin("?", token, 18)

private val brlFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))

/** Always formats as Brazilian reais (R$ 23,40), whatever the app language. */
fun brl(coin: Coin, units: BigInteger): String =
    brlFormat.format(coin.toReais(units).setScale(2, RoundingMode.DOWN))

/** An amount as text for a price field: "23,40". */
fun fareInput(coin: Coin, units: BigInteger): String =
    coin.toReais(units).setScale(2, RoundingMode.DOWN).toPlainString().replace('.', ',')

/** Accepts "23,40" or "23.40". */
fun parseFare(text: String): BigDecimal? {
    val t = text.replace("R$", "").trim()
    val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
    return normalized.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }
}

fun shortAddress(a: String): String = if (a.length > 12) a.take(6) + "…" + a.takeLast(4) else a
