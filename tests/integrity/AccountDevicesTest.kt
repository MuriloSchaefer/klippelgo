package org.klippelgo.tests.integrity

import org.klippelgo.chain.KeyCodes
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import org.web3j.utils.Numeric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One account on several devices, and a guardian who recovers it: the app is one of the person's phones, the other
 * devices and the guardian are played in process, each with a key of its own. What the screen shows is checked
 * against what the account says on chain.
 */
class AccountDevicesTest : Scenario() {
    /** The app as a real person's phone, with a key of its own and an account its first operation made. */
    private fun ownPhone(name: String = "Clara"): String {
        phone.launch(fresh = true, ownKey = true)
        app.welcome.start()
        app.createAccount.fill(name, "rider", stack.rpcFromPhone)
        app.createAccount.submit()
        app.funding.shown()
        app.funding.add()
        app.whereTo.shown()
        app.tabs.open("profile")
        app.profile.account()
        app.account.shown()
        val account = app.account.address().lowercase()
        waitUntil("the phone's account to be made and registered", 120_000) {
            true.takeIf { chain.rolesOf(account) and KlippelGoClient.ROLE_RIDER != 0 }
        }
        return account
    }

    private fun fingerprint(device: KlippelGoClient) = KeyCodes.fingerprint(signerOf(device)).replace(" ", "")

    @Test
    fun `a phone adds a second and a third device, and approves taking one off`() {
        val account = ownPhone()
        assertEquals(1, app.account.awaitKeys(1).size)
        assertNotNull(app.account.thisDevice())
        assertTrue(app.account.recommendsMore())
        // The phone shows its QR code and its name, which go along with its code.
        assertTrue(app.account.showsQr())
        app.account.rename("Celular da Clara")
        waitUntil("the code to carry the new name") { true.takeIf { KeyCodes.nameOf(app.account.thisCode()) == "Celular da Clara" } }
        assertEquals("Celular da Clara", app.account.keyName(app.account.thisDevice()!!))
        // Copy and Paste carry a code without the camera: this phone's own, pasted back, is the same code, and a key the
        // account has already, which it refuses to add.
        val code = app.account.thisCode()
        // The emulator shares its clipboard with the computer it runs on, which can put something else there: copied and
        // pasted again until the round trip carries the code, as a person would.
        waitUntil("the pasted code to be the one copied", 60_000) {
            app.account.copyCode()
            true.takeIf { app.account.pasteCode().trim() == code }
        }
        // The phone shows its own code, whose fingerprint is the one its key is listed under.
        assertEquals(app.account.thisDevice(), app.account.thisFingerprint())
        assertEquals(app.account.thisFingerprint(), KeyCodes.fingerprint(assertNotNull(KeyCodes.signerOf(app.account.thisCode(), stack.deployment.deviceKeyVerifier))).replace(" ", ""))

        // The second device shows its code; the phone adds it, and the screen shows the same fingerprint the device does.
        // Its fingerprint pasted instead is said to be one, and cannot be added.
        val second = device()
        assertNotNull(app.account.paste(KeyCodes.fingerprint(signerOf(second))))
        // Backing out of the confirmation proposes nothing.
        assertEquals("Tablet da Clara", app.account.addDeviceButCancel(KeyCodes.code(second.account.key.publicKey, "Tablet da Clara")))
        assertTrue(second.accountsWithThisKey().isEmpty())
        // The code carries the device's name, as its QR code does: the phone lists the device by it.
        assertEquals(fingerprint(second), app.account.addDevice(KeyCodes.code(second.account.key.publicKey, "Tablet da Clara")))
        val secondOnAccount = device(joined = account, key = second.account.key)
        waitUntil("the second device to be one of the account's keys") { true.takeIf { secondOnAccount.keys().thisDeviceIsKey } }
        assertEquals(listOf(account), second.accountsWithThisKey())
        app.account.awaitKeys(2)
        assertEquals("Tablet da Clara", app.account.keyName(fingerprint(second)))

        // A third: two keys now, so the phone's proposal waits for the second device's approval.
        val third = device()
        app.account.addDevice(third.keyCode)
        val waiting = app.account.awaitChange("adds a device") { it.adds == 1 }
        assertFalse(waiting.canApprove, "the phone's own proposal is already its approval")
        assertTrue(waiting.approvals.startsWith("1"), waiting.approvals)
        val read = secondOnAccount.keys()
        secondOnAccount.approveKeyChange(read.epoch, read.pending.single())
        assertEquals(3, app.account.awaitKeys(3).size)
        assertFalse(app.account.recommendsMore())

        // The third is lost. The second proposes taking it off; the phone sees it and approves.
        secondOnAccount.proposeKeyChange(add = emptyList(), remove = listOf(signerOf(third)))
        val takeOff = app.account.awaitChange("takes a device off") { it.removes == 1 && it.canApprove }
        app.account.approve(takeOff.id)
        assertEquals(2, app.account.awaitKeys(2).size)
        assertFalse(fingerprint(third) in app.account.keys())
        assertFalse(device(joined = account, key = third.account.key).keys().thisDeviceIsKey)
        assertTrue(app.account.changes().isEmpty())
    }

