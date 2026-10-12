package org.klippelgo.tests.integrity

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.core.Coordinate
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.alike
import org.klippelgo.tests.shown
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A person riding with the app, against a driver who is a peer account. */
class RiderRideTest : Scenario() {
    /** Searches a destination, measures the route and sends the request out. Gives the price asked. */
    private fun askForARide(query: String = "Avenida Brasil"): BigDecimal {
        app.whereTo.ask()
        app.ask.shown()
        assertTrue(app.ask.search(query).isNotEmpty(), "the pack's search finds nothing for \"$query\"")
        app.ask.pick(0)
        // The price starts at what the fare guide recommends for the route.
        assertEquals(app.ask.recommended(), app.ask.price())
        val price = app.ask.price()
        app.ask.submit()
        app.offers.shown()
        return price
    }

    @Test
    fun `a ride from search to payment`() {
        val driver = peer(DevAccount.DRIVER)
        val rider = stack.client(DevAccount.RIDER)
        val coin = rider.brz
        val (fee, riderBond, _) = rider.tokenConfig(coin)
        freshRider()

        val price = askForARide()
        // The driver's phone hears the request: the price asked and a length, and nothing about where.
        val request = driver.awaitRequest(appRider)
        assertEquals(coin.toBaseUnits(price), request.maxFare)
        assertEquals(app.offers.price(), price)
        assertEquals(coin.fromCentavos(rider.tariff.recommended(request.route.distanceM, request.route.durationS)), request.maxFare)

        // The driver quotes R$ 0,50 under the rider's price.
        val fare = request.maxFare - coin.toBaseUnits(BigDecimal("0.50"))
        val offer = driver.offer(request, fare)
        assertEquals(coin.toReais(fare).setScale(2), app.offers.awaitOffer(driver.address))
        assertEquals(setOf(driver.address), app.offers.offers().keys)

        // The confirmation shows what is about to be locked: the same numbers the escrow will hold (A14).
        app.offers.pick(driver.address)
        app.confirm.shown()
        val shown = app.confirm.amounts()
        assertEquals(coin.toReais(fare).setScale(2), shown.fare)
        assertEquals(coin.toReais(fee).setScale(2), shown.fee)
        // The government's tax is a share of the fare, inside it: the driver's part is what is left.
        val tax = rider.taxOn(fare)
        assertTrue(tax.signum() > 0, "the escrow charges no tax; the deployment is older than the code")
        assertEquals(coin.shown(tax), shown.tax)
        assertEquals(coin.shown(fare - fee - tax), shown.toDriver)
        // What the network will cost is shown too, as an estimate, and is no part of what is locked.
        assertTrue(shown.infrastructure?.startsWith("≈") == true, "no infrastructure cost on the confirmation: ${shown.infrastructure}")
        assertEquals(coin.toReais(riderBond).setScale(2), shown.bond)
        assertEquals(coin.toReais(fare + riderBond).setScale(2), shown.toLock)

        val riderBefore = rider.balanceOf(coin)
        val driverBefore = driver.balance()
        val taxBefore = taxCollected(coin)
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")

        // On chain: this driver, this fare, and exactly the quote the driver signed.
        val ride = driver.awaitRide(offer)
        assertEquals(appRider, ride.rider)
        assertEquals(fare, ride.fare)
        assertEquals(fee, ride.fee)
        assertEquals(tax, ride.tax)
        assertEquals(RideStatus.Open, ride.status)
        assertEquals(riderBefore - fare - riderBond, rider.balanceOf(coin))
        assertEquals(coin.toReais(fare + riderBond).setScale(2), app.onTheWay.locked())

        // The route arrives sealed to the driver, holds on the driver's own copy of the map, hashes to the
        // quote locked in the escrow, and is as long as the request said.
        val route = driver.awaitRoute(ride)
        assertEquals(request.route.distanceM, org.klippelgo.core.coarseRoute(route.distanceM, route.durationS).distanceM.toInt())

        driver.accept(ride.id)
        app.onTheWay.awaitStatus("Accepted")
        // Before the driver says where they are, the rider is shown no position.
        assertFalse(app.onTheWay.driverLive())
        waitUntil("the rider to see the driver's position", 60_000) {
            driver.sharePosition(ride.id, Coordinate(route.pickup.latE7 + 20_000, route.pickup.lonE7))
            true.takeIf { app.onTheWay.driverLive() }
        }

        driver.start(ride.id)
        app.onTrip.shown()
        assertEquals(coin.toReais(fare).setScale(2), app.onTrip.fare())

        app.onTrip.arrived()
        val end = app.rideEnd.info()
        assertEquals(ride.id.toString(), end.ride)
        assertEquals(coin.toReais(fare).setScale(2), end.amount)
        assertFalse(end.paid)
        assertEquals(coin.shown(tax), end.tax)
        // This phone has paid for the lock by now, so the network's cost is no longer an estimate.
        assertTrue(end.infrastructure != null && !end.infrastructure.startsWith("≈"), "infrastructure cost at the end: ${end.infrastructure}")
        assertTrue(rider.infrastructureCost(rider.gasPrice() * org.klippelgo.chain.KlippelGoClient.RIDER_RIDE_GAS).signum() > 0)
        // The rider rode along: by now their phone is where the route ends.
        val arrival = route.line.last()
        phone.locate(arrival.latE7 / 1e7, arrival.lonE7 / 1e7)
        app.rideEnd.stars(5)
        app.rideEnd.pay()

        waitUntil("the ride to complete on chain", 60_000) { true.takeIf { driver.ride(ride.id).status == RideStatus.Completed } }
        // The driver nets the fare minus the fee for Instituto Klippel and the tax, which went to its
        // collector; the rider paid the fare and no more, and the bond came back.
        assertEquals(driverBefore + fare - fee - tax, driver.balance())
        assertEquals(taxBefore + tax, taxCollected(coin))
        assertEquals(riderBefore - fare, rider.balanceOf(coin))
        waitUntil("the rider's rating to be on chain", 60_000) { true.takeIf { rider.hasRated(ride.id) } }
        driver.rate(ride.id, 5)

        // Back home, and the next ride starts where this one ended: the pin followed the phone there.
        app.whereTo.shown()
        waitUntil("the pickup pin to be where the ride ended, at ${named(arrival)}") { true.takeIf { app.whereTo.pickup() == named(arrival) } }
        // The ride is in the wallet's history as completed and rated.
        app.tabs.open("wallet")
        val past = waitUntil("the ride to show in the history") { app.wallet.history().firstOrNull { it.ride == ride.id.toString() && it.rated } }
        assertEquals("Completed", past.status)
    }

