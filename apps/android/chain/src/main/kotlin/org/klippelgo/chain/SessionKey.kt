package org.klippelgo.chain

import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric

/**
 * What lets a short-lived key speak for an account on gossip: the account's own key (the phone's chip) signed
 * that this session key may, until `expiresAt`. Peers check it once, on chain, with the account's
 * `isValidSignature` (ERC-1271), and then only check that each message was signed by the session key.
 *
 * So the chip's key, which is to ask for a fingerprint once recovery exists (limit P20), signs once a day and
 * never a gossip message; a session key that leaks speaks for the account on gossip until it expires, and can
 * move no money: the account's operations are signed by the chip's key alone.
 */
data class SessionCertificate(
    val account: String,
    val session: String,
    val expiresAt: Long,
    /** The account's signature over [hash]: `abi.encode(signers, signatures)`. */
    val signature: ByteArray,
) {
    fun hash(chainId: Long): ByteArray = hashOf(chainId, account, session, expiresAt)

    companion object {
        /** Versioned like every signed structure on gossip (invariant A2). */
        val DOMAIN: ByteArray = Hash.sha3("KlippelGo session v1".toByteArray())

        fun hashOf(chainId: Long, account: String, session: String, expiresAt: Long): ByteArray =
            KlippelGoClient.abiHash(Bytes32(DOMAIN), Uint256(chainId), Address(account), Address(session), Uint256(expiresAt))
    }
}

/**
 * A session key: secp256k1, kept in app-private storage (or memory), rotated before its certificate expires.
 * It signs gossip messages as an Ethereum signed message, so a peer recovers its address from the signature.
 */
class SessionKey(val keyPair: ECKeyPair, val certificate: SessionCertificate) {
    val address: String = "0x" + Keys.getAddress(keyPair)

    /** Signs a 32-byte hash as an Ethereum signed message. Returns r, s, v (65 bytes). */
    fun sign(hash: ByteArray): ByteArray {
        val sig = Sign.signPrefixedMessage(hash, keyPair)
        return sig.r + sig.s + sig.v
    }

    fun expiresSoon(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = certificate.expiresAt - nowSeconds < RENEW_BEFORE

    companion object {
        /** A certificate lasts a day, and the phone makes a new one when less than an hour is left. */
        const val LIFETIME_SECONDS = 24 * 3600L
        const val RENEW_BEFORE = 3600L

        /** A new session key for `account`, certified by its device key. */
        fun create(account: SmartAccount, chainId: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): SessionKey {
            val keyPair = Keys.createEcKeyPair()
            val session = "0x" + Keys.getAddress(keyPair)
            val expiresAt = nowSeconds + LIFETIME_SECONDS
            val hash = SessionCertificate.hashOf(chainId, account.address, session, expiresAt)
            return SessionKey(keyPair, SessionCertificate(account.address.lowercase(), session, expiresAt, account.signature(hash)))
        }

        /** A session key saved earlier: its private key and certificate. */
        fun restore(privateKey: String, certificate: SessionCertificate) =
            SessionKey(ECKeyPair.create(Numeric.toBigInt(privateKey)), certificate)
    }
}
