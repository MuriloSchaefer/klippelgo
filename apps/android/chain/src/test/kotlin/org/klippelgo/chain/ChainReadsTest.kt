package org.klippelgo.chain

import org.klippelgo.core.Bounds
import org.klippelgo.core.Coordinate
import org.klippelgo.core.regionBounds
import java.io.File
import org.web3j.crypto.Hash
import java.util.Optional
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.core.methods.response.VoidResponse
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a phone reads the chain without polling it whole: event scans that read only new blocks and survive a
 * restart, the check that says whether anything about an account happened, and rides read in one batch. And the
 * gossip cells a phone hears, which need no chain.
 *
 * The chain parts run when KLIPPELGO_RPC is set, as for [TwoPhonesRideTest].
 */
class ChainReadsTest {
    private val rpc = System.getenv("KLIPPELGO_RPC").orEmpty()
    private val deployment = System.getenv("KLIPPELGO_DEPLOYMENT").orEmpty()
        .takeIf { it.isNotBlank() }?.let { Deployment.fromJson(File(it).readText()) } ?: Deployment.LOCAL_DEFAULT

    @Test
    fun aDriverHearsTheCellsAroundThemAndARequestGoesToItsPickupsCell() {
        val centre = Coordinate(-249_555_000, -534_552_000)
        assertEquals("6g9fp", Matchmaker.cellAt(centre))
        // A region a request names lies inside one gossip cell: the cell is the region's prefix.
        assertEquals("6g9fp", Matchmaker.cellOf("6g9fp8"))
        val around = Matchmaker.around(centre)
        // The driver's cell and its eight neighbours, and nothing further.
        assertEquals(9, around.size)
        assertTrue(Matchmaker.cellAt(centre) in around)
        for (dLat in listOf(-439_000, 0, 439_000)) for (dLon in listOf(-439_000, 0, 439_000)) {
            assertTrue(Matchmaker.cellAt(Coordinate(centre.latE7 + dLat, centre.lonE7 + dLon)) in around)
        }
        assertFalse(Matchmaker.cellAt(Coordinate(centre.latE7 + 1_500_000, centre.lonE7)) in around)
        // Moving inside the cell changes nothing anyone can see: the same cells are heard from anywhere in it.
        val cell = regionBounds(Matchmaker.cellAt(centre) + "0")!!.southWest
        for (step in 0..20) {
            val inside = Coordinate(cell.latE7 + 10 + step * 20_000, cell.lonE7 + 10 + step * 20_000)
            if (Matchmaker.cellAt(inside) == Matchmaker.cellAt(centre)) assertEquals(around, Matchmaker.around(inside))
        }
        // A box one cell wide still meets both cells when it straddles their border.
        val box = Bounds(Coordinate(centre.latE7, centre.lonE7 - 10), Coordinate(centre.latE7, centre.lonE7 + 450_000))
        assertTrue(Matchmaker.covering(box).size >= 2)
    }

