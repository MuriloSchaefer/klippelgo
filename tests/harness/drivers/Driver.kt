package org.klippelgo.tests.drivers

import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.tests.Phone
import org.klippelgo.tests.reais
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
data class RequestShown(val rider: String, val trip: String, val pays: BigDecimal, val price: BigDecimal, val pickupRegion: String?, val dropoffRegion: String?)

/** A rider's request on the driver's screen, to quote on or pass. */
class IncomingRequest(phone: Phone) : Component(phone, Tags.REQUEST) {
    fun info(): RequestShown {
        phone.awaitTag(tag)
        return RequestShown(
            rider = read(Tags.REQUEST_RIDER),
            trip = read(Tags.REQUEST_TRIP),
            pays = reais(read(Tags.REQUEST_PAYS)),
            price = reais(read(Tags.REQUEST_PRICE)),
            pickupRegion = readOrNull(Tags.REQUEST_PICKUP),
            dropoffRegion = readOrNull(Tags.REQUEST_DROPOFF),
        )
    }

    /** Waits for a request from this rider, given as the app shortens addresses, to be the one on screen. */
    fun awaitFrom(rider: String, timeoutMs: Long = 60_000): Boolean {
        phone.await("a request from $rider", timeoutMs) { screen -> screen[Tags.REQUEST_RIDER]?.takeIf { it.text == rider } }
        return true
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

    fun pass() = phone.tap(Tags.REQUEST_PASS)
}

/** One ride in the driver's sheet. `route` is checked, waiting or needs-map. */
data class RideShown(val rider: String, val ride: String, val fare: BigDecimal, val route: String, val routeText: String)

/** The driver's rides in hand: the one being driven (`current`) and the one a rider just locked (`chosen`). */
class DriverRide(phone: Phone) : Component(phone, Tags.DRIVER_RIDE) {
    /** What the banner says the driver is guaranteed for the ride at the front. */
    fun guaranteed(): BigDecimal = reais(read(Tags.DRIVER_RIDE_GUARANTEED))

    /** `slot` is current or chosen. Null when there is no ride in that slot. */
    fun ride(slot: String): RideShown? {
        val screen = phone.screen()
        val number = screen[Tags.rideNumber(slot)] ?: return null
        val route = screen.startingWith(Tags.rideRoute(slot, "")).firstOrNull()
        return RideShown(
            rider = screen[Tags.rideRider(slot)]?.text ?: "",
            ride = Regex("""\d+""").find(number.text)?.value ?: "",
            fare = number.reais(),
            route = route?.tag?.substringAfterLast('.') ?: "",
            routeText = route?.text ?: "",
        )
    }

    fun awaitRide(slot: String, timeoutMs: Long = 60_000): RideShown = phone.await("a $slot ride", timeoutMs) { ride(slot) }

    /** Waits until the app says the rider's route arrived and held on this phone's map. */
    fun awaitRouteChecked(slot: String, timeoutMs: Long = 60_000): String =
        phone.awaitTag(Tags.rideRoute(slot, Tags.ROUTE_CHECKED), timeoutMs).text

    /** Takes the ride a rider locked. */
    fun accept() = phone.tap(Tags.DRIVER_RIDE_ACCEPT)

    fun decline() = phone.tap(Tags.DRIVER_RIDE_DECLINE)

    /** Starts the ride at the pickup. */
    fun start() = phone.tap(Tags.DRIVER_RIDE_START)

    /** Gives up a ride already accepted. */
    fun cancel() = phone.tap(Tags.DRIVER_RIDE_CANCEL)

    /** The next request offered while a ride is in hand, or null. */
    fun nextRequest(): String? = readOrNull(Tags.DRIVER_RIDE_NEXT_REQUEST)

    /** Opens that request to quote on it. */
    fun quoteNext() = phone.tap(Tags.DRIVER_RIDE_QUOTE)
}
