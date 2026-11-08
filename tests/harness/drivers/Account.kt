package org.klippelgo.tests.drivers

import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.tests.Phone

/**
 * The notice above every screen while the account needs the person: a guardian's recovery waiting, a change of keys
 * waiting for this phone's approval, or this phone taken off the account.
 */
class AccountNotice(phone: Phone) : Component(phone, Tags.ACCOUNT_NOTICE) {
    /** `recovery`, `approve`, `removed`, or `none` when there is no notice. */
    fun status(): String =
        phone.screen().startingWith(Tags.accountNoticeStatus("")).firstOrNull()?.tag?.removePrefix(Tags.accountNoticeStatus("")) ?: "none"

    /** Waits until the notice says `status` (`none` waits for it to leave), as the app reads the account again. */
    fun awaitStatus(status: String, timeoutMs: Long = 90_000): Boolean {
        phone.await("the account notice to say $status", timeoutMs) { screen ->
            val now = screen.startingWith(Tags.accountNoticeStatus("")).firstOrNull()?.tag?.removePrefix(Tags.accountNoticeStatus("")) ?: "none"
            true.takeIf { now == status }
        }
        return true
    }

    fun text(): String? = readOrNull(Tags.ACCOUNT_NOTICE_TEXT)

    /** Opens the account screen from the notice. */
    fun open() = phone.tap(Tags.ACCOUNT_NOTICE_OPEN)
}

/** What a change of keys waiting for a majority says, line by line, and its approvals. */
data class KeyChangeInfo(val id: String, val adds: Int, val removes: Int, val guardian: Boolean, val cancel: Boolean, val approvals: String, val canApprove: Boolean)

/**
 * The account screen: the devices that sign for the account, the changes to them waiting for a majority, the
 * guardian, a guardian's recovery, and the accounts this one guards. Keys are named by their fingerprint, as both
 * phones show it, without spaces.
 */
class AccountScreen(phone: Phone) : Component(phone, Tags.ACCOUNT) {
    /** The account's whole address. */
    fun address(): String = read(Tags.ACCOUNT_ADDRESS)

    /** The fingerprints of the account's keys, as listed. The list is scrolled to first, as a person would. */
    fun keys(): List<String> = phone.reach(Tags.ACCOUNT_MAJORITY).let { phone.screen() }.startingWith("account.key.")
        .map { it.tag.removePrefix("account.key.").substringBefore('.') }.distinct()

    /** The fingerprint of this phone's own key, or null when this phone is no longer one of them. */
    fun thisDevice(): String? = phone.reach(Tags.ACCOUNT_MAJORITY).let { phone.screen() }.startingWith("account.key.").firstOrNull { it.tag.endsWith(".this") }
        ?.tag?.removePrefix("account.key.")?.substringBefore('.')

    /** Waits until the screen lists `count` keys, as the app reads the account again. */
    fun awaitKeys(count: Int, timeoutMs: Long = 90_000): List<String> =
        phone.await("the account screen to list $count keys", timeoutMs) { _ -> keys().takeIf { it.size == count } }

    /** "Changes need 2 of the 3 devices", in the app's language. */
    fun majority(): String = read(Tags.ACCOUNT_MAJORITY)

    /** Whether the screen recommends more devices: fewer than three. */
    fun recommendsMore(): Boolean = phone.find(Tags.ACCOUNT_RECOMMEND) != null

    /**
     * Pastes a new device's code and adds it. Gives the fingerprint the screen showed for it before adding, which a
     * person compares with the one the new device shows.
     */
    fun addDevice(code: String): String {
        phone.type(Tags.ACCOUNT_ADD_CODE, code)
        val shown = phone.reach(Tags.ACCOUNT_ADD_FINGERPRINT).text
        phone.tap(Tags.ACCOUNT_ADD)
        // Asked once more, with the device's fingerprint: the same one, then added.
        val confirmed = phone.awaitTag(Tags.ACCOUNT_ADD_CONFIRM_FINGERPRINT).text
        check(confirmed.replace(" ", "") in shown.replace(" ", "")) { "the confirmation shows $confirmed, the field showed $shown" }
        phone.tap(Tags.ACCOUNT_ADD_CONFIRM_YES)
        return Regex("""([0-9a-f]{4} ){3}[0-9a-f]{4}""").find(shown)?.value?.replace(" ", "") ?: shown
    }

    /** The name this phone lists the key with this fingerprint by, or null when it knows none. */
    fun keyName(fingerprint: String): String? = phone.reach(Tags.accountKey(fingerprint)).let { phone.find(Tags.accountKeyName(fingerprint))?.text }

