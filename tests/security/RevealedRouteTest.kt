package org.klippelgo.tests.security

import org.klippelgo.chain.DevAccount
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import java.math.BigDecimal
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A driver is told a route was checked only when it is the one the money is locked on (invariant A5). Here a
 * rider locks one quote and reveals another: a real route, drivable, in the regions the request named, but not
 * the one whose hash is in the escrow.
 *
 * Known limit P14 stands: the Accept button does not wait for the check. What this test holds the app to is
 * what the docs promise today, that it never calls such a route checked.
 */
class RevealedRouteTest : Scenario() {
    @Test
    fun `a route that is not the one locked in the escrow is never shown as checked`() {
        val stranger = stranger()
        val rider = peer(DevAccount.RIDER)
        freshDriver()
        app.goOnline.goOnline()

        val route = rider.route(Places.CENTRE, Places.LAKE)
        val request = rider.ask(route)
        app.request.awaitFrom(Peer.short(request.rider))
        app.request.send()
        val offer = rider.awaitOffer(request, appDriver)

        // The same route under a fare the driver never signed: it opens, it holds on the map, it starts and
        // ends in the right regions, and it hashes to something else.
        val other = offer.copy(fare = offer.fare + rider.coin.toBaseUnits(BigDecimal("3.00")))
        val lie = rider.quote(request, other, route, ByteArray(32).also { SecureRandom().nextBytes(it) })
        val id = rider.take(request, offer, reveal = lie)

        app.driverRide.awaitRide("chosen")
        // Long enough for the sealed route to arrive several times over and be looked at on every refresh.
        repeat(15) {
            assertEquals("waiting", app.driverRide.ride("chosen")?.route, "the app called a route checked that is not the one locked")
            Thread.sleep(1_000)
        }
        // The route did go out, addressed to this driver: "waiting" is the app's answer to it, not silence.
        assertTrue(
            stranger.heardJson("route").any { it.get("driver").asText() == appDriver },
            "no route was sent to the driver, so the app was never put to the test",
        )
        rider.cancel(id)

        // And the app does call a route checked when it is the one locked: the same trip again, revealed honestly.
        app.goOnline.shown()
        val again = rider.ask(route)
        app.request.awaitFrom(Peer.short(again.rider))
        app.request.send()
        val honest = rider.take(again, rider.awaitOffer(again, appDriver))
        app.driverRide.awaitRouteChecked("chosen")
        rider.cancel(honest)
    }
}