    @Test
    fun `the phone names a guardian, and cancels a recovery it did not ask for`() {
        val account = ownPhone()
        val friend = personWithAccount()
        app.account.setGuardian(friend.address)
        // One device is a majority of one: done at once.
        waitUntil("the guardian to be named on chain") { true.takeIf { device(joined = account).keys().guardian == friend.address } }
        waitUntil("the screen to show the guardian") { true.takeIf { app.account.guardian().lowercase() == Peer.short(friend.address) } }
        assertEquals(listOf(account), friend.guarded().map { it.account })

        // A recovery nobody asked for: the notice says so over any screen, and the phone cancels it.
        app.account.close()
        friend.startRecovery(account, signerOf(device()))
        app.accountNotice.awaitStatus("recovery")
        app.accountNotice.open()
        waitUntil("the account screen to show the recovery") { true.takeIf { app.account.recoveryShown() } }
        app.account.cancelRecovery()
        waitUntil("the recovery to be cancelled on chain") { true.takeIf { device(joined = account).keys().recovery == null } }
        app.account.close()
        app.accountNotice.awaitStatus("none")
        // Cancelling changed nothing else: the guardian is still the friend.
        assertEquals(friend.address, device(joined = account).keys().guardian)
    }

    @Test
    fun `a new phone joins an account its guardian recovered to it`() {
        // The person's account, on a phone now lost, with a friend as guardian.
        val lost = personWithAccount()
        val friend = personWithAccount()
        lost.proposeKeyChange(add = emptyList(), remove = emptyList(), setGuardian = true, guardian = friend.address)

        // The new phone shows its code; the friend starts the recovery to it.
        phone.launch(fresh = true, ownKey = true)
        app.welcome.start()
        app.createAccount.fill("Clara", "rider", stack.rpcFromPhone)
        app.createAccount.join()
        app.join.shown()
        val code = app.join.code()
        val signer = assertNotNull(KeyCodes.signerOf(code, stack.deployment.deviceKeyVerifier))
        assertEquals(KeyCodes.fingerprint(signer).replace(" ", ""), app.join.fingerprint())
        friend.startRecovery(lost.address, signer)
        app.join.awaitRecovery(lost.address)
        assertTrue(app.join.found().isEmpty())

        // Two days later the friend finishes it, and the new phone finds the account.
        passTime(2 * 86_400 + 60)
        friend.finishRecovery(lost.address)
        app.join.awaitFound(lost.address)
        app.join.use(lost.address)
        app.whereTo.shown()

        // The phone signs for the recovered account now, as its only key.
        app.tabs.open("profile")
        assertEquals(Peer.short(lost.address), app.profile.address().lowercase())
        app.profile.account()
        assertEquals(lost.address, app.account.address().lowercase())
        assertEquals(1, app.account.awaitKeys(1).size)
        assertEquals(KeyCodes.fingerprint(signer).replace(" ", ""), app.account.thisDevice())
        assertFalse(lost.keys().thisDeviceIsKey)
        assertEquals(listOf(Numeric.toHexString(signer)), device(joined = lost.address).keys().keys.map { it.signer })
    }

