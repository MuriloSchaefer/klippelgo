package org.klippelgo.tests.integrity

import org.junit.jupiter.api.Test
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.core.Coordinate
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import kotlin.test.assertTrue

/**
 * A driver who is online, or on a ride, with the app off screen: Android does not suspend it. The service shows
 * while online and goes with it, and a rider goes on hearing where the car is with the driver's screen off. On the
 * second pair of seeded accounts, like `OnTheRideTest`.
 */
class OnDutyTest : Scenario() {
    private fun onDuty() = "isForeground=true" in phone.shell("dumpsys activity services org.klippelgo.app/.OnDutyService")

    private fun activities() = Regex("""Hist\s+#\d+: .*MainActivity""").findAll(phone.shell("dumpsys activity activities")).count()

    /** Opens the shade and taps the service's notification, by what the system's own view says is where. */
    private fun tapNotification() {
        phone.shell("cmd statusbar expand-notifications")
        Thread.sleep(1_500)
        val xml = waitUntil("the notification in the shade", 10_000) { phone.rawDump().takeIf { "text=\"Conectado" in it } }
        val bounds = Regex("""text="Conectado[^>]*bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""").find(xml)!!
        val (left, top, right, bottom) = bounds.destructured
        Thread.sleep(1_000) // the shade is still sliding down
        phone.tapAt((left.toInt() + right.toInt()) / 2, (top.toInt() + bottom.toInt()) / 2)
        Thread.sleep(2_000)
    }

    private fun pastUnratedRides() {
        Thread.sleep(4_000)
        while (phone.screen().name == "ride-end") {
            app.rideEnd.skip()
            Thread.sleep(2_000)
        }
    }

    @Test
    fun `off screen a driver on a ride goes on telling the rider where the car is`() {
        val rider = peer(DevAccount.RIDER_2)
        phone.launch(fresh = true, role = "driver", pair = 2)
        phone.locate(Places.CENTRE.latE7 / 1e7, Places.CENTRE.lonE7 / 1e7)
        pastUnratedRides()
        app.goOnline.shown()
        app.mapNotice.download()
        assertTrue(!onDuty(), "on duty before going online")
        app.goOnline.goOnline()
        waitUntil("the service to start", 15_000) { true.takeIf { onDuty() } }
        app.goOnline.goOffline()
        waitUntil("the service to stop", 15_000) { true.takeIf { !onDuty() } }
        app.goOnline.goOnline()
        waitUntil("the service to start again", 15_000) { true.takeIf { onDuty() } }

        // Its notification brings back the app that is running: a second one would be a second session.
        phone.shell("input keyevent KEYCODE_HOME")
        tapNotification()
        assertTrue(activities() == 1, "the notification opened a second activity")
        assertTrue(onDuty(), "coming back through the notification stopped the service")
        app.goOnline.shown()
        val driver = stack.client(DevAccount.DRIVER_2).address

        val trip = rider.route(Places.CATHEDRAL, Places.LAKE)
        val request = rider.ask(trip)
        app.request.awaitFrom(Peer.short(request.rider))
        app.request.send()
        val id = rider.take(request, rider.awaitOffer(request, driver))
        app.driverRide.awaitRouteChecked("chosen")
        app.driverRide.accept()
        app.driverRide.awaitRouteChecked("current")
        val first = trip.line.first()
        phone.locate(first.latE7 / 1e7, first.lonE7 / 1e7)
        app.driverRide.start()
        waitUntil("the ride to start on chain", 60_000) { true.takeIf { rider.ride(id).status == RideStatus.Started } }
        Thread.sleep(6_000)

        phone.shell("input keyevent KEYCODE_HOME")
        phone.shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(20_000) // long enough for an app with nothing keeping it running to be cached
        assertTrue(onDuty(), "the service stopped with the app off screen")
        val heard = HashSet<Coordinate>()
        for (fraction in listOf(0.2, 0.4, 0.6, 0.8)) {
            val at = trip.line[(trip.line.size * fraction).toInt()]
            phone.locate(at.latE7 / 1e7, at.lonE7 / 1e7)
            Thread.sleep(4_000)
            rider.driverPosition(id)?.let { heard += Coordinate(it.latE7, it.lonE7) }
        }
        assertTrue(heard.size >= 3, "off screen, the rider heard the car in ${heard.size} places: $heard")

        phone.shell("input keyevent KEYCODE_WAKEUP")
        rider.complete(id)

        // The ride over, it is the driver's being online that keeps it, and going offline ends it.
        Thread.sleep(2_000)
        tapNotification()
        pastUnratedRides()
        app.goOnline.shown()
        assertTrue(onDuty(), "the service stopped with the driver still online")
        app.goOnline.goOffline()
        waitUntil("the service to stop", 30_000) { true.takeIf { !onDuty() } }
    }
}
