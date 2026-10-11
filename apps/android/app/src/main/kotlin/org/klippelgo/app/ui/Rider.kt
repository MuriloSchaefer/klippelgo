package org.klippelgo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.klippelgo.app.AppViewModel
import org.klippelgo.app.MapState
import org.klippelgo.app.R
import org.klippelgo.app.Role
import org.klippelgo.app.UiState
import org.klippelgo.app.setAppLanguage
import org.klippelgo.chain.Coin
import org.klippelgo.chain.Offer
import org.klippelgo.chain.Ride
import org.klippelgo.chain.RideRequest
import org.klippelgo.chain.RideStatus
import org.klippelgo.chain.Tariff
import org.klippelgo.core.coarseRoute
import org.klippelgo.core.Coordinate
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.roundToInt

private enum class Tab { RIDE, WALLET, RULES, PROFILE }

/** Picks the rider's screen from where the ride stands: the chain and the gossip network decide, not a back stack. */
@Composable
fun RiderApp(state: UiState, vm: AppViewModel) {
    val active = state.rides.firstOrNull { it.status.isActive }
    var tab by rememberSaveable { mutableStateOf(Tab.RIDE) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var arrived by rememberSaveable { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(emptySet<BigInteger>()) }
    // Only the latest ride asks for a rating on its own screen. Older ones can be rated from the wallet.
    val toRate = state.rides.firstOrNull()
        ?.takeIf { it.status == RideStatus.Completed && it.id !in state.rated && it.id !in skipped }

    LaunchedEffect(active?.id) {
        if (active != null) {
            asking = false
            picked = null
        } else {
            arrived = false
        }
    }


    when {
        state.showFunding -> WalletScreen(state, vm, onboarding = true, onDone = vm::fundingDone)
        active != null && active.status == RideStatus.Started && arrived -> {
            BackHandler { arrived = false }
            RideEndScreen(
                state, active,
                onBack = { arrived = false },
                onConfirm = { stars ->
                    if (stars == 0) skipped = skipped + active.id
                    vm.complete(active.id, stars)
                },
                onSkip = {},
            )
        }
        active != null && active.status == RideStatus.Started -> OnTrip(state, active, onArrived = { arrived = true })
        active != null -> OnTheWay(state, active, onCancel = { vm.cancel(active.id) })
        toRate != null -> RideEndScreen(
            state, toRate,
            onBack = null,
            onConfirm = { vm.rate(toRate.id, it) },
            onSkip = { skipped = skipped + toRate.id },
        )
        asking || state.request != null -> {
            val request = state.request
            val offer = request?.let { state.offers.firstOrNull { it.driver == picked } }
            if (request != null && offer != null) {
                BackHandler { picked = null }
                ConfirmFare(state, request, offer, onBack = { picked = null }, onConfirm = { vm.acceptOffer(request, offer) })
            } else {
                val leave = {
                    request?.let(vm::cancelRequest)
                    vm.clearDestination()
                    asking = false
                }
                BackHandler(onBack = leave)
                Offers(state, vm, onBack = leave, onPick = { picked = it.driver })
            }
        }
        else -> {
            BackHandler(enabled = tab != Tab.RIDE) { tab = Tab.RIDE }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.RIDE -> WhereTo(state, vm, onAsk = { asking = true }, onProfile = { tab = Tab.PROFILE })
                        Tab.WALLET -> WalletScreen(state, vm, onboarding = false)
                        Tab.RULES -> Rules(state)
                        Tab.PROFILE -> Profile(state, vm)
                    }
                }
                TabBar(tab, onTab = { tab = it })
            }
        }
    }
}

/** A map with a sheet under it. The sheet scrolls if it ever outgrows the screen. */
@Composable
fun MapScreen(scene: MapScene? = null, map: @Composable BoxScope.() -> Unit, sheet: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        MapSurface(Modifier.weight(1f).fillMaxWidth(), scene, map)
        Sheet(content = sheet)
    }
}

// ------------------------------------------------------------------- home and tabs

