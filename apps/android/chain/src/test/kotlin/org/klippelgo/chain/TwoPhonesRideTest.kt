package org.klippelgo.chain

import org.klippelgo.core.Coordinate
import org.klippelgo.core.GossipConfig
import org.klippelgo.core.GossipNode
import org.klippelgo.core.regionCell
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays rider and driver at the same time against a running local testnet, the way two emulators do: each side
 * runs on its own thread with its own key and its own gossip node. They agree a fare over gossip, through a
 * bootstrap node, and from then on only learn what the other did by reading the chain.
 *
 * Runs when KLIPPELGO_RPC is set, against a chain that has the test map pack (`testnet/publish-pack.sh
 * testnet/fixtures/test-pack`), e.g.
 *   KLIPPELGO_RPC=http://127.0.0.1:8545 KLIPPELGO_DEPLOYMENT=../../../deployments/local.json ./gradlew test
 */
class TwoPhonesRideTest {
    private val rpc = System.getenv("KLIPPELGO_RPC").orEmpty()
    private val deployment = System.getenv("KLIPPELGO_DEPLOYMENT").orEmpty()
        .takeIf { it.isNotBlank() }?.let { Deployment.fromJson(File(it).readText()) } ?: Deployment.LOCAL_DEFAULT

    @Test
    fun riderAndDriverCompleteARideThroughTheChain() {
        if (rpc.isBlank()) {
            println("KLIPPELGO_RPC not set, skipping the two-phone ride test")
            return
        }
        val riderPhone = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment)
        // A map pack governance activated on this chain (`make publish-pack`). The route itself stays on the
        // rider's phone: the request carries its hash, as the phone computes it from the pack, and how long it is.
        val pack = riderPhone.latestPack(TEST_REGION)
            ?: error("No map pack on this chain. Run: testnet/publish-pack.sh testnet/fixtures/test-pack")
        val route = RouteSummary(
            packId = Numeric.toHexString(pack),
            routeHash = Numeric.toHexString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            distanceM = 8_500,
            durationS = 1_320,
            // The regions the trip starts and ends in: drivers see these, never the points themselves.
            pickupCell = regionCell(PICKUP),
            dropoffCell = regionCell(DROPOFF),
        )
        val driverPhone = KlippelGoClient(rpc, DevAccount.DRIVER.key(), deployment)
        val coin = riderPhone.brz
        val maxFare = coin.toBaseUnits(BigDecimal("25.00"))
        val fare = coin.toBaseUnits(BigDecimal("23.40"))

        assertTrue(riderPhone.drivers().any { it.address == driverPhone.address && it.canAccept })
        // 8,5 km and 22 min are recommended at R$ 25,50; the rider may ask 10% either side, so R$ 25,00 is fine.
        assertEquals(2550L, riderPhone.tariff.recommended(8_500, 1_320))
        assertEquals(2295L..2805L, riderPhone.tariff.riderRange(8_500, 1_320))
        val driverBefore = driverPhone.balanceOf(coin)
        val seenBefore = driverPhone.ridesAsDriver().toSet()

        // The bootstrap node the phones dial, as `make gossip-node` runs it, on a free port.
        val bootstrap = GossipNode.start(GossipConfig(listen = "/ip4/127.0.0.1/tcp/0", bootstrap = emptyList(), router = true))
        fun gossip() = GossipNode.start(GossipConfig(listen = null, bootstrap = bootstrap.listenAddresses(), router = false))
        val riderMatch = Matchmaker(riderPhone, gossip())
        // The driver hears the cells around them; the request goes to the pickup's.
        val driverMatch = Matchmaker(driverPhone, gossip()).apply { listen(Matchmaker.around(PICKUP)) }

        val pool = Executors.newFixedThreadPool(2)
        val riderSaw = java.util.concurrent.atomic.AtomicBoolean(false)
        // Stands in for the quote with the exact route, which only the chosen driver may read.
        val routeForDriver = "{\"pickup\":[${PICKUP.latE7},${PICKUP.lonE7}],\"dropoff\":[${DROPOFF.latE7},${DROPOFF.lonE7}]}"
        // Stands in for the hash of the trip receipt both phones sign at arrival.
        val receipt = Hash.sha3("trip receipt".toByteArray())

        // Nor may a rider ask a price far from the recommended one.
        assertTrue(runCatching { riderMatch.requestRide(coin, coin.toBaseUnits(BigDecimal("15.00")), route) }.isFailure)

