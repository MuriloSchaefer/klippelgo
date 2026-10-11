package org.klippelgo.chain

import org.klippelgo.core.GossipConfig
import org.klippelgo.core.GossipNode
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
        val riderPhone = KlippelGoClient(rpc, DevAccount.RIDER.privateKey, deployment)
        // A map pack governance activated on this chain (`make publish-pack`). The route itself stays on the
        // rider's phone: the request carries its hash, as the phone computes it from the pack, and how long it is.
        val pack = riderPhone.latestPack(TEST_REGION)
            ?: error("No map pack on this chain. Run: testnet/publish-pack.sh testnet/fixtures/test-pack")
        val route = RouteSummary(
            packId = Numeric.toHexString(pack),
            routeHash = Numeric.toHexString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
            distanceM = 8_500,
            durationS = 1_320,
        )
        val driverPhone = KlippelGoClient(rpc, DevAccount.DRIVER.privateKey, deployment)
        val coin = riderPhone.brz
        val maxFare = coin.toBaseUnits(BigDecimal("25.00"))
        val fare = coin.toBaseUnits(BigDecimal("23.40"))

        assertTrue(riderPhone.drivers().any { it.address == driverPhone.address && it.canAccept })
        val driverBefore = driverPhone.balanceOf(coin)
        val seenBefore = driverPhone.ridesAsDriver().toSet()

        // The bootstrap node the phones dial, as `make gossip-node` runs it, on a free port.
        val bootstrap = GossipNode.start(GossipConfig(listen = "/ip4/127.0.0.1/tcp/0", bootstrap = emptyList(), router = true))
        fun gossip() = GossipNode.start(GossipConfig(listen = null, bootstrap = bootstrap.listenAddresses(), router = false))
        val riderMatch = Matchmaker(riderPhone, gossip())
        val driverMatch = Matchmaker(driverPhone, gossip())

        val pool = Executors.newFixedThreadPool(2)
        // Stands in for the quote with the exact route, which only the chosen driver may read.
        val routeForDriver = "{\"pickup\":[-249555000,-534552000]}"
        // Stands in for the hash of the trip receipt both phones sign at arrival.
        val receipt = Hash.sha3("trip receipt".toByteArray())

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
        // Driver nets R$ 22,40: the R$ 23,40 fare minus R$ 1,00 for Instituto Klippel (the bond comes back).
        assertEquals(coin.toBaseUnits(BigDecimal("22.40")), driverPhone.balanceOf(coin) - driverBefore)
        println("Ride $riderId completed: driver received R$ 22,40, Instituto Klippel R$ 1,00")
    }

    private companion object {
        /** Region of `testnet/fixtures/test-pack`, a manifest that stands in for a map pack. */
        const val TEST_REGION = "test-no-map"
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
