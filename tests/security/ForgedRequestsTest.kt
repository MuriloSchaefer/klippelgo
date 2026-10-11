package org.klippelgo.tests.security

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RouteSummary
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A driver's screen shows a request only if it passed every check in the message table of
 * `apps/docs/security.md` (invariants A1 and A6, and of A7 only that the pack must be active). A stranger on the gossip network sends the app requests that
 * each break one rule; none may reach the screen. Then an honest request goes out, and must: otherwise the test
 * would pass with the app deaf.
 */
class ForgedRequestsTest : Scenario() {
    @Test
    fun `a driver is shown only requests that pass every check`() {
        val stranger = stranger()
        val honest = peer(DevAccount.RIDER)
        val victim = stack.client(DevAccount.RIDER_2)
        val coin = victim.brz
        freshDriver()
        app.goOnline.goOnline()

        // A real route on the active pack, so each forgery below is wrong in one way only.
        val measured = honest.route(Places.CENTRE, Places.LAKE)
        val route = honest.summary(measured, ByteArray(32).also { SecureRandom().nextBytes(it) })
        val fair = honest.recommended(measured)
        val now = System.currentTimeMillis() / 1000
        val nobody = stranger.newKey()

        val forgeries: Map<String, ByteArray> = linkedMapOf(
            "signed by a key that is not the rider's" to stranger.request(nobody, coin, fair, route, rider = victim.address).second,
            "from an account that never registered as a rider" to stranger.request(nobody, coin, fair, route).second,
            "priced at twice what the fare guide allows" to stranger.request(victim, coin, fair * 2.toBigInteger(), route).second,
            "priced at half what the fare guide allows" to stranger.request(victim, coin, fair / 2.toBigInteger(), route).second,
            "on a map pack governance never activated" to
                stranger.request(victim, coin, fair, route.copy(packId = Numeric.toHexString(Hash.sha3("no such pack".toByteArray())))).second,
            "already expired" to stranger.request(victim, coin, fair, route, expiresAt = now - 60).second,
            "expiring a day from now" to stranger.request(victim, coin, fair, route, expiresAt = now + 86_400).second,
            "with a region that is not a place" to stranger.request(victim, coin, fair, route.copy(pickupCell = "not-a-cell")).second,
            "for a trip of no length" to stranger.request(victim, coin, fair, route.copy(distanceM = 0)).second,
            "in a coin the escrow does not take" to
                stranger.request(victim, coin.copy(address = "0x000000000000000000000000000000000000dEaD"), fair, route).second,
        )
        for ((what, message) in forgeries) {
            stranger.say(message, seconds = 4)
            assertTrue(app.goOnline.visible() && !app.request.visible(), "the driver was shown a request $what")
        }
        // Keep saying all of them a while longer: nothing may show late either.
        repeat(3) {
            forgeries.values.forEach(stranger::sayOnce)
            Thread.sleep(1_000)
        }
        assertTrue(app.goOnline.visible() && !app.request.visible(), "the driver was shown a forged request")
        assertEquals(0, app.autoSkip.skipped(), "a forged request was counted among the driver's skipped ones")

        // The same trip in a message the stranger wrote itself, this time breaking no rule, is shown. So the
        // app was listening all along, and the stranger writes messages the app accepts: each forgery above
        // was turned away for the one thing wrong with it.
        val (wellFormed, message) = stranger.request(victim, coin, fair, route)
        val saying = Thread { stranger.say(message, seconds = 20) }.apply { isDaemon = true; start() }
        app.request.awaitFrom(Peer.short(wellFormed.rider))
        saying.interrupt()
    }

    @Test
    fun `nobody but its rider can withdraw a request`() {
        val stranger = stranger()
        val honest = peer(DevAccount.RIDER)
        freshDriver()
        app.goOnline.goOnline()

        val request = honest.ask(honest.route(Places.CATHEDRAL, Places.NORTH))
        app.request.awaitFrom(Peer.short(request.rider))

        // A withdrawal signed by someone else, and one signed by another registered rider.
        stranger.say(stranger.close(stranger.newKey(), request), seconds = 4)
        stranger.say(stranger.close(stack.client(DevAccount.RIDER_2), request), seconds = 4)
        assertTrue(app.request.visible(), "a stranger made a rider's request disappear from the driver's screen")

        // The same message from the same stranger, signed with the rider's own key, does take it away: so the
        // two above were turned away for who signed them.
        stranger.say(stranger.close(stack.client(DevAccount.RIDER), request), seconds = 4)
        app.request.gone()
    }
}