        // A request for a route on a map nobody activated reaches no driver.
        val unknownPack = route.copy(packId = Numeric.toHexString(Hash.sha3("no such pack".toByteArray())))
        val ignored = riderMatch.requestRide(coin, maxFare, unknownPack)
        repeat(6) {
            riderMatch.tick()
            driverMatch.tick()
            Thread.sleep(500)
        }
        assertTrue(driverMatch.openRequests().isEmpty())
        riderMatch.closeRequest(ignored)

        // Driver phone: quote R$ 23,40 for the rider's request, then wait for the ride, accept it and start it.
        val driverJob = pool.submit<BigInteger> {
            val request = poll("the rider's request", driverMatch) {
                driverMatch.openRequests().firstOrNull { it.rider == riderPhone.address }
            }
            assertEquals(maxFare, request.maxFare)
            assertEquals(route, request.route) // 8,5 km and 22 min, and nothing about where
            // A driver may go 20% under the rider's price and no further, or above it up to 10% over the
            // recommended R$ 25,50, which is R$ 28,05.
            assertTrue(runCatching { driverMatch.sendOffer(request, coin.toBaseUnits(BigDecimal("19.50"))) }.isFailure)
            assertTrue(runCatching { driverMatch.sendOffer(request, coin.toBaseUnits(BigDecimal("28.50"))) }.isFailure)
            driverMatch.sendOffer(request, coin.toBaseUnits(BigDecimal("28.00")))
            driverMatch.sendOffer(request, coin.toBaseUnits(BigDecimal("24.00")))
            val quote = driverMatch.sendOffer(request, fare) // a newer quote replaces the earlier one
            val id = poll("a new ride for the driver", driverMatch) {
                driverPhone.ridesAsDriver().firstOrNull { it !in seenBefore }
            }
            val ride = driverPhone.ride(id)
            assertEquals(RideStatus.Open, ride.status)
            assertEquals(fare, ride.fare)
            assertEquals(quote.quoteHash, ride.quoteHash) // the rider locked exactly what the driver signed
            poll("the rider's request to close", driverMatch) { true.takeIf { driverMatch.openRequests().isEmpty() } }
            // The route arrives sealed to the key in the driver's offer: this driver can read it.
            assertEquals(routeForDriver, poll("the sealed route", driverMatch) { driverMatch.revealedQuote(quote.quoteHash) })
            driverPhone.accept(id)
            // Now on this rider's ride, the driver lets them see where the car is. Only the rider can read it.
            val riderKey = driverMatch.riderKey(quote.quoteHash) ?: error("the rider's position key did not arrive")
            poll("the rider to see the driver's position", driverMatch) {
                driverMatch.sharePosition(quote.quoteHash, riderKey, PICKUP.latE7 + 5_000, PICKUP.lonE7)
                riderSaw.takeIf { it.get() }
            }
            driverPhone.start(id, Hash.sha3("pickup proof $id".toByteArray()))
            poll("the rider to confirm arrival") { id.takeIf { driverPhone.ride(id).status == RideStatus.Completed } }
            assertEquals(Numeric.toHexString(receipt), driverPhone.ride(id).receiptHash)
            driverPhone.rate(id, 5)
            id
        }

        // Rider phone: ask for a ride up to R$ 25,00, take the driver's quote, wait until the driver has started
        // the ride, then confirm arrival.
        val riderJob = pool.submit<BigInteger> {
            val request = riderMatch.requestRide(coin, maxFare, route)
            val offer = poll("the driver's R$ 23,40 quote", riderMatch) {
                riderMatch.offers(request).firstOrNull { it.fare == fare }
            }
            assertEquals(driverPhone.address, offer.driver)
            assertEquals(1, riderMatch.offers(request).size)
            val id = riderMatch.acceptOffer(request, offer, routeForDriver)
            assertTrue(riderMatch.openRequests().isEmpty())
            assertEquals(0, riderPhone.ridesAhead(riderPhone.ride(id))) // this driver has no ride before this one
            val seen = poll("the driver's position", riderMatch) { riderMatch.driverPosition(offer.quoteHash) }
            assertEquals(PICKUP.latE7 + 5_000 to PICKUP.lonE7, seen.latE7 to seen.lonE7)
            riderSaw.set(true)
            poll("the driver to start the ride", riderMatch) { id.takeIf { riderPhone.ride(id).status == RideStatus.Started } }
            riderPhone.complete(id, receipt)
            riderPhone.rate(id, 5)
            id
        }

