package org.klippelgo.tests.integrity

import org.junit.jupiter.api.Test
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.core.Coordinate
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.cli.Drive
import org.klippelgo.tests.waitUntil
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * A ride being driven: the car moves a step a second, the rider hears each one, and the road already driven
 * comes off the map. On the second pair of seeded accounts, so it can run beside the phones of
 * `make android-two-phones`, which hold the first.
 */
class OnTheRideTest : Scenario() {
    /** How much of a picture is the colour a ride under way is drawn in. */
    private fun routeDrawn(picture: BufferedImage): Int {
        var count = 0
        for (y in 0 until picture.height step 2) {
            for (x in 0 until picture.width step 2) {
                val p = picture.getRGB(x, y)
                val close = abs(((p shr 16) and 0xFF) - UNDER_WAY_RED) <= 24 && abs(((p shr 8) and 0xFF) - UNDER_WAY_GREEN) <= 24 && abs((p and 0xFF) - UNDER_WAY_BLUE) <= 24
                if (close) count++
            }
        }
        return count
    }

    /**
     * These accounts are not wiped with the app: a ride an earlier run left unrated comes up first, asking for
     * stars. Each is passed over.
     */
    private fun pastUnratedRides() {
        Thread.sleep(4_000)
        while (phone.screen().name == "ride-end") {
            app.rideEnd.skip()
            Thread.sleep(2_000)
        }
    }

    @Test
    fun `on a ride the rider hears the car every second and the road driven comes off the driver's map`() {
        val rider = peer(DevAccount.RIDER_2)
        phone.launch(fresh = true, role = "driver", pair = 2)
        phone.locate(Places.CENTRE.latE7 / 1e7, Places.CENTRE.lonE7 / 1e7)
        pastUnratedRides()
        app.goOnline.shown()
        app.mapNotice.download()
        app.goOnline.goOnline()
        val driver = stack.client(DevAccount.DRIVER_2).address

        val trip = rider.route(Places.CATHEDRAL, Places.LAKE)
        val request = rider.ask(trip)
        app.request.awaitFrom(Peer.short(request.rider))
        app.request.send()
        val id = rider.take(request, rider.awaitOffer(request, driver))
        app.driverRide.awaitRouteChecked("chosen")
        app.driverRide.accept()
        app.driverRide.awaitRouteChecked("current")
        // At the pickup, the ride starts.
        val line = trip.line
        phone.locate(line.first().latE7 / 1e7, line.first().lonE7 / 1e7)
        app.driverRide.start()
        waitUntil("the ride to start on chain", 60_000) { true.takeIf { rider.ride(id).status == RideStatus.Started } }
        Thread.sleep(6_000) // the app learns the ride started, and the map frames it
        val whole = routeDrawn(phone.picture("map"))
        assertTrue(whole > 200, "the ride under way is not drawn on the driver's map: $whole points of its colour")

        // The car drives six tenths of the ride, a tenth a second, while the rider's side notes every place it
        // is told the car is.
        val heard = HashSet<Coordinate>()
        val listening = Thread {
            while (!Thread.currentThread().isInterrupted) {
                rider.driverPosition(id)?.let { heard += Coordinate(it.latE7, it.lonE7) }
                try { Thread.sleep(150) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { start() }
        for (step in 1..6) {
            val at = Drive.along(line, step / 10.0)
            phone.locate(at.latE7 / 1e7, at.lonE7 / 1e7)
            Thread.sleep(1_000)
        }
        Thread.sleep(2_000)
        listening.interrupt()
        listening.join()

        // Six places in six seconds. One may be lost on the way; at one every four seconds only two would arrive.
        assertTrue(heard.size >= 5, "the rider heard the car in ${heard.size} places in six seconds, not one a second: $heard")
        // Six tenths of the road are behind the car, and no longer drawn.
        val left = routeDrawn(phone.picture("map"))
        assertTrue(left < whole * 0.7, "the road already driven is still on the map: $left points of the route's colour, of $whole before")
        assertTrue(left > 0, "the road ahead is gone from the map too")

        // The ride ends, and with it the rider's view of the driver (A10): where the car goes next is not told.
        rider.complete(id)
        Thread.sleep(8_000)
        val after = Drive.along(line, 0.9)
        phone.locate(after.latE7 / 1e7, after.lonE7 / 1e7)
        Thread.sleep(5_000)
        val last = rider.driverPosition(id)
        assertTrue(last == null || Coordinate(last.latE7, last.lonE7) != after, "the driver's phone went on telling the rider where it is after the ride ended")
    }

    @Test
    fun `on a ride the road the driver has driven comes off the rider's map`() {
        val driver = peer(DevAccount.DRIVER_2)
        phone.launch(fresh = true, role = "rider", pair = 2)
        pastUnratedRides()
        app.whereTo.shown()
        app.mapNotice.download()
        app.whereTo.ask()
        app.ask.shown()
        assertTrue(app.ask.search("Avenida Brasil").isNotEmpty(), "the pack's search finds nothing")
        app.ask.pick(0)
        app.ask.submit()
        app.offers.shown()
        val request = driver.awaitRequest(stack.client(DevAccount.RIDER_2).address)
        val offer = driver.offer(request, request.maxFare)
        app.offers.awaitOffer(driver.address)
        app.offers.pick(driver.address)
        app.confirm.shown()
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        val ride = driver.awaitRide(offer)
        val line = driver.awaitRoute(ride).line
        driver.accept(ride.id)
        app.onTheWay.awaitStatus("Accepted")
        driver.start(ride.id)
        app.onTrip.shown()
        Thread.sleep(4_000) // the map frames the trip
        val whole = routeDrawn(phone.picture("map"))
        assertTrue(whole > 200, "the trip is not drawn on the rider's map: $whole points of its colour")

        // The driver's phone says where it is a tenth of the way further each second.
        for (step in 1..6) {
            driver.sharePosition(ride.id, Drive.along(line, step / 10.0), onTheRide = true)
            Thread.sleep(1_000)
        }
        Thread.sleep(2_000)
        val left = routeDrawn(phone.picture("map"))
        assertTrue(left < whole * 0.7, "the road already driven is still on the rider's map: $left points of the route's colour, of $whole before")
        assertTrue(left > 0, "the road ahead is gone from the map too")
        app.onTrip.arrived()
    }

    private companion object {
        // The colour of a ride under way: the theme's `tertiary`.
        const val UNDER_WAY_RED = 0x1F
        const val UNDER_WAY_GREEN = 0x5F
        const val UNDER_WAY_BLUE = 0xD1
    }
}
