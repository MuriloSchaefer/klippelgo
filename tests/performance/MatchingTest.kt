package org.klippelgo.tests.performance

import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.RideStatus
import org.klippelgo.tests.Bench
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Places
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import kotlin.test.Test

/**
 * How long each step of a match takes as a person sees it: a tap on one phone to the result on the other. Each
 * figure includes gossip crossing the network, the app's two-second refresh and, where money moves, a block.
 */
class MatchingTest : Scenario() {
    @Test
    fun `a rider's wait at each step of a match`() {
        val driver = peer(DevAccount.DRIVER)
        freshRider()
        app.whereTo.ask()
        app.ask.search("Avenida Brasil")
        app.ask.pick(0)

        lateinit var request: org.klippelgo.chain.RideRequest
        Bench.record("request sent to a driver hearing it", Bench.time {
            app.ask.submit()
            request = driver.awaitRequest(appRider)
        }, "ms", 10_000)

        lateinit var offer: org.klippelgo.chain.Offer
        Bench.record("driver's offer sent to the rider seeing it", Bench.time {
            offer = driver.offer(request)
            app.offers.awaitOffer(driver.address)
        }, "ms", 10_000)

        app.offers.pick(driver.address)
        app.confirm.shown()
        Bench.record("fare locked: tap to the ride on screen", Bench.time {
            app.confirm.lock()
            app.onTheWay.awaitStatus("Open")
        }, "ms", 30_000)

        val ride = driver.awaitRide(offer)
        Bench.record("sealed route reaching the driver, checked", Bench.time { driver.awaitRoute(ride) }, "ms", 15_000)

        Bench.record("driver accepts to the rider seeing it", Bench.time {
            driver.accept(ride.id)
            app.onTheWay.awaitStatus("Accepted")
        }, "ms", 20_000)

        Bench.record("driver's first position to the rider seeing it", Bench.time {
            waitUntil("the rider to see the driver's position", 60_000) {
                driver.sharePosition(ride.id, Places.CENTRE)
                true.takeIf { app.onTheWay.driverLive() }
            }
        }, "ms", 20_000)

        driver.start(ride.id)
        app.onTrip.shown()
        app.onTrip.arrived()
        app.rideEnd.stars(5)
        Bench.record("arrival confirmed: tap to paid on chain", Bench.time {
            app.rideEnd.pay()
            waitUntil("the ride to complete", 60_000) { true.takeIf { driver.ride(ride.id).status == RideStatus.Completed } }
        }, "ms", 30_000)
        driver.rate(ride.id, 5)
    }

    @Test
    fun `a driver's screen under a flood of requests`() {
        val crowd = peer(DevAccount.RIDER)
        val late = peer(DevAccount.RIDER_2)
        freshDriver()
        val idle = phone.memoryMb()

        // One request first, to know how long a request normally takes to reach the screen.
        app.goOnline.goOnline()
        val route = crowd.route(Places.CENTRE, Places.LAKE)
        val first = crowd.ask(route)
        Bench.record("request sent to the driver seeing it", Bench.time { app.request.awaitFrom(Peer.short(first.rider)) }, "ms", 15_000)
        crowd.withdraw(first)
        app.request.gone()
        app.goOnline.goOffline()

        // 150 open requests at once, all valid: three quarters of what a phone keeps (the cap is 200). Each
        // costs the driver's phone the checks of the message table, and a place in every refresh.
        val flood = (1..150).map { crowd.ask(route) }
        // Another phone on the same network holds all of them, so they did cross it.
        waitUntil("150 requests to cross the gossip network", 60_000) { true.takeIf { late.requests().size >= 150 } }
        Thread.sleep(5_000)

        // With all of that in the air, a new rider's request must still get through, and the UI must still answer.
        app.goOnline.goOnline()
        phone.awaitTag("go-online.requests", 60_000)
        val another = late.ask(late.route(Places.CATHEDRAL, Places.NORTH))
        waitUntil("the late rider's request to be heard by the crowd's phone too", 30_000) { crowd.requests().firstOrNull { it.id == another.id } }

        val reads = (1..10).map { Bench.time { phone.screen() } }
        Bench.record("reading the screen under 150 open requests (median)", Bench.median(reads), "ms", 1_500)
        Bench.record("picking a request and passing it, under 150", Bench.time {
            app.request.pass()
            // Passed, it closes and is gone from the list.
            phone.awaitGone("request", 20_000)
        }, "ms", 5_000)
        Bench.record("memory with 150 open requests, over idle", (phone.memoryMb() - idle).toLong().coerceAtLeast(0), "MB", 300)

        // Withdrawn, they all leave, and the phone is back to waiting.
        flood.forEach(crowd::withdraw)
        late.withdraw(another)
        Bench.record("150 withdrawals to an empty screen", Bench.time { phone.awaitTag("go-online.waiting", 90_000) }, "ms", 60_000)
    }
}
