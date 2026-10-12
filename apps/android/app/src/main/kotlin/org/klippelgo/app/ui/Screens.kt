package org.klippelgo.app.ui

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.klippelgo.app.AppViewModel
import org.klippelgo.app.DEFAULT_RPC
import org.klippelgo.app.R
import org.klippelgo.app.Role
import org.klippelgo.app.UiState
import org.klippelgo.app.setAppLanguage
import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.chain.Coin
import org.klippelgo.chain.DriverInfo
import org.klippelgo.chain.Ride
import org.klippelgo.chain.RideStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Lets the tests find a piece of the UI by its tag (see [Tags]), the way a screen reader finds it by its label. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.testable(): Modifier = semantics { testTagsAsResourceId = true }

@Composable
fun KlippelGoApp(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize().testable(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().imePadding()) {
            when (state.role) {
                null -> Onboarding(state, vm)
                Role.RIDER -> RiderApp(state, vm)
                Role.DRIVER -> DriverApp(state, vm)
            }
        }
        state.error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = {
                    TextButton(onClick = vm::dismissError, Modifier.testTag(Tags.ERROR_DISMISS)) { Text(stringResource(R.string.dismiss)) }
                },
                text = { Text(msg, Modifier.testTag(Tags.ERROR_MESSAGE)) },
                // A dialog is a window of its own, so it says again that its tags may be read.
                modifier = Modifier.testable().testTag(Tags.ERROR),
            )
        }
    }
}

// ------------------------------------------------------------------- onboarding

@Composable
private fun Onboarding(state: UiState, vm: AppViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    if (step == 0) {
        Welcome(onStart = { step = 1 })
    } else {
        BackHandler { step = 0 }
        CreateAccount(state, onBack = { step = 0 }, onContinue = vm::createAccount)
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    ScrollScreen(Tags.WELCOME) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 28.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
            Text("KlippelGo", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            LanguagePills(currentLanguage(), ::setAppLanguage)
        }
        MapSurface(
            Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)),
        ) {
            RouteArt(Modifier.fillMaxSize())
        }
        Column(Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title(stringResource(R.string.welcome_title))
            Muted(stringResource(R.string.welcome_body))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Promise(KIcons.Lock, stringResource(R.string.promise_escrow), stringResource(R.string.promise_escrow_body))
            Promise(KIcons.Phone, stringResource(R.string.promise_data), stringResource(R.string.promise_data_body))
            Promise(KIcons.Rules, stringResource(R.string.promise_rules), stringResource(R.string.promise_rules_body))
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(stringResource(R.string.start), onStart, Modifier.testTag(Tags.WELCOME_START))
            Muted(stringResource(R.string.institute_infra), size = 12.sp)
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    KCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Muted(body)
            }
        }
    }
}

