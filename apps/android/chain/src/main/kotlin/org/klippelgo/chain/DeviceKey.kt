package org.klippelgo.chain

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The key that owns a person's account: P-256, as the phone's chip makes it (Android StrongBox or Keystore, the
 * iPhone's Secure Enclave). Those chips sign only through SHA-256, so [sign] signs SHA-256 of the message, and
 * the account's verifier (`DeviceKeyVerifier`) checks exactly that.
 */
interface DeviceKey {
    /** The public key: x then y, 32 bytes each. */
    val publicKey: ByteArray

    /** Signs SHA-256 of `message`. Returns `r || s`, 64 bytes, with `s` in the lower half of the curve's order. */
    fun sign(message: ByteArray): ByteArray
}

/**
 * A device key held in memory, from a known secret: what peers, tests and the local testnet's seeded accounts use.
 * A phone uses the chip's key instead (`KeystoreDeviceKey` in the app). Never for real money.
 */
class SoftwareDeviceKey(secret: BigInteger) : DeviceKey {
    private val d = secret.mod(P256.N).also { require(it.signum() > 0) { "a P-256 secret cannot be zero" } }

    override val publicKey: ByteArray = P256.DOMAIN.g.multiply(d).normalize().let {
        Numeric.toBytesPadded(it.affineXCoord.toBigInteger(), 32) + Numeric.toBytesPadded(it.affineYCoord.toBigInteger(), 32)
    }

    override fun sign(message: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        // Deterministic nonces (RFC 6979): the same message signs the same way, and no random source is trusted.
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, P256.DOMAIN))
        val (r, s) = signer.generateSignature(digest)
        return P256.raw(r, s)
    }

    companion object {
        fun fromHex(secret: String) = SoftwareDeviceKey(Numeric.toBigInt(secret))
    }
}

/** P-256 arithmetic the device keys share. */
object P256 {
    private val curve: X9ECParameters = CustomNamedCurves.getByName("secp256r1")
    val DOMAIN = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
    val N: BigInteger = curve.n
    private val HALF_N: BigInteger = N.shiftRight(1)

    /** `r || s` with `s` in the lower half, which is the only form `P256.verify` on chain accepts. */
    fun raw(r: BigInteger, s: BigInteger): ByteArray {
        val low = if (s > HALF_N) N - s else s
        return Numeric.toBytesPadded(r, 32) + Numeric.toBytesPadded(low, 32)
    }

    /** The same from a DER signature, as Android's Keystore and Java's `Signature` give it. */
    fun rawFromDer(der: ByteArray): ByteArray {
        val seq = ASN1Sequence.getInstance(der)
        return raw((seq.getObjectAt(0) as ASN1Integer).value, (seq.getObjectAt(1) as ASN1Integer).value)
    }
}