@Composable
private fun WhereTo(state: UiState, vm: AppViewModel, onAsk: () -> Unit, onProfile: () -> Unit) {
    MapScreen(
        scene = sceneOf(state, onCentre = vm::setPickup),
        map = {
            // The pin stays in the middle and the map moves under it: where it points is the pickup.
            Box(
                Modifier.align(Alignment.Center).size(40.dp)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Dot(MaterialTheme.colorScheme.tertiary, 10.dp)
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill { Text(totalBalance(state), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = onProfile,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(KIcons.Person, contentDescription = stringResource(R.string.tab_profile), Modifier.size(20.dp))
                        }
                    }
                }
                NetworkNotice(state)
            }
        },
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        if (state.map is MapState.Ready) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FieldLabel(stringResource(R.string.pickup))
                Text(state.pickupName ?: stringResource(R.string.pickup_pin), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Muted(stringResource(R.string.pickup_pin_hint), size = 12.sp)
            }
        } else {
            MapNotice(state, vm::downloadMap)
        }
        Surface(
            onClick = onAsk,
            enabled = state.map is MapState.Ready,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(KIcons.Search, contentDescription = null, Modifier.size(20.dp))
                Text(stringResource(R.string.where_to), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The rider's map when the phone has a map pack; `null` leaves the grey grid. */
@Composable
fun sceneOf(state: UiState, underWay: Boolean = false, onCentre: ((Coordinate) -> Unit)? = null): MapScene? {
    val map = state.map as? MapState.Ready ?: return null
    // The route just measured, or after a restart the one saved with the ride under way.
    val active = state.rides.firstOrNull { it.status.isActive }
    val route = state.route?.line ?: active?.let { state.rideRoutes[it.id]?.line }.orEmpty()
    return MapScene(
        basemap = map.basemap,
        centre = state.pickup ?: map.centre,
        lines = if (route.size < 2) emptyList() else listOf(MapLine(route, if (underWay) LineKind.UNDER_WAY else LineKind.PLANNED)),
        driver = state.driverAt.takeIf { active != null },
        onCentre = onCentre,
    )
}

/** Where the map pack stands when it is not ready: what is missing, and the button to fetch it. */
@Composable
fun MapNotice(state: UiState, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val map = state.map
    if (map is MapState.Ready) return
    KCard(modifier = modifier, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.map_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            when (map) {
                MapState.Checking -> Muted(stringResource(R.string.map_checking), size = 13.sp)
                MapState.NotPublished -> Muted(stringResource(R.string.map_not_published), size = 13.sp)
                is MapState.NotDownloaded -> {
                    Muted(stringResource(if (state.role == Role.DRIVER) R.string.map_needed_driver else R.string.map_needed), size = 13.sp)
                    val megabytes = (map.bytes / 1_000_000).toInt()
                    PrimaryButton(
                        if (megabytes > 0) stringResource(R.string.map_download_size, megabytes) else stringResource(R.string.map_download),
                        onDownload,
                    )
                }
                is MapState.Downloading -> {
                    val fraction = if (map.total > 0) map.done.toFloat() / map.total else 0f
                    Muted(stringResource(R.string.map_downloading, (fraction * 100).roundToInt()), size = 13.sp)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                is MapState.Failed -> {
                    Muted(stringResource(R.string.map_failed, map.message), color = MaterialTheme.colorScheme.error, size = 13.sp)
                    SecondaryButton(stringResource(R.string.map_download), onDownload)
                }
                is MapState.Ready -> {}
            }
        }
    }
}

/** Where to: the search box and what it finds, then the place picked with the route measured to it. */
@Composable
private fun Destination(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val destination = state.destination
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.destination))
        if (destination == null) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.searchPlaces(it)
                },
                placeholder = { Text(stringResource(R.string.search_destination)) },
                leadingIcon = { Icon(KIcons.Search, contentDescription = null, Modifier.size(20.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            state.places.forEach { place ->
                KCard(padding = 12.dp, onClick = { vm.chooseDestination(place) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(place.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (place.context.isNotEmpty()) Muted(place.context, size = 13.sp)
                        }
                        place.distanceM?.let { Muted(distanceLabel(it.toInt()), size = 13.sp) }
                    }
                }
            }
            Muted(stringResource(R.string.route_private), size = 12.sp)
        } else {
            KCard(padding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(destination.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (destination.context.isNotEmpty()) Muted(destination.context, size = 13.sp)
                        }
                        TextButton(onClick = {
                            query = ""
                            vm.clearDestination()
                        }) { Text(stringResource(R.string.change), fontSize = 14.sp) }
                    }
                    val route = state.route
                    when {
                        route != null -> Text(
                            routeLabel(route.distanceM.toInt(), route.durationS.toInt()),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        state.routing -> Muted(stringResource(R.string.routing), size = 13.sp)
                        state.routeError != null -> Muted(
                            stringResource(R.string.route_failed, state.routeError),
                            color = MaterialTheme.colorScheme.error,
                            size = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun totalBalance(state: UiState): String =
    if (state.balances.isEmpty()) "R$ —" else brl(state.balances.entries.fold(BigDecimal.ZERO) { sum, (coin, units) -> sum + coin.toReais(units) })

@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    Surface {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(72.dp)) {
                TabItem(KIcons.Pin, stringResource(R.string.tab_ride), tab == Tab.RIDE) { onTab(Tab.RIDE) }
                TabItem(KIcons.Wallet, stringResource(R.string.tab_wallet), tab == Tab.WALLET) { onTab(Tab.WALLET) }
                TabItem(KIcons.Rules, stringResource(R.string.tab_rules), tab == Tab.RULES) { onTab(Tab.RULES) }
                TabItem(KIcons.Person, stringResource(R.string.tab_profile), tab == Tab.PROFILE) { onTab(Tab.PROFILE) }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f).fillMaxSize().selectable(selected = selected, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = color)
        Text(label, fontSize = 12.sp, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * Balance and past rides. The wireframe adds balance with a PIX QR code; on the local testnet the mock coins mint
 * to anyone, so the button does that instead.
 */
@Composable
fun WalletScreen(state: UiState, vm: AppViewModel, onboarding: Boolean, onDone: () -> Unit = {}) {
    var amount by rememberSaveable { mutableIntStateOf(50) }
    var coinSymbol by rememberSaveable { mutableStateOf("BRZ") }
    val coins = state.balances.keys.toList()
    val coin = coins.firstOrNull { it.symbol == coinSymbol } ?: coins.firstOrNull()
    val add = @Composable {
        PrimaryButton(
            stringResource(R.string.add_amount, brl(BigDecimal(amount))),
            onClick = {
                coin?.let { vm.addMoney(it, BigDecimal(amount)) }
                onDone()
            },
            enabled = coin != null,
            busy = state.busy,
        )
    }
    ScrollScreen {
        if (onboarding) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 20.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.skip_for_now), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Column(
            Modifier.padding(start = 20.dp, top = if (onboarding) 8.dp else 28.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Title(stringResource(if (onboarding) R.string.add_money_title else R.string.tab_wallet))
            if (onboarding) Muted(stringResource(R.string.add_money_body))
        }
        if (!onboarding) {
            KCard(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp)) {
                Column {
                    Muted(stringResource(R.string.balances), size = 13.sp)
                    Text(totalBalance(state), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    state.balances.entries.forEachIndexed { i, (c, units) ->
                        KeyValue(c.symbol, brl(c, units), divider = i < state.balances.size - 1)
                    }
                }
            }
            Text(
                stringResource(R.string.add_money_title),
                Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(Modifier.padding(start = 20.dp, top = if (onboarding) 24.dp else 12.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(20, 50, 100).forEach { n ->
                Choice("R$ $n", amount == n, { amount = n }, Modifier.weight(1f))
            }
        }
        CoinChoice(coins, coin, onPick = { coinSymbol = it.symbol }, Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp))
        Muted(
            stringResource(R.string.add_money_note),
            Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp),
            size = 12.sp,
        )
        if (onboarding) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 32.dp)) { add() }
        } else {
            Box(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp)) { add() }
            History(state, vm)
        }
    }
}

@Composable
private fun CoinChoice(coins: List<Coin>, coin: Coin?, onPick: (Coin) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(stringResource(R.string.coin_label))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            coins.forEach { c -> Choice(c.symbol, c == coin, { onPick(c) }, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun History(state: UiState, vm: AppViewModel) {
    val past = state.rides.filter { !it.status.isActive }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.history), Modifier.padding(top = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (past.isEmpty()) Muted(stringResource(R.string.no_rides))
        past.forEach { ride ->
            val coin = coinOf(state, ride.token)
            KCard(padding = 14.dp) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(statusLabel(ride.status), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Muted(stringResource(R.string.ride_number, ride.id.toString()) + " · " + shortAddress(ride.driver), size = 13.sp)
                        }
                        Text(brl(coin, ride.fare), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (ride.status == RideStatus.Completed) {
                        if (ride.id in state.rated) {
                            Muted(stringResource(R.string.rated), Modifier.padding(top = 4.dp), size = 12.sp)
                        } else {
                            StarPicker(0, onPick = { vm.rate(ride.id, it) }, size = 24.dp)
                        }
                    }
                }
            }
        }
    }
}

/** What the escrow charges and holds today, read from the chain. Voting on it is not in the app yet. */
@Composable
private fun Rules(state: UiState) {
    ScrollScreen {
        Column(Modifier.padding(start = 20.dp, top = 28.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title(stringResource(R.string.tab_rules))
            Muted(stringResource(R.string.rules_body))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // How the recommended price is worked out, read from the fare guide on chain.
            state.tariff?.let { tariff ->
                val reais = { centavos: Long -> brl(BigDecimal(centavos).movePointLeft(2)) }
                KCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.tariff_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Muted(stringResource(R.string.tariff_body), size = 13.sp)
                        Column {
                            KeyValue(stringResource(R.string.tariff_base), reais(tariff.baseCentavos))
                            KeyValue(stringResource(R.string.tariff_per_km), reais(tariff.perKmCentavos))
                            KeyValue(stringResource(R.string.tariff_per_minute), reais(tariff.perMinuteCentavos))
                            KeyValue(stringResource(R.string.tariff_minimum), reais(tariff.minimumCentavos))
                            KeyValue(stringResource(R.string.tariff_rider_range), stringResource(R.string.tariff_percent_either_way, tariff.riderRangeBps / 100))
                            KeyValue(
                                stringResource(R.string.tariff_driver_discount),
                                stringResource(R.string.tariff_percent_under, tariff.driverDiscountBps / 100, tariff.riderRangeBps / 100),
                                divider = false,
                            )
                        }
                        // A worked example with today's numbers: 5 km in 12 minutes.
                        Muted(
                            stringResource(
                                R.string.tariff_example,
                                reais(tariff.baseCentavos),
                                reais(tariff.perKmCentavos * 5),
                                reais(tariff.perMinuteCentavos * 12),
                                reais(tariff.recommended(5_000, 720)),
                            ),
                            size = 13.sp,
                        )
                        Muted(stringResource(R.string.tariff_notes), size = 12.sp)
                    }
                }
            }
            state.fees.forEach { (coin, fee) ->
                val (riderBond, driverBond) = state.bonds[coin] ?: return@forEach
                KCard {
                    Column {
                        Text(coin.symbol, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        KeyValue(stringResource(R.string.institute_per_ride), brl(coin, fee))
                        KeyValue(stringResource(R.string.rule_rider_bond), brl(coin, riderBond))
                        KeyValue(stringResource(R.string.rule_driver_bond), brl(coin, driverBond), divider = false)
                    }
                }
            }
            Muted(stringResource(R.string.rules_voting), size = 12.sp)
        }
    }
}

@Composable
private fun Profile(state: UiState, vm: AppViewModel) {
    ScrollScreen {
        Title(stringResource(R.string.tab_profile), Modifier.padding(start = 20.dp, top = 28.dp, end = 20.dp))
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(56.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.name.ifBlank { stringResource(R.string.role_rider_short) }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(shortAddress(state.address), fontFamily = PlexMono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.name.isNotBlank()) Tag(stringResource(R.string.only_you_this_phone))
                    }
                }
            }
            KCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.local_testnet), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (state.connected != null) {
                        Muted(
                            stringResource(if (state.connected) R.string.connected else R.string.not_connected, state.rpcUrl),
                            size = 13.sp,
                            color = if (state.connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.gossipConnected != null) {
                        Muted(
                            stringResource(if (state.gossipConnected) R.string.gossip_connected else R.string.gossip_not_connected),
                            size = 13.sp,
                            color = if (state.gossipConnected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    Muted(stringResource(R.string.dev_warning), size = 13.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.language))
                Spacer(Modifier.weight(1f))
                LanguagePills(currentLanguage(), ::setAppLanguage)
            }
            SecondaryButton(stringResource(R.string.driver_mode), { vm.connect(Role.DRIVER, state.rpcUrl) }, Modifier.fillMaxWidth())
            TextButton(onClick = vm::signOut, Modifier.align(Alignment.CenterHorizontally)) {
                Muted(stringResource(R.string.sign_out))
            }
        }
    }
}

// ------------------------------------------------------------------- asking for a ride

/**
 * Before the request goes out: where to, and how much the rider will pay at most. After: the drivers' quotes,
 * cheapest first, then best rated.
 */
@Composable
private fun Offers(state: UiState, vm: AppViewModel, onBack: () -> Unit, onPick: (Offer) -> Unit) {
    val request = state.request
    // The rider's price as steps of R$ 0,50 away from the recommended one.
    var steps by rememberSaveable(state.route?.distanceM, state.route?.durationS) { mutableIntStateOf(0) }
    var coinSymbol by rememberSaveable { mutableStateOf("BRZ") }
    val coins = state.balances.keys.toList()
    val coin = request?.let { coinOf(state, it.token) } ?: coins.firstOrNull { it.symbol == coinSymbol } ?: coins.firstOrNull()

    ScrollScreen {
        Row(Modifier.padding(start = 12.dp, top = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                stringResource(if (request == null) R.string.request_ride else R.string.choose_driver),
                Modifier.padding(start = 4.dp),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (request == null) {
            // Recommended from the distance and duration the request will state, so drivers work out the same.
            val tariff = state.tariff
            val trip = state.route?.let { coarseRoute(it.distanceM, it.durationS) }
            val recommended = if (tariff != null && trip != null) tariff.recommended(trip.distanceM.toInt(), trip.durationS.toInt()) else null
            val range = if (tariff != null && trip != null) tariff.riderRange(trip.distanceM.toInt(), trip.durationS.toInt()) else null
            val step = Tariff.STEP_CENTAVOS
            val centavos = recommended?.let { it + steps * step }
            val fare = BigDecimal(centavos ?: 0).movePointLeft(2)
            val needed = coin?.let { it.toBaseUnits(fare) + (state.bonds[it]?.first ?: BigInteger.ZERO) }
            val enough = coin != null && needed != null && (state.balances[coin] ?: BigInteger.ZERO) >= needed
            if (state.map is MapState.Ready) {
                Destination(state, vm, Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp))
            } else {
                MapNotice(state, vm::downloadMap, Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp))
            }
            Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pay_at_most), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (centavos == null || recommended == null || range == null || tariff == null) {
                    Muted(stringResource(R.string.price_needs_route), size = 13.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StepButton("−", stringResource(R.string.lower_price), enabled = centavos - step >= range.first) { steps -= 1 }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(brl(fare), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Muted(
                                stringResource(R.string.price_recommended, brl(BigDecimal(recommended).movePointLeft(2))),
                                size = 12.sp,
                                align = TextAlign.Center,
                            )
                        }
                        StepButton("+", stringResource(R.string.raise_price), enabled = centavos + step <= range.last) { steps += 1 }
                    }
                    Muted(
                        stringResource(R.string.price_range_hint, tariff.riderRangeBps / 100, tariff.driverDiscountBps / 100, tariff.riderRangeBps / 100),
                        size = 13.sp,
                    )
                }
                Muted(stringResource(R.string.max_fare_hint), size = 13.sp)
            }
            CoinChoice(coins, coin, onPick = { coinSymbol = it.symbol }, Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp))
            NetworkNotice(state, Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp))
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (coin != null && !enough) {
                    Muted(stringResource(R.string.not_enough_balance), color = MaterialTheme.colorScheme.error, size = 13.sp)
                }
                PrimaryButton(
                    stringResource(R.string.ask_for_offers),
                    onClick = { coin?.let { vm.requestRide(it, fare) } },
                    enabled = enough && state.route != null && centavos != null,
                    busy = state.busy,
                )
                Muted(stringResource(R.string.no_surge), size = 12.sp, align = TextAlign.Center)
            }
        } else {
            val asked = coinOf(state, request.token)
            val drivers = state.drivers.filter { it.canAccept }.associateBy { it.address }
            val offers = state.offers.filter { it.driver in drivers }
                .sortedWith(compareBy<Offer> { it.fare }.thenByDescending { drivers.getValue(it.driver).score })
            Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp)) {
                state.destination?.let { KeyValue(stringResource(R.string.destination), it.name) }
                KeyValue(stringResource(R.string.trip), routeLabel(request.route.distanceM, request.route.durationS))
                KeyValue(stringResource(R.string.pay_at_most), brl(asked, request.maxFare), divider = false)
            }
            if (state.askedAgain) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp),
                ) {
                    Text(stringResource(R.string.asked_again), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Row(
                Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Dot(MaterialTheme.colorScheme.tertiary)
                Muted(stringResource(if (offers.isEmpty()) R.string.waiting_offers else R.string.offers_arriving))
            }
            NetworkNotice(state, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            // Nobody quoting: the rider can ask again at another price, inside the fare guide's range.
            val tariff = state.tariff
            if (offers.isEmpty() && tariff != null) {
                val range = tariff.riderRange(request.route.distanceM, request.route.durationS)
                val (lowest, highest) = asked.fromCentavos(range.first) to asked.fromCentavos(range.last)
                val step = asked.fromCentavos(Tariff.STEP_CENTAVOS)
                var price by remember(request.id) { mutableStateOf(request.maxFare) }
                KCard(Modifier.padding(horizontal = 20.dp), padding = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.reprice_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StepButton("−", stringResource(R.string.lower_price), enabled = price - step >= lowest) { price -= step }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(brl(asked, price), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Muted(
                                    stringResource(
                                        R.string.price_recommended,
                                        brl(asked, asked.fromCentavos(tariff.recommended(request.route.distanceM, request.route.durationS))),
                                    ),
                                    size = 12.sp,
                                    align = TextAlign.Center,
                                )
                            }
                            StepButton("+", stringResource(R.string.raise_price), enabled = price + step <= highest) { price += step }
                        }
                        val needed = price + (state.bonds[asked]?.first ?: BigInteger.ZERO)
                        PrimaryButton(
                            stringResource(R.string.reprice_action),
                            onClick = { vm.changePrice(request, price) },
                            enabled = price != request.maxFare && (state.balances[asked] ?: BigInteger.ZERO) >= needed,
                            busy = state.busy,
                        )
                        Muted(stringResource(R.string.reprice_hint), size = 12.sp)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                offers.forEachIndexed { i, offer ->
                    val d = drivers.getValue(offer.driver)
                    KCard(
                        padding = 14.dp,
                        border = if (i == 0) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        },
                        onClick = { onPick(offer) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar()
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(shortAddress(d.address) + " · " + scoreLabel(d), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Muted(stringResource(R.string.driver_rides, d.completedRides), size = 13.sp)
                                if (i == 0) {
                                    Text(
                                        stringResource(R.string.best_option),
                                        fontFamily = PlexMono,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                            Text(brl(asked, offer.fare), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Muted(
                stringResource(R.string.no_surge),
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
                size = 12.sp,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DriverRow(state: UiState, driver: String, avatar: Int = 48) {
    val d = state.drivers.firstOrNull { it.address == driver }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(avatar.dp)
        Column {
            Text(shortAddress(driver) + " · " + scoreLabel(d), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (d != null) Muted(stringResource(R.string.driver_rides, d.completedRides), size = 13.sp)
        }
    }
}

@Composable
private fun ConfirmFare(state: UiState, request: RideRequest, offer: Offer, onBack: () -> Unit, onConfirm: () -> Unit) {
    val coin = coinOf(state, request.token)
    val fee = state.fees[coin] ?: BigInteger.ZERO
    val bond = state.bonds[coin]?.first ?: BigInteger.ZERO
    val scene = sceneOf(state)
    MapScreen(
        scene = scene,
        map = {
            if (scene == null) RouteArt(Modifier.fillMaxSize())
            BackButton(onBack, Modifier.padding(20.dp), onMap = true)
        },
    ) {
        DriverRow(state, offer.driver)
        Column {
            Text(brl(coin, offer.fare), fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
            Muted(
                routeLabel(request.route.distanceM, request.route.durationS) + " · " +
                    stringResource(R.string.per_km, brl(perKm(coin, offer.fare, request.route.distanceM))),
                size = 13.sp,
            )
            KeyValue(stringResource(R.string.to_driver), brl(coin, offer.fare - fee))
            KeyValue(stringResource(R.string.institute_per_ride), brl(coin, fee))
            KeyValue(stringResource(R.string.bond_back_on_arrival), brl(coin, bond), divider = false)
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
        ) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(KIcons.Lock, contentDescription = null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.locked_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Muted(stringResource(R.string.locked_body), size = 13.sp)
                }
            }
        }
        Muted(stringResource(R.string.cancel_costs, brl(coin, bond)), size = 12.sp)
        PrimaryButton(
            stringResource(R.string.lock_amount, brl(coin, offer.fare + bond)),
            onConfirm,
            busy = state.busy,
            icon = KIcons.Lock,
        )
    }
}

// ------------------------------------------------------------------- the ride

/** From the fare being locked until the driver starts the ride. */
@Composable
private fun OnTheWay(state: UiState, ride: Ride, onCancel: () -> Unit) {
    val coin = coinOf(state, ride.token)
    val scene = sceneOf(state)
    MapScreen(
        scene = scene,
        map = {
            if (scene == null) RouteArt(Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LockedPill(stringResource(R.string.locked_for_ride, brl(coin, ride.fare + ride.riderBond)))
                NetworkNotice(state)
            }
        },
    ) {
        Text(statusLabel(ride.status), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        DriverRow(state, ride.driver, avatar = 56)
        DriverWhereabouts(state)
        when (ride.status) {
            RideStatus.Open -> Muted(stringResource(R.string.hint_open), size = 13.sp)
            RideStatus.Accepted -> Muted(
                stringResource(R.string.hint_accepted) + " " + stringResource(R.string.cancel_costs, brl(coin, ride.riderBond)),
                size = 13.sp,
            )
            else -> {}
        }
        if (ride.status == RideStatus.Open || ride.status == RideStatus.Accepted) {
            TextButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.cancel_ride), fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/**
 * What the rider knows about where the driver is: still on someone else's ride, so this one waits its turn, or
 * on this one and showing on the map.
 */
@Composable
private fun DriverWhereabouts(state: UiState) {
    when {
        state.ridesAhead > 0 -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    pluralStringResource(R.plurals.driver_busy_title, state.ridesAhead, state.ridesAhead),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Muted(stringResource(R.string.driver_busy_body), size = 13.sp)
            }
        }
        state.driverAt != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(NextRide, 10.dp)
            Muted(stringResource(R.string.driver_live), size = 13.sp)
        }
    }
}

@Composable
private fun LockedPill(text: String) {
    Pill {
        Icon(KIcons.Lock, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun OnTrip(state: UiState, ride: Ride, onArrived: () -> Unit) {
    val coin = coinOf(state, ride.token)
    val scene = sceneOf(state, underWay = true)
    MapScreen(
        scene = scene,
        map = {
            if (scene == null) RouteArt(Modifier.fillMaxSize(), underWay = true)
            NetworkNotice(state, Modifier.padding(20.dp))
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.status_started), Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Icon(KIcons.Lock, contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
            Text(
                brl(coin, ride.fare),
                Modifier.padding(start = 4.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        DriverRow(state, ride.driver)
        DriverWhereabouts(state)
        Muted(stringResource(R.string.signal_note), size = 12.sp)
        PrimaryButton(stringResource(R.string.action_complete), onArrived)
    }
}
