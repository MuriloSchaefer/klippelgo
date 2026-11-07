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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One driver with the app and two riders, who are peer accounts, wanting a ride at the same time. A driver
 * holds at most two locked rides: the one being driven and the next. The next one can only be taken once the
 * rider of the first is in the car. A rider who locks one of the driver's quotes while the driver is on their
 * way to someone else's pickup is given everything back.
 */
class DriverTwoRidesTest : Scenario() {
    @Test
    fun `two riders lock the same driver, who sees both and takes either, and the other is given everything back`() {
        val ana = peer(DevAccount.E2E_RIDER)
        val bia = peer(DevAccount.E2E_RIDER_2)
        val driver = stack.client(DevAccount.E2E_DRIVER)
        val coin = driver.brz
        val (_, _, driverBond) = driver.tokenConfig(coin)
        freshDriver()
        app.goOnline.goOnline()

        // Both ask, and the driver quotes both.
        val first = ana.ask(ana.route(Places.CATHEDRAL, Places.LAKE))
        val second = bia.ask(bia.route(Places.CENTRE, Places.NORTH))
        app.request.awaitFrom(Peer.short(first.rider))
        app.request.send()
        val toAna = ana.awaitOffer(first, appDriver)
        app.request.awaitFrom(Peer.short(second.rider))
        app.request.send()
        val toBia = bia.awaitOffer(second, appDriver)

        // Both lock before the driver answers either.
        val driverBefore = driver.balanceOf(coin)
        val anaBefore = ana.balance()
        val from = mark()
        val a = ana.take(first, toAna)
        val b = bia.take(second, toBia)
        assertEquals(RideStatus.Open, chain.ride(a).status)

        // The driver is shown both, oldest first, each with its own route and its own answer.
        waitUntil("both locked rides to be listed", 60_000) { true.takeIf { app.driverRide.locked() == listOf(a.toString(), b.toString()) } }
        assertEquals(Peer.short(first.rider), app.driverRide.ride("chosen.$a")!!.rider.lowercase())
        assertEquals(Peer.short(second.rider), app.driverRide.ride("chosen.$b")!!.rider.lowercase())
        app.driverRide.awaitRouteChecked("chosen.$a")
        app.driverRide.awaitRouteChecked("chosen.$b")
        assertTrue(app.driverRide.canAccept(a.toString()) && app.driverRide.canAccept(b.toString()), "a locked ride cannot be taken")

        // The driver takes Bia's, the newer one.
        app.driverRide.accept(b.toString())
        waitUntil("Bia's ride to be accepted on chain", 60_000) { true.takeIf { chain.ride(b).status == RideStatus.Accepted } }

        // On the way to Bia's pickup, Ana's ride is not the driver's to take: it is called off, and Ana has all
        // of it back, but for what her lock cost the network.
        waitUntil("Ana's ride to be cancelled on chain", 60_000) { true.takeIf { chain.ride(a).status == RideStatus.Cancelled } }
        assertEquals(anaBefore - gasPaid(ana.address, ana.coin, from), ana.balance())
        waitUntil("the driver's screen to drop Ana's ride") { true.takeIf { app.driverRide.locked().isEmpty() } }
        assertEquals(b.toString(), app.driverRide.awaitRide("current").ride)
        // One bond posted, for Bia's ride: Ana's was never accepted.
        assertEquals(driverBefore - driverBond - gasPaid(appDriver, coin, from), driver.balanceOf(coin))
        assertEquals(RideStatus.Accepted, chain.ride(b).status)
        bia.cancel(b)
    }

    @Test
    fun `a quote sent before the driver took a ride is called off if its rider locks it after`() {
        val ana = peer(DevAccount.E2E_RIDER)
        val bia = peer(DevAccount.E2E_RIDER_2)
        freshDriver()
        app.goOnline.goOnline()

        val first = ana.ask(ana.route(Places.CATHEDRAL, Places.LAKE))
        val second = bia.ask(bia.route(Places.CENTRE, Places.NORTH))
        app.request.awaitFrom(Peer.short(first.rider))
        app.request.send()
        val toAna = ana.awaitOffer(first, appDriver)
        app.request.awaitFrom(Peer.short(second.rider))
        app.request.send()
        val toBia = bia.awaitOffer(second, appDriver)

        // Ana locks and the driver takes her ride. The quote to Bia is still out, signed, and good for minutes.
        val a = ana.take(first, toAna)
        app.driverRide.awaitRouteChecked("chosen")
        app.driverRide.accept()
        waitUntil("Ana's ride to be accepted on chain", 60_000) { true.takeIf { chain.ride(a).status == RideStatus.Accepted } }

        // Bia locks it anyway. It was a quote for a driver with no ride in hand, so it is not one they keep.
        val biaBefore = bia.balance()
        val from = mark()
        val b = bia.take(second, toBia)
        waitUntil("Bia's ride to be cancelled on chain", 60_000) { true.takeIf { chain.ride(b).status == RideStatus.Cancelled } }
        assertEquals(biaBefore - gasPaid(bia.address, bia.coin, from), bia.balance())
        waitUntil("the driver's screen to drop Bia's ride") { true.takeIf { app.driverRide.ride("chosen") == null } }
        assertEquals(RideStatus.Accepted, chain.ride(a).status)
        ana.cancel(a)
    }

