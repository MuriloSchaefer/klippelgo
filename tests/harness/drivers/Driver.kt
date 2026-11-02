package org.klippelgo.tests.drivers

import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.tests.Phone
import org.klippelgo.tests.reais
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal

/** The driver's home: the map, today's numbers, and the round button that goes online. */
class GoOnline(phone: Phone) : Component(phone, Tags.GO_ONLINE) {
    fun online(): Boolean = phone.find(Tags.goOnlineStatus(true)) != null

    fun toggle() = phone.tap(Tags.GO_ONLINE_TOGGLE)

    fun goOnline() {
        if (!online()) toggle()
        phone.awaitTag(Tags.goOnlineStatus(true))
    }

    fun goOffline() {
        if (online()) toggle()
        phone.awaitTag(Tags.goOnlineStatus(false))
    }

    /** "★ 4,9", or the app's word for a driver nobody rated yet. */
    fun score(): String = read(Tags.GO_ONLINE_SCORE)

    fun rides(): Int = read(Tags.GO_ONLINE_RIDES).toInt()

    fun earnedToday(): BigDecimal = reais(read(Tags.GO_ONLINE_TODAY))

    /** Why the policy will not let this driver accept rides, or null when it does. */
    fun blocked(): String? = readOrNull(Tags.BLOCKED)

    /**
     * The kinds of reason the driver is shown for not being let to accept rides, empty when there is none:
     * `account` (blocked), `disabled` (nobody may accept), `cnh`, `stake`, `score`, `role`, `age`, `credential`.
     */
    fun blockers(): Set<String> =
        phone.screen().startingWith(Tags.blocker("")).map { it.tag.removePrefix(Tags.blocker("")) }.toSet()

    /** Waits until the driver is shown exactly these reasons: none, when given none. */
    fun awaitBlockers(vararg kinds: String, timeoutMs: Long = 60_000): Set<String> =
        waitUntil("the driver to be shown ${kinds.toSet().ifEmpty { "no reason not to accept" }}", timeoutMs) {
            blockers().takeIf { it == kinds.toSet() }
        }

    fun riderMode() = phone.tap(Tags.GO_ONLINE_RIDER_MODE)
}

/** The driver's own rules for requests not worth looking at. */
class AutoSkip(phone: Phone) : Component(phone, Tags.AUTO_SKIP) {
    /** The floor per km as shown: an amount, or the app's word for "off". */
    fun perKm(): String = read(Tags.AUTO_SKIP_PER_KM)

    fun raisePerKm() = phone.tap(Tags.AUTO_SKIP_PER_KM_RAISE)

    fun lowerPerKm() = phone.tap(Tags.AUTO_SKIP_PER_KM_LOWER)

    /** How far under the recommended price a request may be, as shown. */
    fun below(): String = read(Tags.AUTO_SKIP_BELOW)

    fun raiseBelow() = phone.tap(Tags.AUTO_SKIP_BELOW_RAISE)

    fun lowerBelow() = phone.tap(Tags.AUTO_SKIP_BELOW_LOWER)

    /** How many open requests the rules are hiding, or 0 when the app says nothing about it. */
    fun skipped(): Int = readOrNull(Tags.AUTO_SKIP_SKIPPED)?.let { Regex("""\d+""").find(it)?.value?.toInt() } ?: 0
}

/** What a driver is told about a request: who, how far, how much, and roughly where. Never the addresses. */
data class RequestShown(
    val rider: String,
    val trip: String,
    val pays: BigDecimal,
    val price: BigDecimal,
    val pickupRegion: String?,
    val dropoffRegion: String?,
    /** What the driver would receive for the quote on screen, after the fee and the tax. */
    val receive: BigDecimal,
    /** The line that says what comes out: the tax, and what the network costs. */
    val costs: String,
)

/**
 * Riders' requests on the driver's screen. Online they are a list under the driver's rules; the one tapped
 * opens to the whole request and the quote, and its regions go on the map. With a ride in hand a request is a
 * screen of its own. Either way the open one is the [Tags.REQUEST] piece.
 */
class IncomingRequest(phone: Phone) : Component(phone, Tags.REQUEST) {
    private val entry = Tags.requestEntry("")