        val riderId = riderJob.get(2, TimeUnit.MINUTES)
        val driverId = driverJob.get(2, TimeUnit.MINUTES)
        pool.shutdown()

        assertEquals(riderId, driverId)
        assertTrue(riderPhone.hasRated(riderId) && driverPhone.hasRated(riderId))
        assertEquals(RideStatus.Completed, riderPhone.ride(riderId).status)
        // Driver nets the R$ 23,40 fare minus R$ 1,00 for Instituto Klippel and minus the government's tax, a
        // share of the fare read from the escrow (the bond comes back). The tax went to its collector.
        val ride = riderPhone.ride(riderId)
        assertEquals(riderPhone.taxOn(fare), ride.tax)
        // The driver's own operations (accept, start, the rating) paid the network in the same coin, through the
        // paymaster: a few centavos at most, and at least what the phone counted its gas at.
        val driverGas = driverPhone.gasSpent(riderId) ?: error("the driver's phone lost count of its gas")
        val gasPaid = fare - coin.toBaseUnits(BigDecimal("1.00")) - ride.tax - (driverPhone.balanceOf(coin) - driverBefore)
        assertTrue(gasPaid.signum() > 0, "the driver paid nothing for the network")
        assertTrue(gasPaid >= coin.toBaseUnits(driverPhone.infrastructureCost(driverGas)) - BigInteger.ONE, "the driver paid less than the gas: $gasPaid")
        assertTrue(gasPaid < coin.toBaseUnits(BigDecimal("0.05")), "the driver paid R$ ${coin.toReais(gasPaid)} for the network")
        // Each phone knows what its own operations cost the network, and what that is in reais.
        val riderGas = riderPhone.gasSpent(riderId) ?: error("the rider's phone lost count of its gas")
        assertTrue(riderGas.signum() > 0 && driverGas.signum() > 0)
        println(
            "Ride $riderId completed: driver received R$ ${coin.toReais(ride.driverPayout)}, Instituto Klippel R$ 1,00, " +
                "tax R$ ${coin.toReais(ride.tax)}, network R$ ${riderPhone.infrastructureCost(riderGas)} (rider's side)",
        )
    }

    /**
     * What announcing costs, heard by a node that only listens: a phone forgets a closed request and a replay does
     * not bring it back, the rider answers the replay with the close, and the route stops once the driver's
     * position shows it arrived.
     */
    @Test
    fun aClosedRequestStaysGoneAndARouteStopsAtTheFirstPosition() {
        if (rpc.isBlank()) {
            println("KLIPPELGO_RPC not set, skipping the announcing test")
            return
        }
        val riderPhone = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val driverPhone = KlippelGoClient(rpc, DevAccount.DRIVER_2.key(), deployment)
        val pack = riderPhone.latestPack(TEST_REGION)
            ?: error("No map pack on this chain. Run: testnet/publish-pack.sh testnet/fixtures/test-pack")
        val route = RouteSummary(
            packId = Numeric.toHexString(pack),
            routeHash = Numeric.toHexString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            distanceM = 8_500,
            durationS = 1_320,
            pickupCell = regionCell(PICKUP),
            dropoffCell = regionCell(DROPOFF),
        )
        val coin = riderPhone.brz
        val bootstrap = GossipNode.start(GossipConfig(listen = "/ip4/127.0.0.1/tcp/0", bootstrap = emptyList(), router = true))
        fun gossip() = GossipNode.start(GossipConfig(listen = null, bootstrap = bootstrap.listenAddresses(), router = false))
        val rider = Matchmaker(riderPhone, gossip())
        val driver = Matchmaker(driverPhone, gossip()).apply { listen(Matchmaker.around(PICKUP)) }
        // A driver who hears it all and quotes nothing, whose list is what forgetting is about.
        val bystander = Matchmaker(KlippelGoClient(rpc, DevAccount.DRIVER.key(), deployment), gossip()).apply { listen(Matchmaker.around(PICKUP)) }
        val spy = gossip()
        val topic = Matchmaker.topicFor(deployment.gossipRelease, Matchmaker.cellOf(route.pickupCell))
        spy.subscribe(topic)
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        val heard = ArrayList<Pair<String, ByteArray>>()
        fun tickAll() {
            rider.tick()
            driver.tick()
            bystander.tick()
            spy.drain().mapTo(heard) { json.readTree(it.data).get("t").asText() to it.data }
        }
        fun all(what: String, check: () -> Boolean) = poll(what) { tickAll(); true.takeIf { check() } }
        fun listed(m: Matchmaker) = m.describe().lines().count { it.startsWith("request ") }

        // Gossip meshes take a moment; a rider announces again anyway.
        val request = rider.requestRide(coin, coin.toBaseUnits(BigDecimal("25.00")), route)
        all("both drivers to hear the request") { driver.openRequests().isNotEmpty() && bystander.openRequests().isNotEmpty() }
        // An account the policy does not let accept, with a real certificate, quotes lower: its offer is never held.
        // The seeded accounts all hold the driver role and stake, so it is a new one, registered as a rider only:
        // its account is on chain, so its certificate is good, but it has no driver role, stake or score.
        val notADriver = KlippelGoClient(rpc, SoftwareDeviceKey(BigInteger(1, ByteArray(32).also { SecureRandom().nextBytes(it) })), deployment)
        notADriver.mint(notADriver.brz, notADriver.brz.toBaseUnits(BigDecimal("5.00")))
        notADriver.ensureRegistered(KlippelGoClient.ROLE_RIDER)
        check(!notADriver.canAcceptRides())
        spy.publish(topic, signedOffer(notADriver, request, coin.toBaseUnits(BigDecimal("21.00")), 1))
        driver.sendOffer(request, coin.toBaseUnits(BigDecimal("23.40")))
        val offer = poll("the offer") { tickAll(); rider.offers(request).firstOrNull() }
        repeat(4) { tickAll(); Thread.sleep(500) }
        assertTrue(rider.offers(request).none { it.driver == notADriver.address }, "an offer from an account that may not accept is not held")
        // A driver's phone keeps no other driver's offers: it shows only its own.
        assertTrue(bystander.offers(request).isEmpty(), "a bystander driver holds no one's offers")
        val routeJson = "{\"pickup\":[${PICKUP.latE7},${PICKUP.lonE7}],\"dropoff\":[${DROPOFF.latE7},${DROPOFF.lonE7}]}"
        val id = rider.acceptOffer(request, offer, routeJson)

        // The bystander forgets it at the close. The driver who quoted keeps it, for the route.
        all("the bystander to forget the closed request") { listed(bystander) == 0 }
        assertEquals(routeJson, poll("the sealed route") { tickAll(); driver.revealedQuote(offer.quoteHash) })
        assertTrue(listed(driver) == 1)

        // A stranger replays the request: the bystander does not list it again, and the rider says it is closed.
        val replay = heard.first { it.first == "request" }.second
        repeat(36) { tickAll(); Thread.sleep(500) } // past the close's last announcement, at 7.5 s
        heard.clear()
        spy.publish(topic, replay)
        all("the rider to close the replayed request again") { heard.any { it.first == "close" } }
        assertTrue(bystander.openRequests().isEmpty() && listed(bystander) == 0)

        // A position nobody signed does not say the route arrived: it goes on being announced.
        val close = json.readTree(heard.first { it.first == "close" }.second)
        val forged = json.createObjectNode().put("t", "position").put("quote", offer.quoteHash.lowercase())
            .put("sealed", "AAAA").put("sig", close.get("sig").asText()).set<com.fasterxml.jackson.databind.node.ObjectNode>("cert", close.get("cert"))
        heard.clear()
        spy.publish(Matchmaker.rideTopic(offer.quoteHash), json.writeValueAsBytes(forged))
        all("the route to go out after a forged position") { heard.any { it.first == "route" } }

        // The first position says the route arrived: it is not announced again.
        val riderKey = driver.riderKey(offer.quoteHash) ?: error("the rider's position key did not arrive")
        poll("the driver's position") {
            driver.sharePosition(offer.quoteHash, riderKey, PICKUP.latE7, PICKUP.lonE7)
            tickAll()
            rider.driverPosition(offer.quoteHash)
        }

        // A true position, signed by the ride's driver, counts only on the ride's own topic.
        val moved = driver.positionMessage(offer.quoteHash, riderKey, PICKUP.latE7 + 9_000, PICKUP.lonE7, System.currentTimeMillis())
        repeat(4) {
            spy.publish(Matchmaker.topicFor(deployment.gossipRelease, Matchmaker.cellOf(regionCell(PICKUP))), moved)
            Thread.sleep(500)
            tickAll()
        }
        assertEquals(PICKUP.latE7, rider.driverPosition(offer.quoteHash)?.latE7, "a position on the cell's topic was believed")
        all("the same position on the ride's topic to be believed") {
            spy.publish(Matchmaker.rideTopic(offer.quoteHash), moved)
            rider.driverPosition(offer.quoteHash)?.latE7 == PICKUP.latE7 + 9_000
        }

        heard.clear()
        repeat(70) { tickAll(); Thread.sleep(1_000) } // past the widest gap a route reaches
        assertEquals(0, heard.count { it.first == "route" })
        riderPhone.cancel(id)
    }

    /** A list of 200 far requests makes room for a near one, however long it has been full, and stays at 200. */
    @Test
    fun aFullListKeepsTheNearestRequest() {
        if (rpc.isBlank()) {
            println("KLIPPELGO_RPC not set, skipping the full list test")
            return
        }
        val riderPhone = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val pack = riderPhone.latestPack(TEST_REGION)
            ?: error("No map pack on this chain. Run: testnet/publish-pack.sh testnet/fixtures/test-pack")
        fun route(pickup: Coordinate) = RouteSummary(
            packId = Numeric.toHexString(pack),
            routeHash = Numeric.toHexString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            distanceM = 8_500,
            durationS = 1_320,
            pickupCell = regionCell(pickup),
            dropoffCell = regionCell(DROPOFF),
        )
        val coin = riderPhone.brz
        val bootstrap = GossipNode.start(GossipConfig(listen = "/ip4/127.0.0.1/tcp/0", bootstrap = emptyList(), router = true))
        fun gossip() = GossipNode.start(GossipConfig(listen = null, bootstrap = bootstrap.listenAddresses(), router = false))
        val rider = Matchmaker(riderPhone, gossip())
        val driver = Matchmaker(KlippelGoClient(rpc, DevAccount.DRIVER_2.key(), deployment), gossip()).apply { listen(Matchmaker.around(PICKUP), PICKUP) }
        fun listed() = driver.describe().lines().count { it.startsWith("request ") }
        val fare = coin.toBaseUnits(BigDecimal("25.00"))
        val far = Coordinate(PICKUP.latE7 + 300_000, PICKUP.lonE7)

        repeat(200) { rider.requestRide(coin, fare, route(far)) }
        poll("the driver's list to fill", timeoutMs = 120_000) { rider.tick(); driver.tick(); true.takeIf { listed() == 200 } }
        // A near request nobody signed pushes nothing out: this one's signature is for another request.
        val listening = gossip()
        val topic = Matchmaker.topicFor(deployment.gossipRelease, Matchmaker.cellOf(regionCell(far)))
        listening.subscribe(topic)
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        val sample = poll("a request to copy") { rider.tick(); driver.tick(); listening.drain().firstOrNull()?.data }
        val before = driver.openRequests().map { it.id }.toSet()
        val forged = (json.readTree(sample) as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("nonce", Numeric.toHexString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            .put("from", regionCell(PICKUP))
        listening.publish(Matchmaker.topicFor(deployment.gossipRelease, Matchmaker.cellOf(regionCell(PICKUP))), json.writeValueAsBytes(forged))
        repeat(6) { rider.tick(); driver.tick(); Thread.sleep(500) }
        assertEquals(before, driver.openRequests().map { it.id }.toSet())

        val near = rider.requestRide(coin, fare, route(PICKUP))
        poll("the near request to get in") { rider.tick(); driver.tick(); true.takeIf { driver.openRequests().any { it.id == near.id } } }
        assertEquals(200, listed())
        // A newcomer from far away pushes out another far one, never the near one.
        rider.requestRide(coin, fare, route(far))
        repeat(16) { rider.tick(); driver.tick(); Thread.sleep(500) }
        assertTrue(driver.openRequests().any { it.id == near.id })
        assertEquals(200, listed())
    }

    private companion object {
        /** Region of `testnet/fixtures/test-pack`, a manifest that stands in for a map pack. */
        const val TEST_REGION = "test-no-map"

        /** Two points in Cascavel, about 8 km apart. */
        val PICKUP = Coordinate(-249_555_000, -534_552_000)
        val DROPOFF = Coordinate(-249_900_000, -534_000_000)
    }

    /** Waits for `check`, running the phone's gossip the way the app's two-second loop does. */
    private fun <T : Any> poll(what: String, gossip: Matchmaker? = null, timeoutMs: Long = 60_000, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            gossip?.tick()
            check()?.let { return it }
            Thread.sleep(500)
        }
        error("Timed out waiting for $what")
    }
}
