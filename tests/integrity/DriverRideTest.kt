package org.klippelgo.tests.integrity

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A person driving with the app, for a rider who is a peer account with a real route on the same map. */
class DriverRideTest : Scenario() {
    @Test
    fun `a ride from going online to being paid`() {
        val rider = peer(DevAccount.RIDER)
        val driver = stack.client(DevAccount.DRIVER)
        val coin = driver.brz
        val (fee, _, driverBond) = driver.tokenConfig(coin)
        freshDriver()
        assertNull(app.goOnline.blocked(), "the driver's account may not accept rides; reset its ratings with: make reset-ratings")

        app.goOnline.goOnline()
        val route = rider.route(Places.CENTRE, Places.LAKE)
        val request = rider.ask(route)

        // The driver is told who asks, how far, how long and how much: not where to the door.
        app.request.awaitFrom(Peer.short(request.rider))
        val shown = app.request.info()
        assertEquals(coin.toReais(request.maxFare).setScale(2), shown.pays)
        assertEquals(shown.pays, shown.price)
        assertTrue(shown.trip.contains("km") || shown.trip.contains(" m"), shown.trip)
        // Two neighbourhood names, from this phone's own map pack.
        assertNotNull(shown.pickupRegion)
        assertNotNull(shown.dropoffRegion)

        // One step under the rider's price.
        assertTrue(app.request.lower())
        val fare = request.maxFare - coin.toBaseUnits(BigDecimal("0.50"))
        waitUntil("the quote to go down a step") { true.takeIf { app.request.price() == coin.toReais(fare).setScale(2) } }
        app.request.send()
        val offer = rider.awaitOffer(request, appDriver)
        assertEquals(fare, offer.fare)

        val before = driver.balanceOf(coin)
        val id = rider.take(request, offer)

        // The rider locked it: the driver sees the ride, what is guaranteed, and the route once it checked out.
        val chosen = app.driverRide.awaitRide("chosen")
        assertEquals(id.toString(), chosen.ride)
        assertEquals(coin.toReais(fare).setScale(2), chosen.fare)
        assertEquals(Peer.short(request.rider), chosen.rider.lowercase())
        assertEquals(coin.toReais(fare - fee).setScale(2), app.driverRide.guaranteed())
        app.driverRide.awaitRouteChecked("chosen")

        app.driverRide.accept()
        waitUntil("the ride to be accepted on chain", 60_000) { true.takeIf { rider.ride(id).status == RideStatus.Accepted } }
        assertEquals(before - driverBond, driver.balanceOf(coin))
        app.driverRide.awaitRouteChecked("current")

        // On this rider's ride now, the phone tells them where it is. It was placed in the centre of Cascavel.
        val position = waitUntil("the rider to learn where the driver is", 60_000) { rider.driverPosition(id) }
        assertTrue(abs(position.latE7 - Places.CENTRE.latE7) < 5_000 && abs(position.lonE7 - Places.CENTRE.lonE7) < 5_000, "position $position")

        app.driverRide.start()
        waitUntil("the ride to start on chain", 60_000) { true.takeIf { rider.ride(id).status == RideStatus.Started } }

        rider.complete(id)
        rider.rate(id, 5)
        // Paid: the screen says what the driver received, and asks for the rating.
        val end = app.rideEnd.info()
        assertEquals(id.toString(), end.ride)
        assertEquals(coin.toReais(fare - fee).setScale(2), end.amount)
        assertTrue(end.paid)
        assertEquals(before + fare - fee, driver.balanceOf(coin))
        app.rideEnd.stars(5)
        app.rideEnd.rate()
        waitUntil("the driver's rating to be on chain", 60_000) { true.takeIf { driver.hasRated(id) } }
        app.goOnline.shown()
    }

    @Test
    fun `a driver who declines gives the rider everything back`() {
        val rider = peer(DevAccount.RIDER)
        freshDriver()
        app.goOnline.goOnline()
        val before = rider.balance()

        val request = rider.ask(rider.route(Places.CATHEDRAL, Places.NORTH))
        app.request.awaitFrom(Peer.short(request.rider))
        app.request.send()
        val id = rider.take(request, rider.awaitOffer(request, appDriver))
        app.driverRide.awaitRide("chosen")

        app.driverRide.decline()
        waitUntil("the ride to be cancelled on chain", 60_000) { true.takeIf { rider.ride(id).status == RideStatus.Cancelled } }
        assertEquals(before, rider.balance())
        app.driverRide.gone()
    }

    @Test
    fun `the driver's own rules hide requests that pay too little per km`() {
        val rider = peer(DevAccount.RIDER)
        freshDriver()
        // A floor of R$ 10,00 per km: far more than any request in the fare guide's range pays.
        repeat(40) { app.autoSkip.raisePerKm() }
        assertEquals(BigDecimal("10.00"), org.klippelgo.tests.reais(app.autoSkip.perKm()))
        app.goOnline.goOnline()

        val request = rider.ask(rider.route(Places.CENTRE, Places.LAKE))
        waitUntil("the app to say it is hiding a request", 30_000) { true.takeIf { app.autoSkip.skipped() >= 1 } }
        assertTrue(app.goOnline.visible())

        // Lowering the floor step by step: once it is under what the request pays per km, the request is offered.
        var steps = 0
        while (!app.request.visible()) {
            check(steps++ < 40) { "the request never showed, even with the rule off" }
            runCatching { app.autoSkip.lowerPerKm() }
        }
        app.request.awaitFrom(Peer.short(request.rider))
    }
}
