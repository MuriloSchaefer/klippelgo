package org.klippelgo.tests.security

import org.klippelgo.chain.KeyCodes
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One of a person's three devices is taken. With it, the thief tries what would lock the owner out: take the other
 * two keys off and add one of their own. Changing the keys needs a majority of them, so it stays a proposal: the app
 * shows it, never applies it, and the owner's two devices take the taken one off.
 */
class TakenPhoneTest : Scenario() {
    @Test
    fun `a taken phone cannot lock the others out, and the other two take it off`() {
        // The app is the person's first phone; a second and a third device join it.
        phone.launch(fresh = true, ownKey = true)
        app.welcome.start()
        app.createAccount.fill("Clara", "rider", stack.rpcFromPhone)
        app.createAccount.submit()
        app.funding.shown()
        app.funding.add()
        app.whereTo.shown()
        app.tabs.open("profile")
        app.profile.account()
        val account = app.account.address().lowercase()
        waitUntil("the phone's account to be made and registered", 120_000) {
            true.takeIf { chain.rolesOf(account) and KlippelGoClient.ROLE_RIDER != 0 }
        }
        val second = device()
        app.account.addDevice(second.keyCode)
        app.account.awaitKeys(2)
        val secondOnAccount = device(joined = account, key = second.account.key)
        val third = device()
        app.account.addDevice(third.keyCode)
        app.account.awaitChange("adds the third device") { it.adds == 1 }
        secondOnAccount.keys().let { secondOnAccount.approveKeyChange(it.epoch, it.pending.single()) }
        app.account.awaitKeys(3)
        val taken = device(joined = account, key = third.account.key)

        // The bad message: the taken phone proposes taking the other two off and adding the thief's own key.
        val thief = newKey()
        val thiefSigner = KeyCodes.signerOf(KeyCodes.code(thief.publicKey), stack.deployment.deviceKeyVerifier)!!
        val appFingerprint = app.account.thisDevice()!!
        val appSigner = secondOnAccount.keys().keys.single { it.fingerprint.replace(" ", "") == appFingerprint }.signer
        taken.proposeKeyChange(add = listOf(thiefSigner), remove = listOf(org.web3j.utils.Numeric.hexStringToByteArray(appSigner), signerOf(second)))
        val grab = app.account.awaitChange("takes two devices off") { it.removes == 2 }
        assertEquals(1, grab.adds)
        assertTrue(grab.approvals.startsWith("1"), grab.approvals)
        // A while later it is still only a proposal: every key the owner had is there, and the thief's is not.
        Thread.sleep(5_000)
        val keys = secondOnAccount.keys()
        assertEquals(3, keys.keys.size)
        assertFalse(keys.keys.any { it.signer.equals(org.web3j.utils.Numeric.toHexString(thiefSigner), ignoreCase = true) })
        assertEquals(3, app.account.keys().size)

        // The good one: the owner's second device proposes taking the taken phone off, and the app approves.
        secondOnAccount.proposeKeyChange(add = emptyList(), remove = listOf(signerOf(third)))
        val takeOff = app.account.awaitChange("takes the taken phone off") { it.removes == 1 && it.canApprove }
        app.account.approve(takeOff.id)
        app.account.awaitKeys(2)
        // The thief's proposal was made under the old keys, and is gone with them.
        waitUntil("the thief's proposal to leave the screen") { true.takeIf { app.account.changes().isEmpty() } }
        assertFalse(taken.keys().thisDeviceIsKey)
        // And the taken phone signs nothing for the account any more.
        assertFailsWith<Exception> { taken.proposeKeyChange(add = listOf(thiefSigner), remove = emptyList()) }
    }
}