    @Test
    fun `the map stays where the rider put it while the driver comes`() {
        val driver = peer(DevAccount.DRIVER)
        freshRider()
        askForARide()
        val request = driver.awaitRequest(appRider)
        val offer = driver.offer(request, request.maxFare)
        app.offers.awaitOffer(driver.address)
        app.offers.pick(driver.address)
        app.confirm.shown()
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        val ride = driver.awaitRide(offer)
        val route = driver.awaitRoute(ride)
        driver.accept(ride.id)
        app.onTheWay.awaitStatus("Accepted")
        fun driverAt(step: Int) = Coordinate(route.pickup.latE7 + 60_000 - step * 10_000, route.pickup.lonE7)
        waitUntil("the rider to see the driver's position", 60_000) {
            driver.sharePosition(ride.id, driverAt(0))
            true.takeIf { app.onTheWay.driverLive() }
        }
        Thread.sleep(3_000) // the map frames the trip and the driver

        // The rider looks closer at where they will be picked up.
        app.map.zoomIn()
        Thread.sleep(800)
        app.map.zoomIn()
        Thread.sleep(800)
        app.map.drag(0.2, 0.15)
        Thread.sleep(3_000)
        val before = phone.picture("map")

        // The driver comes closer, a hundred metres at a time.
        for (step in 1..5) {
            driver.sharePosition(ride.id, driverAt(step))
            Thread.sleep(3_000)
        }
        val after = phone.picture("map")

        val same = alike(before, after)
        assertTrue(same > 0.8, "the map moved or zoomed by itself while the driver came: only ${(same * 100).toInt()}% of it is as the rider left it")
        driver.cancel(ride.id)
    }