    @Test
    fun `the next ride is taken once the rider of the first is in the car, and never a third`() {
        val ana = peer(DevAccount.E2E_RIDER)
        val bia = peer(DevAccount.E2E_RIDER_2)
        // The second driver account is seeded as a rider too: here it is a third person wanting a ride.
        val caio = peer(DevAccount.E2E_DRIVER_2)
        freshDriver()
        app.goOnline.goOnline()

        val first = ana.ask(ana.route(Places.CATHEDRAL, Places.LAKE))
        app.request.awaitFrom(Peer.short(first.rider))
        app.request.send()
        val a = ana.take(first, ana.awaitOffer(first, appDriver))
        app.driverRide.awaitRouteChecked("chosen")
        app.driverRide.accept()
        waitUntil("Ana's ride to be accepted on chain", 60_000) { true.takeIf { chain.ride(a).status == RideStatus.Accepted } }

        // On the way to Ana, the driver is shown Bia's request under the ride and quotes it.
        val second = bia.ask(bia.route(Places.CENTRE, Places.NORTH))
        waitUntil("Bia's request to be offered under the ride", 60_000) { app.driverRide.nextRequest() }
        app.driverRide.quoteNext()
        app.request.awaitFrom(Peer.short(second.rider))
        app.request.send()
        val b = bia.take(second, bia.awaitOffer(second, appDriver))

        // Bia's ride is the next one: it waits, and cannot be taken while Ana is not in the car yet.
        val next = app.driverRide.awaitRide("chosen")
        assertEquals(b.toString(), next.ride)
        app.driverRide.awaitRouteChecked("chosen")
        assertFalse(app.driverRide.canAccept(), "the driver can take Bia's ride before Ana is in the car")
        assertTrue(app.driverRide.afterPickup(), "the driver is not told why Bia's ride cannot be taken yet")
        Thread.sleep(5_000)
        assertEquals(RideStatus.Open, chain.ride(b).status)
        assertFalse(app.driverRide.canAccept(), "the driver can take Bia's ride before Ana is in the car")
        // Nor around the app: the escrow itself refuses the driver's account a second ride waiting for pickup.
        val refused = runCatching { stack.client(DevAccount.E2E_DRIVER).accept(b) }
        assertTrue(refused.isFailure, "the escrow let the driver accept a second ride before the first rider was in the car")
        assertTrue("DriverBusy" in refused.exceptionOrNull()!!.message.orEmpty(), "refused for another reason: ${refused.exceptionOrNull()}")
        assertEquals(RideStatus.Open, chain.ride(b).status)

        // Ana is picked up: now Bia's ride can be taken, and Bia is told one ride is ahead of hers.
        app.driverRide.start()
        waitUntil("Ana's ride to start on chain", 60_000) { true.takeIf { chain.ride(a).status == RideStatus.Started } }
        waitUntil("the driver to be let take Bia's ride", 60_000) { true.takeIf { app.driverRide.canAccept() } }
        app.driverRide.accept()
        waitUntil("Bia's ride to be accepted on chain", 60_000) { true.takeIf { chain.ride(b).status == RideStatus.Accepted } }
        assertEquals(RideStatus.Started, chain.ride(a).status)
        assertEquals(1, chain.ridesAhead(chain.ride(b)))
        assertEquals(a.toString(), app.driverRide.awaitRide("current").ride)

        // Two rides in hand: a third rider's request is not put to the driver, and gets no quote.
        val third = caio.ask(caio.route(Places.CENTRE, Places.WEST))
        Thread.sleep(10_000)
        assertNull(app.driverRide.nextRequest(), "a driver with two rides in hand is offered a third")
        assertTrue(caio.offers(third).none { it.driver == appDriver.lowercase() }, "a driver with two rides in hand quoted a third")
        assertEquals(setOf(a, b), chain.underWay(appDriver).map { it.id }.toSet())

        caio.withdraw(third)
        ana.complete(a)
        bia.cancel(b)
    }
}
