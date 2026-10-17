package org.klippelgo.tests.security

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.shown
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a change of the rules by Instituto Klippel's team may and may not do to a person using the app. A ride
 * keeps the terms it opened with (contracts C5), the chain refuses what the network stopped even when the phones
 * still match it (C1, C15's other side), and a change still waiting for a second approval changes nothing the
 * app shows or locks (C14, apps A14). Each test ends with the honest path going through.
 */
class RuleChangesTest : Scenario() {
    /** The rider asks for a ride to Avenida Brasil at the recommended price, and is on the offers screen. */
    private fun askForARide() {
        app.whereTo.ask()
        app.ask.shown()
        assertTrue(app.ask.search("Avenida Brasil").isNotEmpty())
        app.ask.pick(0)
        app.ask.submit()
        app.offers.shown()
    }

    @Test
    fun `a ride under way keeps the tax it opened with when Finanças changes it`() {
        val gov = governance()
        val driver = peer(DevAccount.DRIVER)
        val rider = stack.client(DevAccount.RIDER)
        val coin = rider.brz
        val (fee, _, _) = rider.tokenConfig(coin)
        freshRider()

        askForARide()
        val request = driver.awaitRequest(appRider)
        val fare = request.maxFare
        val offer = driver.offer(request, fare)
        app.offers.awaitOffer(driver.address)
        app.offers.pick(driver.address)
        app.confirm.shown()
        val rateAtOpen = gov.taxBps()
        val taxAtOpen = fare * rateAtOpen.toBigInteger() / 10_000.toBigInteger()
        assertEquals(coin.shown(taxAtOpen), app.confirm.amounts().tax)

        val driverBefore = driver.balance()
        val taxBefore = taxCollected(coin)
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        val ride = driver.awaitRide(offer)
        assertEquals(taxAtOpen, ride.tax)
        driver.awaitRoute(ride)
        driver.accept(ride.id)
        app.onTheWay.awaitStatus("Accepted")
        driver.start(ride.id)
        app.onTrip.shown()

        // Mid-ride, Finanças halves the tax and sends it to the same collector.
        val halved = BigDecimal(rateAtOpen).movePointLeft(2).divide(BigDecimal(2), 2, RoundingMode.DOWN)
        gov.tax(halved)
        assertEquals(halved.movePointRight(2).toInt(), gov.taxBps())

        // The ride ends on the rate it opened with: on the rider's screen, in the collector's balance, and in what
        // the driver nets.
        app.onTrip.arrived()
        assertEquals(coin.shown(taxAtOpen), app.rideEnd.info().tax)
        app.rideEnd.stars(5)
        app.rideEnd.pay()
        waitUntil("the ride to complete on chain", 60_000) { true.takeIf { driver.ride(ride.id).status == RideStatus.Completed } }
        assertEquals(taxBefore + taxAtOpen, taxCollected(coin))
        assertEquals(driverBefore + fare - fee - taxAtOpen, driver.balance())

        // And the app did hear the new rate: the next ride is priced on it.
        app.whereTo.shown()
        app.tabs.open("rules")
        val words = halved.stripTrailingZeros().toPlainString().replace('.', ',')
        waitUntil("the rules screen to show the new tax, $words%", 60_000) { true.takeIf { words in app.rules.tax() } }
    }

    @Test
    fun `the chain refuses a ride the network stopped, even when the phones still match it`() {
        val gov = governance()
        val driver = peer(DevAccount.DRIVER)
        val rider = stack.client(DevAccount.RIDER)
        val coin = rider.brz
        freshRider()

        // Both phones agreed on a ride while rides were allowed: the request was heard, the offer made.
        askForARide()
        val request = driver.awaitRequest(appRider)
        val offer = driver.offer(request, request.maxFare)
        app.offers.awaitOffer(driver.address)
        app.offers.pick(driver.address)
        app.confirm.shown()
        val balanceBefore = rider.balanceOf(coin)
        val ridesBefore = rider.ridesAsRider().size

        // Then Segurança stops new rides. The phones knew nothing of it when they matched.
        gov.stopRides()
        app.confirm.lock()
        val refused = waitUntil("the app to say the lock was refused", 60_000) { app.error.message() }
        assertTrue(refused.isNotBlank())
        assertEquals(balanceBefore, rider.balanceOf(coin), "money left the rider for a ride the network stopped")
        assertEquals(ridesBefore, rider.ridesAsRider().size, "a ride opened while new rides were stopped")
        app.error.dismiss()

        // Once the board resumes rides, the same offer locks.
        gov.resumeRides()
        app.confirm.shown()
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        assertEquals(RideStatus.Open, driver.awaitRide(offer).status)
    }

    @Test
    fun `a tax change waiting for a second approval changes nothing the app shows`() {
        val gov = governance()
        // Finanças gets a second member, and from now on the tax needs both of them.
        val colleague = gov.colleague("finance")
        gov.needApprovals("setTax(uint16,address)", 2)
        freshRider()
        app.tabs.open("rules")
        val rate = gov.taxBps()
        val shown = waitUntil("the rules screen to show the tax") { app.rules.tax().takeIf { it.isNotBlank() } }

        val target = BigDecimal(rate).movePointLeft(2).divide(BigDecimal(2), 2, RoundingMode.DOWN)
        val pending = gov.taxPending(target, colleague)
        // Longer than the app's ten seconds between reads of the tax: nothing moves, on screen or on chain.
        Thread.sleep(15_000)
        assertEquals(shown, app.rules.tax(), "the app showed a tax change that one member asked for alone")
        assertEquals(rate, gov.taxBps(), "the tax changed with one approval of two")

        // The second member approves, which runs it: the app follows.
        gov.approve(colleague, pending)
        assertEquals(target.movePointRight(2).toInt(), gov.taxBps())
        val words = target.stripTrailingZeros().toPlainString().replace('.', ',')
        waitUntil("the rules screen to show the new tax, $words%", 60_000) { true.takeIf { words in app.rules.tax() } }
    }
}
