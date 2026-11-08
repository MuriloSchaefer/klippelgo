package org.klippelgo.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the app keeps of past rides, sealed: each file under `filesDir/rides/` is AES-256-GCM under a key made in
 * the phone's chip (the Android Keystore), which cannot be copied out (closes P8). A copy of the app's files, from a
 * backup tool, a forensic dump or a phone taken apart, holds nothing readable without that chip. Each file's name is
 * bound to it as associated data, so a sealed route cannot be passed off as another ride's.
 *
 * The key asks for no screen unlock: the driver's phone takes routes in and reads them back with the screen off,
 * on a ride. Whoever can run code as the app on the phone itself can still read them; that is limit P20, the same
 * as for the account's key. What they find is bounded by [RETENTION_DAYS].
 */
class SealedFiles(private val dir: File) {
    init {
        dir.mkdirs()
    }

    /** Seals `bytes` into `name`, replacing what was there. Written aside first, so a crash leaves the old file. */
    fun write(name: String, bytes: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val sealed = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(bytes)
        val part = File(dir, "$name.part")
        part.writeBytes(sealed)
        check(part.renameTo(File(dir, name))) { "could not keep $name" }
    }

    fun writeText(name: String, text: String) = write(name, text.toByteArray())

    /**
     * What `name` holds, or null when there is no such file or it does not open (altered, or sealed elsewhere). A
     * file that opens counts as used now: retention runs from its last use, so a ride still under way or in a dispute
     * keeps its files as long as the app starts and reads them within the 30 days. A clock that jumps more than that
     * ahead loses them at the next start.
     */
    fun read(name: String): ByteArray? = open(name)?.also { File(dir, name).setLastModified(System.currentTimeMillis()) }

    private fun open(name: String): ByteArray? {
        val file = File(dir, name).takeIf { it.isFile } ?: return null
        return runCatching {
            val sealed = file.readBytes()
            require(sealed.size > 1 + IV_BYTES && sealed[0] == VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
            cipher.updateAAD(name.toByteArray())
            cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        }.getOrNull()
    }

    fun readText(name: String): String? = read(name)?.let { String(it) }

    fun exists(name: String): Boolean = File(dir, name).isFile

    /** Deletes what was last written or read more than [RETENTION_DAYS] ago, and anything left half written. */
    fun forgetOld(nowMs: Long = System.currentTimeMillis()) {
        val before = nowMs - RETENTION_DAYS * 86_400_000L
        dir.listFiles()?.forEach { if (it.name.endsWith(".part") || it.lastModified() < before) it.delete() }
    }

    /**
     * Seals every file in the folder that is not sealed yet, as an earlier version of the app wrote them in the
     * clear, keeping its time so retention counts from when it was first written. Run once, by [AppData].
     */
    fun sealPlain() {
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && open(it.name) == null }?.forEach { plain ->
            val written = plain.lastModified()
            write(plain.name, plain.readBytes())
            File(dir, plain.name).setLastModified(written)
        }
    }

    companion object {
        /**
         * How long a ride's route, quote and the rider's position key are kept after they were last used. Far longer
         * than any dispute over the ride can take, so the evidence is there when it is needed, and no longer.
         */
        const val RETENTION_DAYS = 30

        /** Versioned: another scheme gets another alias and another first byte. */
        private const val ALIAS = "klippelgo.files.v1"
        private const val VERSION: Byte = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** The rides folder of this app. */
        fun rides(filesDir: File) = SealedFiles(File(filesDir, "rides"))

        @Synchronized
        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
