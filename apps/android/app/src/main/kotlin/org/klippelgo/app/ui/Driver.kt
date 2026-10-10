package org.klippelgo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.klippelgo.app.AppViewModel
import org.klippelgo.app.R
import org.klippelgo.app.Role
import org.klippelgo.app.UiState
import org.klippelgo.chain.Blocker
import org.klippelgo.chain.Ride
import org.klippelgo.chain.RideRequest
import org.klippelgo.chain.RideStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Picks the driver's screen: a ride in progress first, then a rating owed, then requests while online. */
@Composable
fun DriverApp(state: UiState, vm: AppViewModel) {
    val active = state.rides.firstOrNull { it.status.isActive }
    var online by rememberSaveable { mutableStateOf(false) }
    var passed by remember { mutableStateOf(emptySet<BigInteger>()) }
    var skipped by remember { mutableStateOf(emptySet<BigInteger>()) }
    val toRate = state.rides.firstOrNull()
        ?.takeIf { it.status == RideStatus.Completed && it.id !in state.rated && it.id !in skipped }
    val request = state.requests.firstOrNull { it.id !in passed }

    when {
        active != null -> DriverRide(state, active, vm)
        toRate != null -> RideEndScreen(
            state, toRate,
            onBack = null,
            onConfirm = { vm.rate(toRate.id, it) },
            onSkip = { skipped = skipped + toRate.id },
        )
        online && request != null -> {
            val pass = { passed = passed + request.id }
            BackHandler(onBack = pass)
            IncomingRequest(state, request, onPass = pass, onSend = { vm.sendOffer(request, it) })
        }
        else -> {
            BackHandler(enabled = online) { online = false }
            GoOnline(state, online, onToggle = { online = !online }, onRiderMode = { vm.connect(Role.RIDER, state.rpcUrl) })
        }
    }
}

