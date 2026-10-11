package org.klippelgo.tests.integrity

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.core.Coordinate
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.drivers.App
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Three people, three phones, three copies of the real app: one rider and two drivers. No peer account stands
 * in for anyone. Everything one phone learns of another crosses the gossip network or the chain.
 *
 * Needs three emulators: `PHONES=3 tests/stack.sh up`, or `make e2e-three-phones`, which also runs these.
 * With `HEADLESS=0` all three show their windows, side by side. With fewer phones up, these tests are skipped.
 */
class ThreePhonesTest : Scenario() {
    private val rider get() = App(stack.phones[0])
    private val firstDriver get() = App(stack.phones[1])
    private val secondDriver get() = App(stack.phones[2])

    private val riderAccount by lazy { stack.client(DevAccount.RIDER) }
    private val firstAccount by lazy { stack.client(DevAccount.DRIVER) }
    private val secondAccount by lazy { stack.client(DevAccount.DRIVER_2) }

    @BeforeEach
    fun threePhones() {
        assumeTrue(stack.phonesUp() >= 3, "these tests need three phones: PHONES=3 tests/stack.sh up")
    }

    /** A rider on the first phone, a driver on each of the others, a few blocks apart, both online. */
    private fun everyoneReady() {
        // The rider and the first driver go through the app's own onboarding. The second driver is the second
        // seeded driver account, which only a scripted launch can pick: the create-account screen gives the first.
        freshRider()
        onboardDriver(firstDriver, "Bruno", Places.CENTRE)
        secondDriver.phone.launch(fresh = true, role = "driver", pair = 2)
        placeAndFetchMap(secondDriver, Places.CATHEDRAL)
        for (driver in listOf(firstDriver, secondDriver)) {
            assertNull(driver.goOnline.blocked(), "a driver's account may not accept rides; reset its ratings with: make reset-ratings")
            driver.goOnline.goOnline()
        }
    }

    private fun onboardDriver(app: App, name: String, at: Coordinate) {
        app.phone.launch(fresh = true)
        app.welcome.start()
        app.createAccount.fill(name, "driver", stack.rpcFromPhone)
        app.createAccount.submit()
        placeAndFetchMap(app, at)
    }

    private fun placeAndFetchMap(app: App, at: Coordinate) {
        app.phone.locate(at.latE7 / 1e7, at.lonE7 / 1e7)
        app.goOnline.shown()
        app.mapNotice.download()
        app.phone.awaitTag("go-online.score")
    }

    /** The rider searches a destination and sends the request out. Gives the price asked. */
    private fun riderAsks(): BigDecimal {
        rider.whereTo.ask()
        rider.ask.search("Avenida Brasil")
        rider.ask.pick(0)
        val price = rider.ask.price()
        rider.ask.submit()
        rider.offers.shown()
        return price
    }

    @Test
    fun `a ride from request to payment, with a second driver looking on`() {
        val coin = riderAccount.brz
        val (fee, riderBond, driverBond) = riderAccount.tokenConfig(coin)
        everyoneReady()
        val riderBefore = riderAccount.balanceOf(coin)
        val firstBefore = firstAccount.balanceOf(coin)
        val secondBefore = secondAccount.balanceOf(coin)

        val price = riderAsks()
        // Both drivers' phones show the request: the same rider, the same price.
        for (driver in listOf(firstDriver, secondDriver)) {
            driver.request.awaitFrom(Peer.short(appRider))
            assertEquals(price, driver.request.info().pays)
        }

        // Only the first driver quotes, at the rider's price.
        firstDriver.request.send()
        assertEquals(price, rider.offers.awaitOffer(firstAccount.address))
        assertEquals(setOf(firstAccount.address), rider.offers.offers().keys)

        rider.offers.pick(firstAccount.address)
        assertEquals((price + coin.toReais(riderBond)).setScale(2), rider.confirm.amounts().toLock)
        rider.confirm.lock()
        rider.onTheWay.awaitStatus("Open")

        // The chosen driver sees the ride and its route, checked on that phone's own map. The other sees the
        // request go away and is offered nothing.
        val chosen = firstDriver.driverRide.awaitRide("chosen")
        assertEquals(price, chosen.fare)
        firstDriver.driverRide.awaitRouteChecked("chosen")
        secondDriver.request.gone()
        assertTrue(secondDriver.goOnline.visible() && !secondDriver.driverRide.visible())
        val id = chosen.ride.toBigInteger()
        assertEquals(firstAccount.address, riderAccount.ride(id).driver)

        firstDriver.driverRide.accept()
        rider.onTheWay.awaitStatus("Accepted")
        // The driver's phone tells the rider's where it is, and the rider's shows it.
        rider.onTheWay.awaitDriverLive()

        firstDriver.driverRide.start()
        rider.onTrip.shown()
        rider.onTrip.arrived()
        rider.rideEnd.stars(5)
        rider.rideEnd.pay()

        // Paid: the driver's phone says what it received and asks for a rating.
        val end = waitUntil("the driver to be shown the payment", 60_000) { firstDriver.rideEnd.info().takeIf { it.paid } }
        assertEquals(id.toString(), end.ride)
        assertEquals((price - coin.toReais(fee)).setScale(2), end.amount)
        firstDriver.rideEnd.stars(5)
        firstDriver.rideEnd.rate()

        waitUntil("both ratings to be on chain", 60_000) { true.takeIf { riderAccount.hasRated(id) && firstAccount.hasRated(id) } }
        assertEquals(RideStatus.Completed, riderAccount.ride(id).status)
        val fare = coin.toBaseUnits(price)
        // The rider paid the fare and has the bond back; the driver nets the fare minus the fee and has theirs
        // back; the driver who only looked on is where they started.
        assertEquals(riderBefore - fare, riderAccount.balanceOf(coin))
        assertEquals(firstBefore + fare - fee, firstAccount.balanceOf(coin))
        assertEquals(secondBefore, secondAccount.balanceOf(coin))
        assertTrue(driverBond.signum() >= 0)

        rider.whereTo.shown()
        firstDriver.goOnline.shown()
    }

