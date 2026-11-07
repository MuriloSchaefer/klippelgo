package org.klippelgo.tests.integrity

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the release registry tells a running app (docs/releases-and-data.md). The app reads its own release's
 * status at start and again whenever a release event shows in its change check: revoked, it starts no new ride;
 * deprecated, it goes on and says an update is coming. Each test puts the release back.
 */
class ReleaseTest : Scenario() {
    private fun askForARide() {
        app.whereTo.ask()
        app.ask.shown()
        assertTrue(app.ask.search("Avenida Brasil").isNotEmpty())
        app.ask.pick(0)
        app.ask.submit()
    }

    @Test
    fun `a revoked release starts no new ride and says so, and asks again once the board puts it back`() {
        val gov = governance()
        freshRider()
        assertEquals("none", app.releaseNotice.status())

        gov.revokeRelease()
        assertEquals(3, gov.releaseStatus())
        app.releaseNotice.awaitStatus("revoked")
        assertTrue(!app.releaseNotice.text().isNullOrBlank())

        // The app refuses to ask for a ride, and says why.
        askForARide()
        app.error.shown()
        assertTrue(!app.error.message().isNullOrBlank())
        app.error.dismiss()
        // The registry stops honest apps only: the escrow's own policy is unchanged. Closing it is the
        // revoke-release playbook's part (tests/security covers what the chain refuses).
        assertTrue(chain.canOpenRides(appRider))

        gov.reinstateRelease()
        app.releaseNotice.awaitStatus("none")
        // Asking works again: a driver hears the request.
        val driver = peer(DevAccount.E2E_DRIVER)
        app.back.system()
        askForARide()
        app.offers.shown()
        driver.awaitRequest(appRider)
    }

    @Test
    fun `a deprecated release goes on working and says an update is coming`() {
        val gov = governance()
        freshDriver()
        app.goOnline.awaitBlockers()

        gov.deprecateRelease()
        app.releaseNotice.awaitStatus("deprecated")
        // Nothing is refused: the driver may still accept rides.
        app.goOnline.awaitBlockers()
        assertTrue(chain.canAcceptRides(appDriver))
    }

    @Test
    fun `a driver on a revoked release quotes no new ride`() {
        val gov = governance()
        val rider = peer(DevAccount.E2E_RIDER)
        freshDriver()
        app.goOnline.goOnline()
        gov.revokeRelease()
        app.releaseNotice.awaitStatus("revoked")

        val request = rider.ask(rider.route(Places.CENTRE, Places.LAKE))
        app.request.awaitFrom(Peer.short(request.rider))
        app.request.send()
        app.error.shown()
        assertTrue(!app.error.message().isNullOrBlank())
        app.error.dismiss()
        // No offer left the phone.
        assertTrue(runCatching { rider.awaitOffer(request, appDriver, 10_000) }.isFailure)
        rider.withdraw(request)
    }

    @Test
    fun `an update waits until the ride under way is finished`() {
        val gov = governance()
        val rider = peer(DevAccount.E2E_RIDER)
        freshDriver()
        app.goOnline.goOnline()
        val request = rider.ask(rider.route(Places.CENTRE, Places.LAKE))
        app.request.awaitFrom(Peer.short(request.rider))
        app.request.send()
        val id = rider.take(request, rider.awaitOffer(request, appDriver))
        app.driverRide.awaitRide("chosen")
        app.driverRide.awaitRouteChecked("chosen")

        // A newer release. A ride someone opened naming this driver holds nothing of the driver's, so it does not
        // hold the update: anyone can open one, and the rider takes it back on the old escrow.
        gov.publishNextRelease()
        app.releaseNotice.awaitStatus("update")
        waitUntil("the update to be offered beside an Open ride", 60_000) { true.takeIf { app.releaseNotice.offersUpdate() } }

        // Once the driver accepts, the ride is theirs on this release's escrow: the update waits for it.
        app.driverRide.accept()
        waitUntil("the ride to be accepted on chain", 60_000) { true.takeIf { rider.ride(id).status == RideStatus.Accepted } }
        waitUntil("the notice to hold the update for the ride", 60_000) { true.takeIf { app.releaseNotice.updateWaitsForRide() } }
        assertFalse(app.releaseNotice.offersUpdate())

        // Once the ride is over, the update is offered again.
        rider.cancel(id)
        waitUntil("the ride to be cancelled", 60_000) { true.takeIf { rider.ride(id).status == RideStatus.Cancelled } }
        waitUntil("the update to be offered", 90_000) { true.takeIf { app.releaseNotice.offersUpdate() } }
    }
}
