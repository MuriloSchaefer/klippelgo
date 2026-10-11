package org.klippelgo.tests.integrity

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.core.Coordinate
import org.klippelgo.tests.Scenario
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
        assertEquals(coin.toReais(fare - fee).setScale(2), shown.toDriver)
        assertEquals(coin.toReais(riderBond).setScale(2), shown.bond)
        assertEquals(coin.toReais(fare + riderBond).setScale(2), shown.toLock)

        val riderBefore = rider.balanceOf(coin)
        val driverBefore = driver.balance()
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")

        // On chain: this driver, this fare, and exactly the quote the driver signed.
        val ride = driver.awaitRide(offer)
        assertEquals(appRider, ride.rider)
        assertEquals(fare, ride.fare)
        assertEquals(fee, ride.fee)
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
        app.rideEnd.stars(5)
        app.rideEnd.pay()

        waitUntil("the ride to complete on chain", 60_000) { true.takeIf { driver.ride(ride.id).status == RideStatus.Completed } }
        // The driver nets the fare minus the fee for Instituto Klippel; the rider's bond came back.
        assertEquals(driverBefore + fare - fee, driver.balance())
        assertEquals(riderBefore - fare, rider.balanceOf(coin))
        waitUntil("the rider's rating to be on chain", 60_000) { true.takeIf { rider.hasRated(ride.id) } }
        driver.rate(ride.id, 5)

        // Back home, and the ride is in the wallet's history as completed and rated.
        app.whereTo.shown()
        app.tabs.open("wallet")
        val past = waitUntil("the ride to show in the history") { app.wallet.history().firstOrNull { it.ride == ride.id.toString() && it.rated } }
        assertEquals("Completed", past.status)
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