    @Test
    fun `both drivers offer, and the rider takes the cheaper one`() {
        val coin = riderAccount.brz
        everyoneReady()
        val riderBefore = riderAccount.balanceOf(coin)

        val price = riderAsks()
        for (driver in listOf(firstDriver, secondDriver)) driver.request.awaitFrom(Peer.short(appRider))

        // The first driver quotes the rider's price. The second goes two steps of R$ 0,50 under it.
        firstDriver.request.send()
        assertTrue(secondDriver.request.lower() && secondDriver.request.lower())
        val cheaper = price - BigDecimal("1.00")
        waitUntil("the second driver's quote to come down") { true.takeIf { secondDriver.request.price() == cheaper } }
        secondDriver.request.send()

        // Each driver's phone shows their own offer as sent.
        assertEquals(price, firstDriver.request.awaitYourOffer())
        assertEquals(cheaper, secondDriver.request.awaitYourOffer())

        // The rider sees both, each at the fare its driver signed, cheapest first.
        assertEquals(price, rider.offers.awaitOffer(firstAccount.address))
        assertEquals(cheaper, rider.offers.awaitOffer(secondAccount.address))
        val shown = rider.offers.offers()
        assertEquals(mapOf(secondAccount.address to cheaper, firstAccount.address to price), shown)
        assertEquals(listOf(secondAccount.address, firstAccount.address), shown.keys.toList())

        // The first driver thinks again and matches the cheaper price: the rider's screen follows.
        assertTrue(firstDriver.request.lower() && firstDriver.request.lower())
        firstDriver.request.send()
        waitUntil("the first driver's new offer to reach the rider", 30_000) { true.takeIf { rider.offers.offers()[firstAccount.address] == cheaper } }

        // The rider takes the second driver. What is locked is that driver's fare, for that driver.
        rider.offers.pick(secondAccount.address)
        assertEquals(cheaper, rider.confirm.amounts().fare)
        rider.confirm.lock()
        rider.onTheWay.awaitStatus("Open")

        val chosen = secondDriver.driverRide.awaitRide("chosen")
        assertEquals(cheaper, chosen.fare)
        secondDriver.driverRide.awaitRouteChecked("chosen")
        val ride = riderAccount.ride(chosen.ride.toBigInteger())
        assertEquals(secondAccount.address, ride.driver)
        assertEquals(coin.toBaseUnits(cheaper), ride.fare)

        // The driver who was not picked sees the request go, and gets no ride and no route.
        firstDriver.request.gone()
        assertFalse(firstDriver.driverRide.visible())
        assertTrue(firstAccount.underWay().none { it.rider == appRider })

        // The rider changes their mind before the driver answers: everything comes back, and the driver's
        // phone lets go of the ride.
        rider.onTheWay.cancel()
        rider.whereTo.shown()
        secondDriver.driverRide.gone()
        assertEquals(RideStatus.Cancelled, riderAccount.ride(ride.id).status)
        assertEquals(riderBefore, riderAccount.balanceOf(coin))
    }
}
