package org.klippelgo.tests.integrity

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.alike
import org.klippelgo.tests.shown
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // Before quoting, the driver sees what they would be left with: the price minus the fee and the tax.
        assertEquals(coin.shown(request.maxFare - fee - driver.taxOn(request.maxFare)), shown.receive)
        assertTrue("≈" in shown.costs, "the request does not say what the network costs: ${shown.costs}")
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
        val taxBefore = taxCollected(coin)
        val tax = driver.taxOn(fare)
        val id = rider.take(request, offer)

        // The rider locked it: the driver sees the ride, what is guaranteed, and the route once it checked out.
        val chosen = app.driverRide.awaitRide("chosen")
        assertEquals(id.toString(), chosen.ride)
        assertEquals(coin.toReais(fare).setScale(2), chosen.fare)
        assertEquals(Peer.short(request.rider), chosen.rider.lowercase())
        assertEquals(coin.shown(fare - fee - tax), app.driverRide.guaranteed())
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
        assertEquals(coin.shown(fare - fee - tax), end.amount)
        assertTrue(end.paid)
        assertEquals(coin.shown(tax), end.tax)
        assertTrue(end.infrastructure != null && !end.infrastructure.startsWith("≈"), "infrastructure cost at the end: ${end.infrastructure}")
        assertEquals(before + fare - fee - tax, driver.balanceOf(coin))
        assertEquals(taxBefore + tax, taxCollected(coin))
        app.rideEnd.stars(5)
        app.rideEnd.rate()
        waitUntil("the driver's rating to be on chain", 60_000) { true.takeIf { driver.hasRated(id) } }
        app.goOnline.shown()
    }

    @Test
    fun `the map stays where the driver put it while the car moves`() {
        val rider = peer(DevAccount.RIDER)
        freshDriver()
        app.goOnline.goOnline()
        val request = rider.ask(rider.route(Places.CATHEDRAL, Places.LAKE))
        app.request.awaitFrom(Peer.short(request.rider))
        app.request.send()
        val id = rider.take(request, rider.awaitOffer(request, appDriver))
        app.driverRide.awaitRouteChecked("chosen")
        app.driverRide.accept()
        app.driverRide.awaitRouteChecked("current")
        Thread.sleep(3_000) // the map frames the ride and the way to it

        // The driver looks closer at some part of the map: two taps on zoom, and a drag.
        app.map.zoomIn()
        Thread.sleep(800)
        app.map.zoomIn()
        Thread.sleep(800)
        app.map.drag(0.2, 0.15)
        Thread.sleep(3_000)
        val before = phone.picture("map")

        // The car moves on, a couple of hundred metres at a time, as it does when someone is driving. The way
        // to the pickup is drawn again from each new position.
        for (step in 1..5) {
            phone.locate((Places.CENTRE.latE7 - step * 18_000) / 1e7, Places.CENTRE.lonE7 / 1e7)
            Thread.sleep(3_000)
        }
        val after = phone.picture("map")

        // Same streets in the same places. Had the map framed the route again, hardly a pixel would match.
        val same = alike(before, after)
        assertTrue(same > 0.8, "the map moved or zoomed by itself while the car moved: only ${(same * 100).toInt()}% of it is as the driver left it")
        // And the rider did hear the car move, so the positions above were real ones to the app.
        val position = waitUntil("the rider to learn where the driver is", 60_000) { rider.driverPosition(id) }
        assertTrue(position.latE7 < Places.CENTRE.latE7 - 50_000, "the rider was told $position")
        rider.cancel(id)
    }

    /** How much of the map is the colour a request's regions are edged in. */
    private fun regionsDrawn(): Int {
        val map = phone.picture("map")
        var count = 0
        for (y in 0 until map.height step 2) {
            for (x in 0 until map.width step 2) {
                val p = map.getRGB(x, y)
                if (kotlin.math.abs(((p shr 16) and 0xFF) - 0xE8) <= 16 && kotlin.math.abs(((p shr 8) and 0xFF) - 0x59) <= 16 && kotlin.math.abs((p and 0xFF) - 0x0C) <= 16) count++
            }
        }
        return count
    }

    @Test
    fun `requests are listed, and the one picked is the one on the map and the one quoted`() {
        val ana = peer(DevAccount.RIDER)
        val bia = peer(DevAccount.RIDER_2)
        freshDriver()
        app.goOnline.goOnline()

        val first = ana.ask(ana.route(Places.CATHEDRAL, Places.LAKE))
        val second = bia.ask(bia.route(Places.CENTRE, Places.NORTH))
        // Both are listed under the driver's rules, and the driver is still at home: none is open, and the map
        // has not moved for them.
        waitUntil("both requests to be listed", 60_000) { true.takeIf { app.request.count() == 2 } }
        assertTrue(app.goOnline.visible())
        assertFalse(phone.screen().has("request"), "a request opened by itself")
        assertTrue(regionsDrawn() < 20, "the map shows a request's regions before any was picked")

        // Picking the second: it opens, with its own numbers, and its regions are what the map now shows.
        app.request.awaitFrom(Peer.short(second.rider))
        assertEquals(Peer.short(second.rider), app.request.info().rider)
        Thread.sleep(3_000)
        assertTrue(regionsDrawn() > 50, "the map does not show the regions of the request picked")

        // The quote goes to the rider of the one picked, and to nobody else.
        app.request.send()
        bia.awaitOffer(second, appDriver)
        assertTrue(ana.offers(first).isEmpty(), "the rider of a request that was not picked got an offer")

        // Picking the other one moves the map to its regions and the quote to it.
        app.request.awaitFrom(Peer.short(first.rider))
        assertEquals(Peer.short(first.rider), app.request.info().rider)
        Thread.sleep(3_000)
        assertTrue(regionsDrawn() > 50, "the map does not show the regions of the request picked")
        // Passing it takes it off the list; the other stays.
        app.request.pass()
        waitUntil("the passed request to leave the list") { true.takeIf { app.request.count() == 1 } }
        ana.withdraw(first)
        bia.withdraw(second)
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