    @Test
    fun `the pickup pin follows the phone, and a place off the map does not move it`() {
        freshRider()
        val lake = named(Places.LAKE)
        assertTrue(lake != named(Places.CENTRE), "the two spots have the same name on this map: the test would show nothing")
        phone.locate(Places.LAKE.latE7 / 1e7, Places.LAKE.lonE7 / 1e7)
        waitUntil("the pickup pin to follow the phone to $lake") { true.takeIf { app.whereTo.pickup() == lake } }

        // A phone that says it is somewhere the map pack does not cover: there is no pickup there.
        phone.locate(0.0, 0.0)
        Thread.sleep(5_000)
        assertEquals(lake, app.whereTo.pickup())
        // And back on the map it is followed again.
        phone.locate(Places.CENTRE.latE7 / 1e7, Places.CENTRE.lonE7 / 1e7)
        val centre = named(Places.CENTRE)
        waitUntil("the pickup pin to follow the phone back") { true.takeIf { app.whereTo.pickup() == centre } }

        // The rider moves the map under the pin: from then on the pin is theirs, wherever the phone goes.
        app.map.drag(0.35, 0.3)
        val placed = waitUntil("the pin to name the spot the rider put it on") { app.whereTo.pickup().takeIf { it != centre } }
        phone.locate(Places.LAKE.latE7 / 1e7, Places.LAKE.lonE7 / 1e7)
        Thread.sleep(5_000)
        assertEquals(placed, app.whereTo.pickup())

        // Once the rider is asking, the trip is from where the pin was: the phone moving changes neither the
        // pickup nor the route measured from it, before the request goes out or after.
        app.whereTo.ask()
        app.ask.shown()
        app.ask.search("Avenida Brasil")
        val measured = app.ask.pick(0)
        phone.locate(Places.CENTRE.latE7 / 1e7, Places.CENTRE.lonE7 / 1e7)
        Thread.sleep(5_000)
        assertEquals(placed, app.ask.pickup())
        assertEquals(measured, app.ask.route())
        app.ask.submit()
        app.offers.shown()
        phone.locate(Places.CATHEDRAL.latE7 / 1e7, Places.CATHEDRAL.lonE7 / 1e7)
        Thread.sleep(5_000)
        assertEquals(placed, app.offers.pickup())
    }