    /** Whether the driver is shown any request at all: one open, or any in the list. */
    override fun visible(): Boolean = phone.screen().let { it.has(tag) || it.startingWith(entry).isNotEmpty() }

    override fun shown(): Boolean = phone.await("a request") { screen -> true.takeIf { screen.has(tag) || screen.startingWith(entry).isNotEmpty() } }

    override fun gone(): Boolean = waitUntil("no request to be on the driver's screen") { true.takeIf { !visible() } }

    /** How many requests the list says it holds. */
    fun count(): Int {
        if (!visible()) return 0
        // The line may be under the fold or scrolled away: it is looked for, as a person would.
        return Regex("""\d+""").find(phone.reach(Tags.GO_ONLINE_REQUESTS_COUNT).text)?.value?.toInt() ?: 0
    }

    fun info(): RequestShown {
        phone.awaitTag(tag)
        return RequestShown(
            rider = read(Tags.REQUEST_RIDER),
            trip = read(Tags.REQUEST_TRIP),
            pays = reais(read(Tags.REQUEST_PAYS)),
            price = reais(read(Tags.REQUEST_PRICE)),
            pickupRegion = readOrNull(Tags.REQUEST_PICKUP),
            dropoffRegion = readOrNull(Tags.REQUEST_DROPOFF),
            receive = reais(read(Tags.REQUEST_RECEIVE)),
            costs = read(Tags.REQUEST_COSTS),
        )
    }

    /** Whether a request from this rider, given as the app shortens addresses, is open or in the list now. */
    fun listedFrom(rider: String): Boolean {
        val screen = phone.screen()
        return screen[Tags.REQUEST_RIDER]?.text == rider ||
            screen.startingWith(entry).any { it.tag.endsWith(".rider") && it.text == rider }
    }

    /**
     * Waits for a request from this rider, given as the app shortens addresses, and picks it in the list as a
     * driver would, by tapping it, so that it is the one open.
     */
    fun awaitFrom(rider: String, timeoutMs: Long = 60_000): Boolean = waitUntil("a request from $rider", timeoutMs) {
        val screen = phone.screen()
        if (screen[Tags.REQUEST_RIDER]?.text == rider) {
            // A card just opened takes no tap on its buttons for half a second, against double taps.
            Thread.sleep(700)
            return@waitUntil true
        }
        val listed = screen.startingWith(entry).firstOrNull { it.tag.endsWith(".rider") && it.text == rider }
        when {
            listed != null -> phone.tap(listed.tag.removeSuffix(".rider"))
            // Another one is open and fills the sheet: tapping it closes it, and the rest of the list is in sight again.
            screen.has(Tags.GO_ONLINE) && screen.has(Tags.REQUEST_RIDER) -> phone.tap(Tags.REQUEST_RIDER)
        }
        null
    }

    /** The quote about to be sent. */
    fun price(): BigDecimal = reais(read(Tags.REQUEST_PRICE))

    /** Lowers the quote by R$ 0,50. False when the fare guide allows no lower. */
    fun lower(): Boolean = step(Tags.REQUEST_LOWER)

    fun raise(): Boolean = step(Tags.REQUEST_RAISE)

    private fun step(button: String): Boolean {
        if (!phone.reach(button).enabled) return false
        phone.tap(button)
        return true
    }

    /** Sends the quote, or the changed quote. */
    fun send() = phone.tap(Tags.REQUEST_SEND)

    /** The quote this driver already has out for the request, or null. */
    fun yourOffer(): BigDecimal? = readOrNull(Tags.REQUEST_YOUR_OFFER)?.let(::reais)

    fun awaitYourOffer(): BigDecimal = phone.await("the app to show the offer as sent") { it[Tags.REQUEST_YOUR_OFFER] }.reais()

    /** Passes the request that is open, or the first of the list when none is. */
    fun pass() {
        if (phone.find(Tags.REQUEST_PASS) == null) {
            phone.tap(phone.await("a request to pass") { screen -> screen.startingWith(entry).firstOrNull { !it.tag.endsWith(".rider") } }.tag)
            phone.awaitTag(Tags.REQUEST_PASS)
            Thread.sleep(700)
        }
        phone.tap(Tags.REQUEST_PASS)
    }
}

/** One ride in the driver's sheet. `route` is checked, waiting or needs-map. */
data class RideShown(val rider: String, val ride: String, val fare: BigDecimal, val route: String, val routeText: String)

