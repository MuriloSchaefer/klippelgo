package org.klippelgo.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.klippelgo.chain.DeviceKey
import org.klippelgo.chain.P256
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The key that owns this phone's account: P-256, made inside the phone's chip (StrongBox when the phone has one,
 * the TEE otherwise) and never able to leave it. It signs SHA-256 of what it is given, which is what the
 * account's verifier checks.
 *
 * It is not bound to the phone's screen lock (no user check per use): Android destroys a key bound to the lock
 * when the lock is turned off, and with no second signer on the account yet (recovery, limit P21) that would lose
 * the account and its money for good. Whoever holds the unlocked phone can therefore sign (limit P20). Once
 * recovery exists, the key is to be made with `setUserAuthenticationRequired`.
 */
class KeystoreDeviceKey private constructor(private val alias: String) : DeviceKey {
    private val entry: KeyStore.PrivateKeyEntry
        get() = keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry

    override val publicKey: ByteArray = (entry.certificate.publicKey as ECPublicKey).w.let {
        pad(it.affineX) + pad(it.affineY)
    }

    override fun sign(message: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey as PrivateKey)
        signature.update(message)
        return P256.rawFromDer(signature.sign())
    }

    companion object {
        /** Versioned: a new key scheme gets a new alias rather than reusing this one. */
        private const val ALIAS = "klippelgo.account.v1"

        /** This phone's key, made the first time it is asked for. */
        fun forThisPhone(context: Context): KeystoreDeviceKey {
            if (!keyStore().containsAlias(ALIAS)) create()
            return KeystoreDeviceKey(ALIAS)
        }

        fun exists(): Boolean = keyStore().containsAlias(ALIAS)

        private fun create() {
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            try {
                generator.initialize(spec(strongBox = true))
                generator.generateKeyPair()
            } catch (e: java.security.ProviderException) {
                // No StrongBox on this phone (StrongBoxUnavailableException, which Android 8 does not know by
                // name): the TEE keeps the key, which still never leaves it.
                generator.initialize(spec(strongBox = false))
                generator.generateKeyPair()
            }
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun pad(v: BigInteger): ByteArray {
            val b = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - b.size) + b
        }
    }
}