@Composable
private fun OnlinePill(online: Boolean) {
    Pill {
        Dot(if (online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
        Text(stringResource(if (online) R.string.online else R.string.offline), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GoOnline(state: UiState, online: Boolean, onToggle: () -> Unit, onRiderMode: () -> Unit) {
    val button = 132.dp
    var sheetHeight by remember { mutableIntStateOf(0) }
    val lift = with(LocalDensity.current) { (button / 2).roundToPx() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.fillMaxSize()) {
            MapSurface(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OnlinePill(online)
                        Spacer(Modifier.weight(1f))
                        Pill(onClick = onRiderMode) {
                            Text(stringResource(R.string.rider_mode), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    NetworkNotice(state)
                }
            }
            Sheet(Modifier.onSizeChanged { sheetHeight = it.height }) {
                Spacer(Modifier.height(button / 2 - 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat(stringResource(R.string.stat_today), earnedToday(state))
                    Stat(stringResource(R.string.stat_score), scoreLabel(state.me))
                    Stat(stringResource(R.string.stat_rides), (state.me?.completedRides ?: 0).toString())
                }
                when {
                    state.connected == true && !state.canAccept -> BlockedNotice(state)
                    online -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Muted(stringResource(R.string.waiting_rides), size = 13.sp)
                    }
                    else -> Muted(stringResource(R.string.go_online_body), size = 13.sp)
                }
            }
        }
        // The round button straddles the top edge of the sheet.
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = if (online) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            border = BorderStroke(6.dp, if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
            shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, lift - sheetHeight) }.size(button),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (online) R.string.go_offline else R.string.go_online),
                    Modifier.padding(horizontal = 18.dp),
                    fontSize = 19.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Why the policy will not let this driver accept rides: only the requirements still missing. */
@Composable
private fun BlockedNotice(state: UiState) {
    val lines = state.blockers.map {
        when (it) {
            is Blocker.Score -> stringResource(R.string.blocked_score, score(it.have), score(it.need))
            is Blocker.Stake -> stringResource(R.string.blocked_stake, brl(it.have), brl(it.need))
            is Blocker.Credential -> stringResource(if (it.cnh) R.string.blocked_cnh else R.string.blocked_credential)
            Blocker.Role -> stringResource(R.string.blocked_role)
            Blocker.AccountAge -> stringResource(R.string.blocked_age)
            Blocker.Disabled -> stringResource(R.string.cannot_accept)
        }
    }.ifEmpty { listOf(stringResource(R.string.cannot_accept)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Muted(it, size = 13.sp, color = MaterialTheme.colorScheme.error) }
    }
}

private fun score(x100: Int): String = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", x100 / 100.0)

@Composable
private fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Muted(label, size = 12.sp)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** What today's completed rides paid this driver, among the latest rides the app loads. */
private fun earnedToday(state: UiState): String {
    val today = LocalDate.now()
    val total = state.rides
        .filter { it.status == RideStatus.Completed }
        .filter { Instant.ofEpochSecond(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }
        .fold(BigDecimal.ZERO) { sum, ride -> sum + coinOf(state, ride.token).toReais(ride.driverPayout) }
    return brl(total)
}

/** A rider's request. The price starts at the rider's max and moves in steps of R$ 0,50. */
@Composable
private fun IncomingRequest(state: UiState, request: RideRequest, onPass: () -> Unit, onSend: (BigDecimal) -> Unit) {
    val coin = coinOf(state, request.token)
    val mine = state.myOffers[request.id]
    var price by rememberSaveable(request.id.toString()) { mutableStateOf(mine?.fare ?: request.maxFare) }
    val fee = state.fees[coin]
    val step = coin.toBaseUnits(BigDecimal("0.50"))
    val valid = fee != null && price > fee && price <= request.maxFare

    MapScreen(
        map = {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnlinePill(online = true)
                NetworkNotice(state)
            }
        },
    ) {
        Text(stringResource(R.string.new_request), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Column {
            KeyValue(stringResource(R.string.role_rider_short), shortAddress(request.rider), strong = true)
            KeyValue(stringResource(R.string.pays_at_most), brl(coin, request.maxFare), divider = false, strong = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepButton("−", stringResource(R.string.lower_offer), enabled = fee != null && price - step > fee) { price -= step }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(brl(coin, price), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (fee != null) {
                    Muted(stringResource(R.string.you_receive, brl(coin, price - fee), brl(coin, fee)), size = 12.sp, align = TextAlign.Center)
                }
                if (mine != null) {
                    Text(stringResource(R.string.your_offer, brl(coin, mine.fare)), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
            StepButton("+", stringResource(R.string.raise_offer), enabled = price < request.maxFare) {
                price = (price + step).min(request.maxFare)
            }
        }
        if (state.connected == true && !state.canAccept) BlockedNotice(state)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.pass), onPass, Modifier.weight(1f))
            PrimaryButton(
                stringResource(if (mine == null) R.string.action_send_offer else R.string.action_update_offer),
                onClick = { onSend(coin.toReais(price)) },
                modifier = Modifier.weight(2f),
                enabled = valid && price != mine?.fare && state.canAccept,
                busy = state.busy,
            )
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(52.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 24.sp) }
    }
}

/**
 * The ride from the rider locking the fare until the rider confirms arrival. The wireframe starts the ride from a
 * QR code and Bluetooth; until that exists the driver starts it with a button.
 */
@Composable
private fun DriverRide(state: UiState, ride: Ride, vm: AppViewModel) {
    val coin = coinOf(state, ride.token)
    val bond = brl(coin, ride.driverBond)
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(KIcons.Lock, contentDescription = null)
                Column {
                    Text(stringResource(R.string.guaranteed, brl(coin, ride.driverPayout)), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.paid_at_end), fontSize = 13.sp)
                }
            }
        }
        NetworkNotice(state, Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp))
        KCard(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (ride.status) {
                        RideStatus.Open -> stringResource(R.string.driver_open_title)
                        RideStatus.Accepted -> stringResource(R.string.pickup_title)
                        else -> statusLabel(ride.status)
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(shortAddress(ride.rider), fontFamily = PlexMono, fontSize = 15.sp)
                Muted(stringResource(R.string.ride_number, ride.id.toString()) + " · " + brl(coin, ride.fare), size = 13.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (ride.status) {
                RideStatus.Open -> {
                    PrimaryButton(stringResource(R.string.action_accept), { vm.accept(ride.id) }, enabled = state.canAccept, busy = state.busy)
                    Muted(stringResource(R.string.accept_hint, bond), size = 12.sp, align = TextAlign.Center)
                }
                RideStatus.Accepted -> {
                    PrimaryButton(stringResource(R.string.action_start), { vm.start(ride.id) }, busy = state.busy)
                    SecondaryButton(stringResource(R.string.cancel_ride), { vm.cancel(ride.id) }, Modifier.fillMaxWidth(), enabled = !state.busy)
                    Muted(stringResource(R.string.driver_cancel_hint, bond), size = 12.sp, align = TextAlign.Center)
                }
                RideStatus.Started -> Muted(stringResource(R.string.driver_trip_hint), size = 13.sp, align = TextAlign.Center)
                else -> {}
            }
        }
    }
}