/**
 * The driver's rides in hand: the one being driven (`current`) and every one a rider locked and the driver has
 * not answered, each in a slot of its own (`chosen.<ride id>`), oldest first. `chosen` alone means the oldest of
 * those; the functions that take a `ride` act on that ride, and on the oldest when given none.
 */
class DriverRide(phone: Phone) : Component(phone, Tags.DRIVER_RIDE) {
    /** What the banner says the driver is guaranteed for the ride at the front. */
    fun guaranteed(): BigDecimal = reais(read(Tags.DRIVER_RIDE_GUARANTEED))

    /** The ids of the rides waiting for the driver's answer, oldest first. */
    fun locked(): List<String> = lockedIn(phone.screen())

    private fun lockedIn(screen: org.klippelgo.tests.Screen): List<String> {
        val number = Regex("""^driver-ride\.${Tags.SLOT_CHOSEN}\.(\d+)\.ride$""")
        return screen.startingWith("${Tags.DRIVER_RIDE}.${Tags.chosenSlot("")}").mapNotNull { number.find(it.tag)?.groupValues?.get(1) }
            .distinct().sortedBy { it.toBigInteger() }
    }

    /** The slot a test means: `chosen` is the oldest ride waiting for an answer, or none when there is none. */
    private fun slotIn(screen: org.klippelgo.tests.Screen, slot: String): String? =
        if (slot == Tags.SLOT_CHOSEN) lockedIn(screen).firstOrNull()?.let(Tags::chosenSlot) else slot

    private fun chosen(ride: String?): String =
        ride?.let(Tags::chosenSlot) ?: slotIn(phone.screen(), Tags.SLOT_CHOSEN) ?: Tags.chosenSlot("none")

    /** `slot` is current, chosen, or chosen.<ride id>. Null when there is no ride in that slot. */
    fun ride(slot: String): RideShown? {
        val screen = phone.screen()
        val at = slotIn(screen, slot) ?: return null
        val number = screen[Tags.rideNumber(at)] ?: return null
        val route = screen.startingWith(Tags.rideRoute(at, "")).firstOrNull()
        return RideShown(
            rider = screen[Tags.rideRider(at)]?.text ?: "",
            ride = Regex("""\d+""").find(number.text)?.value ?: "",
            fare = number.reais(),
            route = route?.tag?.substringAfterLast('.') ?: "",
            routeText = route?.text ?: "",
        )
    }

    fun awaitRide(slot: String, timeoutMs: Long = 60_000): RideShown = phone.await("a $slot ride", timeoutMs) { ride(slot) }

    /** Waits until the app says the rider's route arrived and held on this phone's map. */
    fun awaitRouteChecked(slot: String, timeoutMs: Long = 60_000): String =
        phone.await("the $slot ride's route to be checked", timeoutMs) { screen ->
            slotIn(screen, slot)?.let { screen[Tags.rideRoute(it, Tags.ROUTE_CHECKED)] }
        }.text

    /** Takes a ride a rider locked. */
    fun accept(ride: String? = null) = phone.tap(Tags.rideAccept(chosen(ride)))

    /** Whether a ride a rider locked can be taken now: it is there, and its button can be tapped. */
    fun canAccept(ride: String? = null): Boolean = phone.find(Tags.rideAccept(chosen(ride)))?.enabled == true

    /** Whether the driver is told the ride can be taken once the current rider is in the car. */
    fun afterPickup(ride: String? = null): Boolean = phone.find(Tags.rideAfterPickup(chosen(ride))) != null

    fun decline(ride: String? = null) = phone.tap(Tags.rideDecline(chosen(ride)))

    /** Starts the ride at the pickup. */
    fun start() = phone.tap(Tags.DRIVER_RIDE_START)

    /** Gives up a ride already accepted. */
    fun cancel() = phone.tap(Tags.DRIVER_RIDE_CANCEL)

    /** The next request offered while a ride is in hand, or null. */
    fun nextRequest(): String? = readOrNull(Tags.DRIVER_RIDE_NEXT_REQUEST)

    /** Opens that request to quote on it. */
    fun quoteNext() = phone.tap(Tags.DRIVER_RIDE_QUOTE)
}