    /** Renames this phone, as its QR code and code will say from now on. */
    fun rename(name: String) {
        phone.type(Tags.ACCOUNT_THIS_NAME, name)
        phone.tap(Tags.ACCOUNT_THIS_NAME_SAVE)
    }

    /** Copies this phone's code, as the Copy button under it does. */
    fun copyCode() {
        showCode()
        phone.tap(Tags.ACCOUNT_THIS_COPY)
        // The emulator hands the clipboard to the computer and back: give it a moment.
        Thread.sleep(1_500)
    }

    /** Pastes what was copied into the add-device field, with the Paste button beside the scanner, and gives what it holds. */
    fun pasteCode(): String {
        phone.tap(Tags.ACCOUNT_ADD_PASTE)
        Thread.sleep(500)
        return read(Tags.ACCOUNT_ADD_CODE)
    }

    /** Whether the screen shows this phone's QR code. */
    fun showsQr(): Boolean = runCatching { showCode(); phone.reach(Tags.ACCOUNT_THIS_QR) }.isSuccess

    /** Shows this phone's QR code and code, which stay hidden until asked for, if they are not shown yet. */
    fun showCode() {
        // Shown already when the button that shows it is gone: looked for down the whole screen once.
        if (runCatching { phone.reach(Tags.ACCOUNT_THIS_SHOW, 2_000) }.isFailure) return
        phone.tap(Tags.ACCOUNT_THIS_SHOW)
        phone.reach(Tags.ACCOUNT_THIS_CODE)
    }

    /** This phone's own code, to add it to another account of the person's, shown first if it is not. */
    fun thisCode(): String = showCode().let { phone.reach(Tags.ACCOUNT_THIS_CODE).text.trim() }

    /** The fingerprint shown under this phone's code, without spaces. */
    fun thisFingerprint(): String = showCode().let { thisFingerprintShown() }

    private fun thisFingerprintShown(): String =
        Regex("""([0-9a-f]{4} ){3}[0-9a-f]{4}""").find(phone.reach(Tags.ACCOUNT_THIS_FINGERPRINT).text)?.value?.replace(" ", "") ?: ""

    /** Pastes text into the add-device field and gives what the screen says is wrong with it, or null if it is a code. */
    fun paste(text: String): String? {
        phone.type(Tags.ACCOUNT_ADD_CODE, text)
        Thread.sleep(500)
        return readOrNull(Tags.ACCOUNT_ADD_INVALID)
    }

    /** Other accounts this phone's key was added to, which it can sign for instead. */
    fun otherAccounts(): List<String> = phone.screen().startingWith("account.other.")
        .map { it.tag.removePrefix("account.other.").substringBefore('.') }.distinct()

    fun awaitOtherAccount(account: String, timeoutMs: Long = 90_000): Boolean {
        phone.reach(Tags.accountOther(account), timeoutMs)
        return true
    }

    /** Signs for `account` from now on, which the person checked by its address. */
    fun useAccount(account: String) = phone.tap(Tags.accountOtherUse(account))

    /** Pastes a code, taps Add, and backs out of the confirmation: nothing is proposed. Gives the name it showed. */
    fun addDeviceButCancel(code: String): String? {
        phone.type(Tags.ACCOUNT_ADD_CODE, code)
        phone.tap(Tags.ACCOUNT_ADD)
        phone.awaitTag(Tags.ACCOUNT_ADD_CONFIRM)
        val name = phone.find(Tags.ACCOUNT_ADD_CONFIRM_NAME)?.text
        phone.tap(Tags.ACCOUNT_ADD_CONFIRM_NO)
        phone.awaitGone(Tags.ACCOUNT_ADD_CONFIRM)
        return name
    }

    /** Proposes taking the key with this fingerprint off the account. */
    fun removeDevice(fingerprint: String) = phone.tap(Tags.accountKeyRemove(fingerprint))

    /** The ids of the changes waiting for approval, at the top of the screen, which is scrolled to first. */
    fun changes(): List<String> = phone.reach(Tags.ACCOUNT_ADDRESS).let { phone.screen() }.startingWith("account.change.")
        .map { it.tag.removePrefix("account.change.").substringBefore('.') }.distinct()

    fun change(id: String): KeyChangeInfo {
        val nodes = phone.screen().startingWith(Tags.accountChange(id) + ".")
        fun count(kind: String) = nodes.count { it.tag == Tags.accountChangeLine(id, kind) }
        return KeyChangeInfo(
            id, count("add"), count("remove"), count("guardian") > 0, count("cancel") > 0,
            approvals = read(Tags.accountChangeApprovals(id)),
            canApprove = nodes.any { it.tag == Tags.accountChangeApprove(id) },
        )
    }