    @Test
    fun scansReadOnlyNewBlocksAndAreKeptAcrossARestart() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val rider = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment)
        val first = rider.ridesAsRider()
        // Asked again, only new blocks are read, and the answer is the same.
        assertEquals(first, rider.ridesAsRider())
        // A phone started again takes back what it found, checks it is the same chain, and finds the same rides.
        val again = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment)
        again.restoreScans(rider.savedScans())
        assertEquals(first, again.ridesAsRider())
        // What another chain's scans found (the anchor block's hash is not this chain's) is thrown away and read again.
        val other = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment)
        other.restoreScans(rider.savedScans().replace(Regex("\"0x[0-9a-f]{64}\""), "\"0x" + "ab".repeat(32) + "\""))
        assertEquals(first, other.ridesAsRider())
        // Rides come in one batch, the same as one by one.
        val some = first.take(5)
        assertEquals(some.map(rider::ride), rider.rides(some))
    }

    @Test
    fun aScanForTheNewestRidesStopsThereAndCanBeFinishedLater() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val all = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment).ridesAsRider()
        if (all.isEmpty()) return println("the rider has no rides yet, skipping")
        // Asked for the newest one, a new phone finds it, and only rides at least as new as some it kept.
        val phone = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment)
        val newest = phone.ridesAsRider(1)
        assertEquals(all.first(), newest.first())
        assertTrue(newest.all { it in all })
        // Restarted with what it read so far, and asked for everything, it goes on back to the start.
        val again = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment)
        again.restoreScans(phone.savedScans())
        assertEquals(all, again.ridesAsRider())
        // What an earlier version saved, which has no `from`, had read from the start and is taken as whole.
        val old = KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment)
        old.restoreScans(again.savedScans().replace(Regex(",?\"from\":-?\\d+"), ""))
        assertEquals(all, old.ridesAsRider())
    }

    @Test
    fun theChangeCheckSeesThisAccountsEventsAndNoOneElses() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val me = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val someoneElse = KlippelGoClient(rpc, DevAccount.DRIVER_2.key(), deployment)
        val before = me.blockNumber().toLong()
        // What happened before the test, such as a map pack published just before, is taken in first: the check
        // reads the blocks before `before` again, and would count it.
        me.changedSince(before - 1, emptySet(), setOf(me.address))
        // Nothing new is no change.
        assertFalse(me.changedSince(me.blockNumber().toLong(), emptySet(), setOf(me.address)).second)
        someoneElse.mint(someoneElse.brz, someoneElse.brz.toBaseUnits(BigDecimal.ONE))
        val (afterTheirs, theirs) = me.changedSince(before, emptySet(), setOf(me.address))
        assertTrue(afterTheirs > before)
        assertFalse(theirs, "money arriving in another account is no change for this one")
        me.mint(me.brz, me.brz.toBaseUnits(BigDecimal.ONE))
        assertTrue(me.changedSince(afterTheirs, emptySet(), setOf(me.address)).second, "money arriving here is")
    }

    @Test
    fun theChangeCheckSeesTheRidesItWatchesAndRidesOpenedNamingItsAccounts() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val rider = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val driver = KlippelGoClient(rpc, DevAccount.DRIVER_2.key(), deployment)
        // Each question asked by a phone of its own: a check remembers what it already reported. Each phone first
        // takes in what happened before the test, such as a change of the escrow's settings by another test,
        // which the check would count as it reads the blocks before `before` again.
        val nobody = "0x" + "00".repeat(19) + "01"
        val before = rider.blockNumber().toLong()
        val onlookers = ArrayDeque(List(6) { KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment).also { it.changedSince(before - 1, emptySet(), setOf(nobody)) } })
        fun onlooker() = onlookers.removeFirst()
        val id = rider.openRide(driver.address, rider.brz, rider.brz.toBaseUnits(BigDecimal("10.00")), Hash.sha3("ride for the change check ${System.nanoTime()}".toByteArray()))
        assertTrue(onlooker().changedSince(before, emptySet(), setOf(driver.address)).second, "a ride opened naming a watched account is a change")
        assertTrue(onlooker().changedSince(before, emptySet(), setOf(rider.address)).second, "so is one a watched account opened as the rider")
        assertFalse(onlooker().changedSince(before, emptySet(), setOf(nobody)).second, "a ride opened naming others is not")
        val opened = rider.blockNumber().toLong()
        rider.cancel(id)
        assertTrue(onlooker().changedSince(opened, setOf(id), setOf(nobody)).second, "an event about a watched ride is a change")
        assertFalse(onlooker().changedSince(opened, setOf(id + BigInteger.valueOf(1_000_000)), setOf(nobody)).second, "one about another ride is not")
        // Asked again over the same blocks, the same phone reports it once.
        val asker = onlooker()
        assertTrue(asker.changedSince(opened, setOf(id), setOf(nobody)).second)
        assertFalse(asker.changedSince(opened, setOf(id), setOf(nobody)).second)
    }

    @Test
    fun theChangeCheckSeesAChangeOfTheEscrowsSettings() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val me = KlippelGoClient(rpc, DevAccount.RIDER_2.key(), deployment)
        val before = me.blockNumber().toLong()
        me.changedSince(before - 1, emptySet(), setOf(me.address))
        // The governor sets a coin's cap to what it already is: a `CapSet` that changes nothing else.
        val web3 = Web3j.build(HttpService(rpc))
        fun anvil(method: String, vararg params: Any) =
            Request(method, params.toList(), HttpService(rpc), VoidResponse::class.java).send()
        val capOf = FunctionEncoder.encode(Function("cap", listOf(Address(deployment.brz)), emptyList()))
        val cap = Numeric.toBigInt(web3.ethCall(Transaction.createEthCallTransaction(null, deployment.escrow, capOf), DefaultBlockParameterName.LATEST).send().value)
        val setCap = FunctionEncoder.encode(Function("setCap", listOf(Address(deployment.brz), Uint256(cap)), emptyList()))
        anvil("anvil_setBalance", deployment.governor, "0xde0b6b3a7640000")
        anvil("anvil_impersonateAccount", deployment.governor)
        try {
            val tx = web3.ethSendTransaction(Transaction.createFunctionCallTransaction(deployment.governor, null, null, BigInteger.valueOf(200_000), deployment.escrow, setCap)).send()
            assertFalse(tx.hasError(), tx.error?.message)
            var receipt: Optional<TransactionReceipt>
            do {
                Thread.sleep(250)
                receipt = web3.ethGetTransactionReceipt(tx.transactionHash).send().transactionReceipt
            } while (receipt.isEmpty)
            assertTrue(receipt.get().isStatusOK)
        } finally {
            anvil("anvil_stopImpersonatingAccount", deployment.governor)
        }
        assertTrue(me.changedSince(before, emptySet(), setOf(me.address)).second, "a change of the escrow's settings is a change for every phone")
    }
}