    @Test
    fun `the trip is drawn once a destination is picked, and the pickup can be changed by name`() {
        val driver = peer(DevAccount.DRIVER)
        val coin = chain.brz
        freshRider()
        val pinned = app.whereTo.pickup()

        app.whereTo.ask()
        app.ask.shown()
        // The pickup starts as the spot under the pin on the home map. No trip yet, so no map of it.
        assertEquals(pinned, app.ask.pickup())
        assertFalse(app.ask.mapShown())

        app.ask.search("Avenida Brasil")
        val fromThePin = app.ask.pick(0)
        assertTrue(app.ask.mapShown(), "the route is measured but the screen does not draw it")

        // Looking for another pickup and thinking better of it changes nothing.
        app.ask.searchPickup("Rodoviária")
        app.ask.keepPickup()
        assertEquals(pinned, app.ask.pickup())
        assertEquals(fromThePin, app.ask.route())

        // Picking one moves the pickup there and measures the route again from it.
        val found = app.ask.searchPickup("Rodoviária")
        val pickup = app.ask.pickPickup(0)
        assertTrue(found[0].startsWith(pickup), "picked \"${found[0]}\" and the pickup reads \"$pickup\"")
        val fromThePlace = waitUntil("the route to be measured again from the new pickup", 60_000) { app.ask.route().takeIf { it != fromThePin } }
        assertTrue(app.ask.mapShown())
        val destination = app.ask.destination()

        app.ask.submit()
        app.offers.shown()
        // While the offers come in, the trip stays on screen: drawn, and named at both ends.
        assertTrue(app.offers.mapShown())
        assertEquals(pickup, app.offers.pickup())
        assertEquals(destination, app.offers.destination())
        // The request states the trip's length rounded, so drivers cannot tell one address from the next.
        assertTrue(app.offers.trip().isNotBlank() && fromThePlace.isNotBlank())

        // With the request out, moving the pickup means another route, and a request commits to its route. The
        // app asks first, and until the rider says yes nothing changes: the driver still hears the same request.
        val first = driver.awaitRequest(appRider)
        val question = app.offers.movePickup("Catedral")
        assertTrue(question.isNotBlank())
        app.offers.keepRequest()
        Thread.sleep(4_000)
        assertEquals(pickup, app.offers.pickup())
        assertEquals(listOf(first.id), driver.requests().filter { it.rider == appRider }.map { it.id })

        // Said yes: the old request is withdrawn and a new one goes out, for the route from the new pickup, at
        // the price the fare guide recommends for that route.
        app.offers.movePickup("Catedral")
        app.offers.confirmMove()
        val second = waitUntil("the trip to be asked again from the new pickup", 60_000) {
            driver.requests().filter { it.rider == appRider }.takeIf { all -> all.none { it.id == first.id } }?.firstOrNull()
        }
        assertTrue(second.route.routeHash != first.route.routeHash)
        assertEquals(coin.fromCentavos(chain.tariff.recommended(second.route.distanceM, second.route.durationS)), second.maxFare)
        val moved = waitUntil("the screen to name the new pickup") { app.offers.pickup().takeIf { it != pickup } }
        waitUntil("the screen to show the new request's price") { true.takeIf { app.offers.price() == coin.toReais(second.maxFare).setScale(2) } }
        assertTrue(app.offers.mapShown())

        // What is locked is the route from the place picked last: the driver opens it and finds it starts there.
        val offer = driver.offer(second)
        app.offers.awaitOffer(driver.address)
        app.offers.pick(driver.address)
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        val ride = driver.awaitRide(offer)
        val route = driver.awaitRoute(ride)
        val place = stack.pack.search("Catedral", null, 20u).firstOrNull { it.name == moved } ?: error("the pack has no place named $moved")
        val metres = Math.hypot((route.pickup.latE7 - place.at.latE7) * 0.0111, (route.pickup.lonE7 - place.at.lonE7) * 0.0101)
        assertTrue(metres < 400, "the locked route starts $metres m from the pickup the rider picked")

        app.onTheWay.cancel()
        app.whereTo.shown()
        // Back home, the pin is where the rider put it by name.
        assertEquals(moved, app.whereTo.pickup())
    }

    @Test
    fun `a rider who cancels before the driver answers gets everything back`() {
        val driver = peer(DevAccount.DRIVER)
        val rider = stack.client(DevAccount.RIDER)
        freshRider()
        val before = rider.balanceOf(rider.brz)

        askForARide()
        val offer = driver.offer(driver.awaitRequest(appRider))
        app.offers.awaitOffer(driver.address)
        app.offers.pick(driver.address)
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        val ride = driver.awaitRide(offer)

        app.onTheWay.cancel()
        app.whereTo.shown()
        assertEquals(RideStatus.Cancelled, rider.ride(ride.id).status)
        assertEquals(before, rider.balanceOf(rider.brz))
    }

    @Test
    fun `when the driver declines, the same trip goes out again to the others`() {
        val first = peer(DevAccount.DRIVER)
        val second = peer(DevAccount.DRIVER_2)
        freshRider()

        val price = askForARide()
        val request = first.awaitRequest(appRider)
        val offer = first.offer(request)
        app.offers.awaitOffer(first.address)
        app.offers.pick(first.address)
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        val ride = first.awaitRide(offer)

        first.cancel(ride.id)

        // The rider is back among the offers, told why, with a new request for the same trip at the same price.
        phone.awaitTag("offers.asked-again", 60_000)
        assertTrue(app.offers.askedAgain())
        val again = waitUntil("the trip to be asked again", 30_000) { second.requests().firstOrNull { it.rider == appRider && it.id != request.id } }
        assertEquals(request.maxFare, again.maxFare)
        assertEquals(request.route.distanceM, again.route.distanceM)
        // A new salt each time: the same trip asked twice shows as two unrelated route hashes.
        assertTrue(again.route.routeHash != request.route.routeHash)
        assertEquals(price, app.offers.price())

        second.offer(again)
        app.offers.awaitOffer(second.address)
    }
}