    /** Waits until a change shows that `matches`, and gives it. */
    internal fun awaitChange(what: String, timeoutMs: Long = 90_000, matches: (KeyChangeInfo) -> Boolean): KeyChangeInfo =
        phone.await("a change of keys that $what", timeoutMs) { _ -> changes().map(::change).firstOrNull(matches) }

    /** Approves a change another device proposed. */
    fun approve(id: String) = phone.tap(Tags.accountChangeApprove(id))

    /** The guardian as the screen shows it: a shortened address, or the word for none. */
    fun guardian(): String = read(Tags.ACCOUNT_GUARDIAN_NOW)

    fun setGuardian(address: String) {
        phone.type(Tags.ACCOUNT_GUARDIAN_INPUT, address)
        phone.tap(Tags.ACCOUNT_GUARDIAN_SET)
    }

    fun clearGuardian() = phone.tap(Tags.ACCOUNT_GUARDIAN_CLEAR)

    /** Whether a guardian's recovery is waiting on this account. */
    fun recoveryShown(): Boolean = phone.find(Tags.ACCOUNT_RECOVERY) != null

    /** Cancels the guardian's recovery, or approves a cancel another device proposed. */
    fun cancelRecovery() = phone.tap(Tags.ACCOUNT_RECOVERY_CANCEL)

    /** Whether the screen says this phone was taken off the account. */
    fun removed(): Boolean = phone.find(Tags.ACCOUNT_REMOVED) != null

    /** Leaves the account this phone was taken off, for a new one of its own. */
    fun leave() = phone.tap(Tags.ACCOUNT_LEAVE)

    /** The accounts that named this one their guardian. */
    fun guarded(): List<String> = phone.screen().startingWith("account.guarded.")
        .map { it.tag.removePrefix("account.guarded.").substringBefore('.') }.distinct()

    /** Waits until `account` is listed among those this one guards. */
    fun awaitGuarded(account: String, timeoutMs: Long = 90_000): Boolean {
        // At the foot of the screen: scrolled to, as a person would.
        phone.reach(Tags.guarded(account), timeoutMs)
        return true
    }

    /** As guardian, starts recovering `account` to the new device whose code the person sent. Gives the fingerprint shown. */
    fun startRecovery(account: String, code: String): String {
        phone.type(Tags.guardedCode(account), code)
        val shown = phone.reach(Tags.guardedFingerprint(account)).text
        phone.tap(Tags.guardedStart(account))
        return Regex("""([0-9a-f]{4} ){3}[0-9a-f]{4}""").find(shown)?.value?.replace(" ", "") ?: shown
    }

    /** `waiting`, `ready`, or `none` for the recovery this account started on `account`. */
    fun recovery(account: String): String = when {
        phone.find(Tags.guardedRecovery(account, "ready")) != null -> "ready"
        phone.find(Tags.guardedRecovery(account, "waiting")) != null -> "waiting"
        else -> "none"
    }

    fun awaitRecovery(account: String, state: String, timeoutMs: Long = 90_000): Boolean {
        phone.reach(Tags.guardedRecovery(account, state), timeoutMs)
        return true
    }

    fun finishRecovery(account: String) = phone.tap(Tags.guardedFinish(account))

    /**
     * Back to where the person was, and waits for the screen to leave. A tap that lands while the page is still
     * moving from a scroll only stops it, as it would for a person, who taps again.
     */
    fun close() {
        repeat(3) {
            phone.tap(Tags.BACK)
            if (runCatching { phone.awaitGone(tag, 3_000) }.isSuccess) return
        }
        phone.awaitGone(tag)
    }
}

/**
 * Over every screen: an account added this phone's key soon after its code went out, and asks whether to join it,
 * listing its devices by fingerprint.
 */
class JoinOffer(phone: Phone) : Component(phone, Tags.JOIN_OFFER) {
    fun awaitShown(timeoutMs: Long = 90_000): Boolean {
        phone.awaitTag(tag, timeoutMs)
        return true
    }

    /** The address of the account asking. */
    fun account(): String = read(Tags.JOIN_OFFER_ACCOUNT).trim().lowercase()

    /** Its devices' fingerprints, without spaces. */
    fun keys(): List<String> = phone.screen().startingWith(Tags.joinOfferKey("")).map { it.tag.removePrefix(Tags.joinOfferKey("")) }

    fun accept() = phone.tap(Tags.JOIN_OFFER_ACCEPT)

    fun decline() = phone.tap(Tags.JOIN_OFFER_DECLINE)
}
