package org.klippelgo.chain

import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.VoidResponse
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One person's account on several devices, and a guardian's recovery, through the client as the phones use it,
 * against a running local testnet and its bundler. Each device is a key of its own; the second and third are
 * joined to the account the first one made.
 *
 * Runs when KLIPPELGO_RPC is set (see [SmartAccountTest]). The recovery test moves the chain's clock two days on.
 */
class AccountKeysTest {
    private val rpc = System.getenv("KLIPPELGO_RPC").orEmpty()
    private val deployment = System.getenv("KLIPPELGO_DEPLOYMENT").orEmpty()
        .takeIf { it.isNotBlank() }?.let { Deployment.fromJson(File(it).readText()) } ?: Deployment.LOCAL_DEFAULT

    private fun key() = SoftwareDeviceKey(BigInteger(256, SecureRandom()).mod(P256.N - BigInteger.ONE) + BigInteger.ONE)

    private fun device(joined: String? = null, key: DeviceKey = key()) = KlippelGoClient(rpc, key, deployment, joined = joined)

    /** A person's first phone, its account on chain with reais to pay its gas. */
    private fun firstPhone(): KlippelGoClient = device().also {
        it.mint(it.brz, it.brz.toBaseUnits(BigDecimal("20.00")))
        it.ensureRegistered(KlippelGoClient.ROLE_RIDER)
    }

    private fun signer(c: KlippelGoClient): ByteArray = KeyCodes.signerOf(c.keyCode, deployment.deviceKeyVerifier)!!

    @Test
    fun aSecondAndThirdPhoneJoinAndTwoOfThreeTakeTheThirdOff() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val first = firstPhone()
        // The new phone shows its code; the first adds it. One key is a majority of one: done at once.
        val second = device()
        assertTrue(second.accountsWithThisKey().isEmpty())
        first.proposeKeyChange(add = listOf(signer(second)), remove = emptyList())
        // The new phone finds the account its key was added to, and from then on signs for it.
        assertEquals(listOf(first.address), second.accountsWithThisKey())
        val secondOnAccount = device(joined = first.address, key = second.account.key)
        assertEquals(first.address, secondOnAccount.address)
        assertTrue(secondOnAccount.keys().thisDeviceIsKey)

        // A third phone: two keys now, so the first's proposal waits for the second's approval.
        val third = device()
        first.proposeKeyChange(add = listOf(signer(third)), remove = emptyList())
        val waiting = secondOnAccount.keys()
        assertEquals(2, waiting.keys.size)
        assertEquals(2, waiting.majority)
        val change = waiting.pending.single()
        assertEquals(listOf(Numeric.toHexString(signer(third))), change.add)
        assertEquals(1, change.approvals)
        assertFalse(change.approvedByThisDevice)
        secondOnAccount.approveKeyChange(waiting.epoch, change)
        val thirdOnAccount = device(joined = first.address, key = third.account.key)
        val three = thirdOnAccount.keys()
        assertEquals(3, three.keys.size)
        assertEquals(2, three.majority)
        assertTrue(three.pending.isEmpty())
        // The account's gas comes from the account, whichever phone signs.
        thirdOnAccount.ensureRegistered(KlippelGoClient.ROLE_RIDER or KlippelGoClient.ROLE_DRIVER)

        // The third phone is taken. Alone it tries to lock the others out: it stays a proposal.
        thirdOnAccount.proposeKeyChange(add = emptyList(), remove = listOf(first.account.signer, second.account.signer))
        assertEquals(3, first.keys().keys.size)
        // The owner's two phones take it off.
        first.proposeKeyChange(add = emptyList(), remove = listOf(third.account.signer))
        val beforeApproval = secondOnAccount.keys()
        val takeOff = beforeApproval.pending.single { third.account.signer.let(Numeric::toHexString) in it.remove }
        secondOnAccount.approveKeyChange(beforeApproval.epoch, takeOff)
        val after = thirdOnAccount.keys()
        assertFalse(after.thisDeviceIsKey)
        assertEquals(2, after.keys.size)
        // The thief's proposal was made under the old keys and is gone.
        assertTrue(after.pending.isEmpty())
        // And the taken phone signs nothing for the account any more (4: the validator role, which it lacks).
        assertFailsWith<Exception> { thirdOnAccount.ensureRegistered(4) }
    }

    @Test
    fun aGuardianRecoversTheAccountToANewPhoneAfterTheDelayAndTheKeysCanCancel() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val phone = firstPhone()
        val friend = firstPhone()
        phone.proposeKeyChange(add = emptyList(), remove = emptyList(), setGuardian = true, guardian = friend.address)
        assertEquals(friend.address, phone.keys().guardian)
        assertEquals(listOf(phone.address), friend.guarded().map { it.account })

        // A recovery the person did not ask for: their phone cancels it with a change that changes nothing.
        val stranger = device()
        friend.startRecovery(phone.address, signer(stranger))
        assertEquals(KeyCodes.fingerprint(signer(stranger)), assertNotNull(phone.keys().recovery).fingerprint)
        phone.proposeKeyChange(add = emptyList(), remove = emptyList())
        assertNull(phone.keys().recovery)
        assertEquals(friend.address, phone.keys().guardian)

        // The phone is lost. The new one shows its code; the friend starts the recovery to it.
        val newPhone = device()
        friend.startRecovery(phone.address, signer(newPhone))
        assertEquals(listOf(phone.address), newPhone.recoveriesToThisKey().map { it.first })
        assertNotNull(friend.guarded().single().recovery)
        // Not before the delay.
        assertFailsWith<Exception> { friend.finishRecovery(phone.address) }
        increaseTime(2 * 86_400 + 60)
        friend.finishRecovery(phone.address)

        // The new phone finds the account, and is its only key.
        assertEquals(listOf(phone.address), newPhone.accountsWithThisKey())
        val recovered = device(joined = phone.address, key = newPhone.account.key).keys()
        assertEquals(1, recovered.keys.size)
        assertTrue(recovered.thisDeviceIsKey)
        assertFalse(phone.keys().thisDeviceIsKey)
        assertTrue(newPhone.recoveriesToThisKey().isEmpty())
    }

    @Test
    fun aChangeIsListedOnlyWhenItsIdMatchesWhatItSays() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val first = firstPhone()
        val second = device()
        first.proposeKeyChange(add = listOf(signer(second)), remove = emptyList())
        val joined = device(joined = first.address, key = second.account.key)
        joined.proposeKeyChange(add = emptyList(), remove = listOf(first.account.signer))
        val keys = first.keys()
        val change = keys.pending.single()
        // A change shown other than it was filed is refused before anything is signed.
        val altered = change.copy(remove = emptyList(), add = listOf(Numeric.toHexString(signer(device()))))
        assertFailsWith<IllegalStateException> { first.approveKeyChange(keys.epoch, altered) }
        assertEquals(2, first.keys().keys.size)
    }

    /** Moves the local testnet's clock on, as anvil allows, and mines a block at the new time. */
    private fun increaseTime(seconds: Long) {
        val http = HttpService(rpc)
        Request("evm_increaseTime", listOf(seconds), http, VoidResponse::class.java).send()
        Request("evm_mine", emptyList<Any>(), http, VoidResponse::class.java).send()
    }
}