    @Test
    fun `as a guardian, the phone recovers a friend's account to the friend's new phone`() {
        val account = ownPhone()
        val friendsLost = personWithAccount()
        friendsLost.proposeKeyChange(add = emptyList(), remove = emptyList(), setGuardian = true, guardian = account)
        // The account screen reads whom this account guards while it is open.
        app.account.close()
        app.profile.account()
        app.account.awaitGuarded(friendsLost.address)

        val friendsNew = device()
        assertEquals(fingerprint(friendsNew), app.account.startRecovery(friendsLost.address, friendsNew.keyCode))
        app.account.awaitRecovery(friendsLost.address, "waiting")
        assertNotNull(friendsLost.keys().recovery)

        passTime(2 * 86_400 + 60)
        // The screen says it is ready at its next read, and the phone finishes it.
        app.account.close()
        app.profile.account()
        app.account.awaitRecovery(friendsLost.address, "ready")
        app.account.finishRecovery(friendsLost.address)
        waitUntil("the friend's account to have only the new phone's key") {
            true.takeIf { device(joined = friendsLost.address, key = friendsNew.account.key).keys().let { it.thisDeviceIsKey && it.keys.size == 1 } }
        }
        assertNull(friendsLost.keys().recovery)
    }

    @Test
    fun `a phone added to the person's other account is asked, joins it, and both list the same devices`() {
        val own = ownPhone()
        val other = personWithAccount()
        // This phone shows its code; the other account's device reads it and adds it.
        val code = app.account.thisCode()
        val signer = assertNotNull(KeyCodes.signerOf(code, stack.deployment.deviceKeyVerifier))
        other.proposeKeyChange(add = listOf(signer), remove = emptyList())

        // Over the screen, the phone asks whether to join, listing that account's devices: the adding one and itself.
        app.joinOffer.awaitShown()
        assertEquals(other.address, app.joinOffer.account())
        assertEquals(setOf(fingerprint(other), KeyCodes.fingerprint(signer).replace(" ", "")), app.joinOffer.keys().toSet())
        app.joinOffer.accept()
        waitUntil("the phone to sign for the other account") { true.takeIf { runCatching { app.account.address().lowercase() }.getOrNull() == other.address } }
        // Both list the same two devices.
        assertEquals(2, app.account.awaitKeys(2).size)
        assertEquals(2, device(joined = other.address).keys().keys.size)

        // And back: its own account is offered under the other accounts.
        app.account.awaitOtherAccount(own)
        app.account.useAccount(own)
        waitUntil("the phone to sign for its own account again") { true.takeIf { runCatching { app.account.address().lowercase() }.getOrNull() == own } }
        // It asked once: the account it left does not ask again.
        Thread.sleep(35_000)
        assertFalse(app.joinOffer.visible())
    }

    @Test
    fun `an account that added this phone before its code went out is not offered, and not now is not asked again`() {
        val account = ownPhone()
        // A stranger reads this phone's key off the chain (the account's keys are public) and adds it to an account of
        // theirs. The phone has not shown its code: opening the account screen does not show it.
        val signer = Numeric.hexStringToByteArray(device(joined = account).signersOf(account).single())
        val stranger = personWithAccount()
        stranger.proposeKeyChange(add = listOf(signer), remove = emptyList())
        // A full read later the phone shows its code: the stranger's add came before, and is never asked about.
        Thread.sleep(35_000)
        assertEquals(KeyCodes.fingerprint(signer).replace(" ", ""), app.account.thisFingerprint())
        Thread.sleep(35_000)
        assertFalse(app.joinOffer.visible(), "an account that added the key before the code went out was offered")
        app.account.awaitOtherAccount(stranger.address)

        // An account that adds it now is asked about; not now, and it does not ask again.
        val other = personWithAccount()
        other.proposeKeyChange(add = listOf(signer), remove = emptyList())
        app.joinOffer.awaitShown()
        assertEquals(other.address, app.joinOffer.account())
        app.joinOffer.decline()
        app.joinOffer.gone()
        Thread.sleep(35_000)
        assertFalse(app.joinOffer.visible(), "an account the person said not now to asked again")
        app.account.awaitOtherAccount(other.address)
    }
}
