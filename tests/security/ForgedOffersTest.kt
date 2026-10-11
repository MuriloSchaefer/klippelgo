package org.klippelgo.tests.security

import org.klippelgo.chain.DevAccount
import org.klippelgo.tests.Scenario
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rider is shown an offer only if it passed the checks of the message table in `apps/docs/security.md`
 * (invariants A1, A3, A6), and only from a driver the policy lets accept rides. A stranger overhears the
 * app's request, as anyone can, and answers it with offers that each break one rule.
 */
class ForgedOffersTest : Scenario() {
    @Test
    fun `a rider is shown only offers that pass every check`() {
        val stranger = stranger()
        val honest = peer(DevAccount.DRIVER)
        val impersonated = stack.client(DevAccount.DRIVER_2)
        freshRider()
        app.whereTo.ask()
        app.ask.search("Avenida Brasil")
        app.ask.pick(0)
        app.ask.submit()
        app.offers.shown()

        val request = stranger.overhearRequest(appRider)
        val coin = impersonated.coinFor(request.token)
        val tariff = impersonated.tariff
        val ceiling = coin.fromCentavos(tariff.driverCeiling(request.route.distanceM, request.route.durationS))
        val floor = tariff.driverFloor(request.maxFare)
        val step = coin.toBaseUnits(BigDecimal("0.50"))
        val nobody = stranger.newKey()
        val elsewhere = request.copy(id = request.id.xor(1.toBigInteger()))

        // An offer the stranger writes with the driver's real key, breaking no rule. It is sent last, as the
        // control. Before that, copies of it with one term changed and the signature left alone.
        val wellFormed = stranger.offer(impersonated, request, request.maxFare - step - step)

        val forgeries: Map<String, ByteArray> = linkedMapOf(
            "signed by a key that is not the driver's" to stranger.offer(nobody, request, request.maxFare, driver = impersonated.address),
            "above the fare guide's ceiling for the trip" to stranger.offer(impersonated, request, ceiling + step),
            "below the floor under the rider's price" to stranger.offer(impersonated, request, floor - step),
            // The quote hash is recomputed from the message's own terms (A3), so a signature over one fare or
            // one expiry does not carry over to another.
            "signed for another fare" to stranger.tamper(wellFormed, "fare", (request.maxFare - step).toString()),
            "signed for another expiry" to stranger.tamper(wellFormed, "expiresAt", request.expiresAt - 1),
            "expiring after the request does" to stranger.offer(impersonated, request, request.maxFare, expiresAt = request.expiresAt + 600),
            "already expired" to stranger.offer(impersonated, request, request.maxFare, expiresAt = System.currentTimeMillis() / 1000 - 60),
            "for some other request" to stranger.offer(impersonated, elsewhere, request.maxFare),
            // Correctly signed and fairly priced, but from an account with no stake, licence or role: it could
            // never accept the ride, so the rider must not be offered it.
            "from an account the policy does not let accept rides" to stranger.offer(nobody, request, request.maxFare),
        )
        for ((what, message) in forgeries) {
            stranger.say(message, seconds = 4)
            assertTrue(app.offers.offers().isEmpty(), "the rider was shown an offer $what: ${app.offers.offers()}")
        }

        // The stranger's own well-formed offer is shown. So the stranger writes offers the app accepts, and
        // each forgery above was turned away for the one thing wrong with it.
        val saying = Thread { stranger.say(wellFormed, seconds = 20) }.apply { isDaemon = true; start() }
        assertEquals(coin.toReais(request.maxFare - step - step).setScale(2), app.offers.awaitOffer(impersonated.address))
        saying.interrupt()

        // And an honest driver's offer is shown beside it, at the fare that driver signed. Nothing else is.
        val offer = honest.offer(request, request.maxFare - step)
        assertEquals(coin.toReais(offer.fare).setScale(2), app.offers.awaitOffer(honest.address))
        forgeries.values.forEach(stranger::sayOnce)
        Thread.sleep(4_000)
        // Each driver's offer still stands at the fare that driver signed: no forgery replaced one either.
        assertEquals(
            mapOf(
                honest.address to coin.toReais(offer.fare).setScale(2),
                impersonated.address to coin.toReais(request.maxFare - step - step).setScale(2),
            ),
            app.offers.offers(),
        )
    }
}
