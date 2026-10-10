package org.klippelgo.chain

import org.web3j.crypto.Hash
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays rider and driver at the same time against a running local testnet, the way two emulators do: each side
 * runs on its own thread with its own key and only learns what the other did by reading the chain.
 *
 * Runs when KLIPPELGO_RPC is set, e.g.
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
        val driverPhone = KlippelGoClient(rpc, DevAccount.DRIVER.privateKey, deployment)
        val coin = riderPhone.brz
        val fare = coin.toBaseUnits(BigDecimal("23.40"))

        assertTrue(riderPhone.drivers().any { it.address == driverPhone.address && it.canAccept })
        val driverBefore = driverPhone.balanceOf(coin)
        val seenBefore = driverPhone.ridesAsDriver().toSet()

        val pool = Executors.newFixedThreadPool(2)

        // Driver phone: watch the chain for a new ride addressed to us, accept it and start it.
        val driverJob = pool.submit<BigInteger> {
            val id = poll("a new ride for the driver") { driverPhone.ridesAsDriver().firstOrNull { it !in seenBefore } }
            assertEquals(RideStatus.Open, driverPhone.ride(id).status)
            driverPhone.accept(id)
            driverPhone.start(id, Hash.sha3("pickup proof $id".toByteArray()))
            poll("the rider to confirm arrival") { id.takeIf { driverPhone.ride(id).status == RideStatus.Completed } }
            driverPhone.rate(id, 5)
            id
        }

        // Rider phone: open the ride, wait until the driver has started it, then confirm arrival.
        val riderJob = pool.submit<BigInteger> {
            val id = riderPhone.openRide(driverPhone.address, coin, fare, Hash.sha3("quote".toByteArray()))
            poll("the driver to start the ride") { id.takeIf { riderPhone.ride(id).status == RideStatus.Started } }
            riderPhone.complete(id)
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

    private fun <T : Any> poll(what: String, timeoutMs: Long = 60_000, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            check()?.let { return it }
            Thread.sleep(500)
        }
        error("Timed out waiting for $what")
    }
}
