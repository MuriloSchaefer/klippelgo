package org.klippelgo.tests.integrity

import org.klippelgo.chain.DevAccount
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instituto Klippel's team changes a rule with `klippel-gov`, through the team council, and the app on the
 * phone follows while it runs: no restart, no new install. Each test puts back what it changed.
 */
class GovernanceTest : Scenario() {
    @Test
    fun `a tariff Finanças changes reaches a running app, and the next request is priced on it`() {
        val gov = governance()
        freshRider()
        app.tabs.open("rules")
        val was = app.rules.tariff().perKm
        val now = was + BigDecimal("0.37")

        gov.perKm(now, was)
        // The app reads the tariff again every half minute: the rules screen follows on its own.
        waitUntil("the rules screen to show R$ $now per km", 90_000) {
            true.takeIf { app.rules.tariff().perKm.compareTo(now) == 0 }
        }
        val tariff = stack.client(DevAccount.E2E_RIDER_2).tariff
        assertEquals(now.movePointRight(2).toLong(), tariff.perKmCentavos)

        // A driver who came online after the change, so that both phones price on the tariff now in force.
        val driver = peer(DevAccount.E2E_DRIVER)
        app.tabs.open("ride")
        app.whereTo.ask()
        app.ask.shown()
        assertTrue(app.ask.search("Avenida Brasil").isNotEmpty())
        app.ask.pick(0)
        val price = app.ask.price()
        assertEquals(app.ask.recommended(), price)
        app.ask.submit()
        app.offers.shown()

        val request = driver.awaitRequest(appRider)
        val coin = driver.coin
        assertEquals(coin.toBaseUnits(price), request.maxFare)
        assertEquals(coin.fromCentavos(tariff.recommended(request.route.distanceM, request.route.durationS)), request.maxFare)
    }

    @Test
    fun `Segurança stops new rides at once, and the board's resume brings them back`() {
        val gov = governance()
        freshDriver()
        app.goOnline.awaitBlockers()
        assertTrue(chain.canAcceptRides(appDriver))

        gov.stopRides()
        // Nobody may accept or open a ride, and the driver is told it is the network's rule, not theirs.
        app.goOnline.awaitBlockers("disabled")
        assertFalse(chain.canAcceptRides(appDriver))
        assertFalse(chain.canOpenRides(appRider))

        gov.resumeRides()
        app.goOnline.awaitBlockers()
        assertTrue(chain.canAcceptRides(appDriver))
        assertTrue(chain.canOpenRides(appRider))
    }

    @Test
    fun `a driver Segurança blocks is told so, and can accept again once unblocked`() {
        val gov = governance()
        val rider = peer(DevAccount.E2E_RIDER_2)
        freshDriver()
        app.goOnline.awaitBlockers()

        gov.block(appDriver)
        app.goOnline.awaitBlockers("account")
        assertFalse(chain.canAcceptRides(appDriver))
        // Only the driver is stopped: the rest of the network goes on.
        assertTrue(chain.canOpenRides(rider.address))

        gov.unblock(appDriver)
        app.goOnline.awaitBlockers()
        assertTrue(chain.canAcceptRides(appDriver))

        // Back to work: online, a rider's request reaches the screen.
        app.goOnline.goOnline()
        val request = rider.ask(rider.route(org.klippelgo.tests.Places.CENTRE, org.klippelgo.tests.Places.LAKE))
        assertTrue(app.request.awaitFrom(org.klippelgo.tests.Peer.short(request.rider)))
    }
}
