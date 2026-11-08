package org.klippelgo.chain

import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/**
 * The account's keys as the phones show them: every device that can sign for it, the changes to them waiting for a
 * majority, the guardian, and a guardian's recovery if one is waiting. Read from the account (`KlippelGoAccount`).
 */
data class AccountKeys(
    val keys: List<AccountKey>,
    /** Raised by every change of keys: a change or approval made before it no longer counts. */
    val epoch: Long,
    /** How many keys must approve a change: more than half. */
    val majority: Int,
    /** Who may start a recovery, or null for nobody. */
    val guardian: String?,
    val pending: List<KeyChange>,
    val recovery: PendingRecovery?,
    /** Whether this phone's key is still one of the account's. False once the other devices took it off. */
    val thisDeviceIsKey: Boolean,
    /** Whether the account exists on chain yet. Until its first operation it has this phone's key only. */
    val deployed: Boolean,
)

/** One of the account's keys. `signer` is the verifier's address followed by the key, in hex. */
data class AccountKey(val signer: String, val thisDevice: Boolean, val kind: KeyKind) {
    val fingerprint: String get() = KeyCodes.fingerprint(Numeric.hexStringToByteArray(signer))
}

enum class KeyKind {
    /** A phone's chip key, through `DeviceKeyVerifier`. */
    Device,

    /** Anything else: a passkey through a WebAuthn verifier, or a key this app does not know. */
    Other,
}

/**
 * A change of keys waiting for a majority. `id` is computed on this phone from what the change says, never taken
 * from the node: approving it approves exactly what the screen shows.
 */
data class KeyChange(
    val id: String,
    val add: List<String>,
    val remove: List<String>,
    /** Whether the change names the guardian, and whom (null for nobody). Otherwise it keeps the one there is. */
    val setsGuardian: Boolean,
    val guardian: String?,
    val approvals: Int,
    val approvedByThisDevice: Boolean,
) {
    /** Nothing to add, remove or name: approving it only cancels a recovery, as every change does. */
    val cancelsOnly: Boolean get() = add.isEmpty() && remove.isEmpty() && !setsGuardian
}

/** A guardian's recovery: the one key the account will have, and when it can be finished (Unix seconds). */
data class PendingRecovery(val signer: String, val readyAt: Long) {
    val fingerprint: String get() = KeyCodes.fingerprint(Numeric.hexStringToByteArray(signer))
}

/**
 * An account that named this account its guardian, with the recovery this account started on it, if any, and
 * whether that recovery can be finished now: by the chain's clock, which is the one the account goes by.
 */
data class GuardedAccount(val account: String, val recovery: PendingRecovery?, val ready: Boolean = false)

/**
 * How a new phone's key travels to the devices that add it, or to the guardian who recovers to it: a short text the
 * person copies or shares, and a fingerprint both screens show so that a key swapped on the way is seen.
 */
object KeyCodes {
    private const val PREFIX = "klippelgo-key:"

    /**
     * The text for a public key (x then y, 32 bytes each), with the device's name after a `#` when given. The name
     * is only a label for the person: nothing checks it, and it is never part of the key or its fingerprint.
     */
    fun code(publicKey: ByteArray, name: String? = null): String =
        PREFIX + Numeric.toHexStringNoPrefix(publicKey) + (label(name)?.let { "#" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")

    /** The device name a code carries, cleaned for showing, or null when it carries none. */
    fun nameOf(code: String): String? =
        code.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.let(::label)

    /**
     * A name fit to show: no control or format characters (so no direction overrides or zero-width marks that could
     * make it pose as something else), trimmed, at most [MAX_NAME] characters, never cut inside one. Null when nothing
     * is left.
     */
    fun label(name: String?): String? {
        val clean = name?.codePoints()?.toArray()
            ?.filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }
            ?.take(MAX_NAME)?.let { String(it.toIntArray(), 0, it.size) }?.trim()
        return clean?.takeIf { it.isNotEmpty() }
    }

    const val MAX_NAME = 40

    /**
     * The signer entry a code stands for, with this deployment's device-key verifier, or null when the text is no
     * code: not the prefix and 128 hex digits, or not a point on P-256 (a digit lost on the way).
     */
    fun signerOf(code: String, deviceKeyVerifier: String): ByteArray? {
        val hex = code.substringBefore('#').filterNot { it.isWhitespace() }.removePrefix(PREFIX)
        if (hex.length != 128 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        val key = Numeric.hexStringToByteArray(hex)
        val onCurve = runCatching {
            P256.DOMAIN.curve.createPoint(Numeric.toBigInt(key.copyOfRange(0, 32)), Numeric.toBigInt(key.copyOfRange(32, 64))).isValid
        }.getOrDefault(false)
        if (!onCurve) return null
        return Numeric.hexStringToByteArray(deviceKeyVerifier) + key
    }

    /**
     * Sixteen hex digits of the signer's hash, in groups of four. Making a key whose fingerprint matches another's
     * takes about 2^64 tries, so two screens showing the same one are showing the same key.
     */
    fun fingerprint(signer: ByteArray): String =
        Numeric.toHexStringNoPrefix(Hash.sha3(signer)).take(16).chunked(4).joinToString(" ")
}