@Composable
fun IconTile(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * The wireframe asks for a phone number and creates a key behind Face ID. Until smart accounts exist the app
 * signs with the seeded test accounts, so this screen picks which one to be and where the test network is.
 */
@Composable
private fun CreateAccount(state: UiState, onBack: () -> Unit, onContinue: (String, Role, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(state.name) }
    var role by rememberSaveable { mutableStateOf(Role.RIDER) }
    var rpc by rememberSaveable { mutableStateOf(state.rpcUrl.ifBlank { DEFAULT_RPC }) }
    ScrollScreen(Tags.CREATE_ACCOUNT) {
        BackButton(onBack, Modifier.padding(start = 12.dp, top = 20.dp))
        Column(Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title(stringResource(R.string.create_title))
            Muted(stringResource(R.string.create_body))
        }
        Column(Modifier.padding(start = 20.dp, top = 28.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(stringResource(R.string.first_name))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Muted(stringResource(R.string.first_name_hint), size = 16.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag(Tags.CREATE_NAME),
                )
                Tag(stringResource(R.string.only_you_this_phone), Modifier.padding(top = 2.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(stringResource(R.string.choose_role))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice(stringResource(R.string.role_rider), role == Role.RIDER, { role = Role.RIDER }, Modifier.weight(1f).testTag(Tags.CREATE_ROLE_RIDER))
                    Choice(stringResource(R.string.role_driver), role == Role.DRIVER, { role = Role.DRIVER }, Modifier.weight(1f).testTag(Tags.CREATE_ROLE_DRIVER))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(stringResource(R.string.rpc_label))
                OutlinedTextField(
                    value = rpc,
                    onValueChange = { rpc = it },
                    supportingText = { Text(stringResource(R.string.rpc_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag(Tags.CREATE_RPC),
                )
            }
        }
        KCard(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(KIcons.Lock)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.local_testnet), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Muted(stringResource(R.string.dev_warning))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            stringResource(R.string.continue_label),
            onClick = { onContinue(name, role, rpc) },
            enabled = rpc.isNotBlank(),
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 32.dp).testTag(Tags.CREATE_CONTINUE),
        )
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

// ------------------------------------------------------------------- shared pieces

@Composable
fun currentLanguage(): String = AppCompatDelegate.getApplicationLocales()[0]?.language ?: "pt"

/** Says so when the chain node or the gossip network is out of reach. Nothing when both are fine. */
@Composable
fun NetworkNotice(state: UiState, modifier: Modifier = Modifier) {
    val text = when {
        state.connected == false -> stringResource(R.string.not_connected, state.rpcUrl)
        state.gossipConnected == false -> stringResource(R.string.gossip_not_connected)
        else -> return
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error, modifier = modifier.testTag(Tags.NETWORK_NOTICE)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
fun statusLabel(status: RideStatus): String = stringResource(
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
fun scoreLabel(d: DriverInfo?): String = when {
    d == null || d.ratings == 0 -> stringResource(R.string.driver_new)
    else -> "★ " + String.format(Locale.forLanguageTag("pt-BR"), "%.1f", d.score / 100.0)
}

/**
 * The end of a ride, for both sides. The rider lands here on arrival and pays by confirming; after that, and for
 * the driver, it only asks for the rating.
 */
@Composable
fun RideEndScreen(
    state: UiState,
    ride: Ride,
    onBack: (() -> Unit)?,
    onConfirm: (Int) -> Unit,
    onSkip: () -> Unit,
) {
    val coin = coinOf(state, ride.token)
    val rider = state.role == Role.RIDER
    val paid = ride.status == RideStatus.Completed
    var stars by rememberSaveable(ride.id.toString()) { mutableIntStateOf(0) }
    ScrollScreen(Tags.RIDE_END) {
        if (onBack != null) BackButton(onBack, Modifier.padding(start = 12.dp, top = 20.dp)) else Spacer(Modifier.height(48.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(KIcons.Check, contentDescription = null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Title(stringResource(if (rider && !paid) R.string.arrived_title else R.string.status_completed), Modifier.testTag(Tags.RIDE_END_TITLE), size = 26.sp)
            Muted(stringResource(R.string.ride_number, ride.id.toString()), Modifier.testTag(Tags.RIDE_END_RIDE))
        }
        KCard(Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (rider) R.string.amount else R.string.you_received),
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(brl(coin, if (rider) ride.fare else ride.driverPayout), Modifier.testTag(Tags.RIDE_END_AMOUNT), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Muted(
                    when {
                        !rider -> stringResource(R.string.institute_got, brl(coin, ride.fee))
                        paid -> stringResource(R.string.paid_to, shortAddress(ride.driver))
                        else -> stringResource(R.string.paid_on_confirm, shortAddress(ride.driver))
                    },
                    size = 13.sp,
                )
                // What came out of the fare for the government, and what the ride cost the network on this
                // side: what this phone's transactions paid if it knows, else what they are expected to.
                KeyValue(
                    stringResource(if (rider) R.string.tax_in_fare else R.string.tax_paid, percent(state.taxBps, ride)),
                    brl(coin, ride.tax),
                    tag = Tags.RIDE_END_TAX,
                )
                val spent = state.infrastructureSpent[ride.id]
                (spent ?: state.infrastructure)?.let { cost ->
                    KeyValue(
                        stringResource(R.string.infrastructure_cost),
                        (if (spent == null) "≈ " else "") + brlFine(cost),
                        divider = false,
                        tag = Tags.RIDE_END_INFRASTRUCTURE,
                    )
                    Muted(stringResource(R.string.infrastructure_note), size = 12.sp)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 24.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(if (rider) R.string.rate_driver else R.string.rate_rider), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            StarPicker(stars, onPick = { stars = it }, tag = Tags.RIDE_END_STARS)
            Tag(stringResource(R.string.rating_public))
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (paid) {
                PrimaryButton(stringResource(R.string.send_rating), { onConfirm(stars) }, Modifier.testTag(Tags.RIDE_END_RATE), enabled = stars > 0, busy = state.busy)
                TextButton(onClick = onSkip, Modifier.testTag(Tags.RIDE_END_SKIP), enabled = !state.busy) {
                    Muted(stringResource(R.string.not_now), align = TextAlign.Center)
                }
            } else {
                PrimaryButton(stringResource(R.string.confirm_and_pay), { onConfirm(stars) }, Modifier.testTag(Tags.RIDE_END_PAY), busy = state.busy)
            }
        }
    }
}

// ------------------------------------------------------------------- formatting

fun coinOf(state: UiState, token: String): Coin =
    state.balances.keys.firstOrNull { it.address.equals(token, ignoreCase = true) } ?: Coin("?", token, 18)

private val brlFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))

/** Always formats as Brazilian reais (R$ 23,40), whatever the app language. */
fun brl(coin: Coin, units: BigInteger): String =
    brlFormat.format(coin.toReais(units).setScale(2, RoundingMode.DOWN))

fun brl(reais: BigDecimal): String = brlFormat.format(reais.setScale(2, RoundingMode.DOWN))

/**
 * An amount that may be under a centavo, as what a ride costs the network often is: R$ 0,0012 where R$ 0,00
 * would say it is free.
 */
fun brlFine(reais: BigDecimal): String =
    if (reais.signum() > 0 && reais < BigDecimal("0.01")) "R$ " + reais.setScale(4, RoundingMode.HALF_UP).toPlainString().replace('.', ',') else brl(reais)

/** The tax rate as a ride carries it, "5" for 5%: from the ride's own amounts when it has a fare, else today's rate. */
fun percent(taxBps: Int, ride: Ride? = null): String {
    val bps = if (ride != null && ride.fare.signum() > 0) (ride.tax * BigInteger.valueOf(10_000) / ride.fare).toInt() else taxBps
    return BigDecimal(bps).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ',')
}

/** "8,5 km" from 8 500 m; "850 m" under a kilometre. */
fun distanceLabel(metres: Int): String =
    if (metres < 1000) "$metres m" else String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f km", metres / 1000.0)

/** "8,5 km · 22 min" */
fun routeLabel(metres: Int, seconds: Int): String = distanceLabel(metres) + " · " + maxOf(1, (seconds + 30) / 60) + " min"

/** What a fare comes to per kilometre of the route, in reais. */
fun perKm(coin: Coin, fare: BigInteger, metres: Int): BigDecimal =
    coin.toReais(fare).multiply(BigDecimal(1000)).divide(BigDecimal(maxOf(metres, 1)), 2, RoundingMode.HALF_UP)

fun shortAddress(a: String): String = if (a.length > 12) a.take(6) + "…" + a.takeLast(4) else a
