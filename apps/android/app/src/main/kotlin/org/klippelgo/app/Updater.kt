package org.klippelgo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.klippelgo.chain.Deployment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where an update to the latest release stands. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data class Downloading(val done: Long, val total: Long) : UpdateState

    /** Downloaded and named by the release registry: Android's installer can be asked to install it. */
    data class Ready(val version: Long) : UpdateState

    /** Nothing the mirror served is an APK the registry names for the release, or the download failed. */
    data class Failed(val message: String) : UpdateState
}

/** What the mirror served is not an APK the release registry names: it was deleted, never installed. */
class ApkRefused : Exception("the downloaded update is not one the release registry names")

/** The mirror has no APK of the release for this phone. */
class NoApk : Exception("the mirror has no APK of this release")

/**
 * Fetches the latest release's APK from a mirror and hands it to Android's installer, the way [MapStore] fetches
 * map packs: nothing the mirror sends is trusted. The file must hash to one of the APKs the release registry
 * names for that release (`ReleaseRegistry.isApk`), or it is deleted and never installed. Android then asks the
 * person to confirm the install, and accepts it only if it is signed with the same key as the app on the phone.
 */
class Updater(private val context: Context) {
    private val dir = File(context.cacheDir, "updates")

    /**
     * Downloads the APK of release `version` for this phone's architecture, or the universal one, and checks it
     * with `published`. Gives the checked file.
     */
    fun download(mirror: String, version: Long, published: (File) -> Boolean, progress: (done: Long, total: Long) -> Unit): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        var refused = false
        for (variant in variants()) {
            val name = fileName(version, variant)
            val part = File(dir, "$name.part")
            val fetched = runCatching { fetch("${mirror.trimEnd('/')}/$name", part, progress) }.getOrDefault(false)
            if (!fetched) continue
            if (!published(part)) {
                // Not what the registry names: maybe altered, maybe another build. It is never kept.
                part.delete()
                refused = true
                continue
            }
            return File(dir, name).also { check(part.renameTo(it)) { "could not keep the update" } }
        }
        throw if (refused) ApkRefused() else NoApk()
    }

    /** Whether Android lets this app ask to install other apps, which the person allows once in its settings. */
    fun mayInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The settings screen where the person allows this app to install updates. */
    fun allowInstallsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Asks Android's installer to install the checked update. The person confirms it there. */
    fun installIntent(version: Long): Intent? {
        val file = dir.listFiles()?.firstOrNull { it.name.startsWith(prefix(version)) && it.name.endsWith(".apk") } ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun fetch(url: String, into: File, progress: (Long, Long) -> Unit): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) return false
            val total = connection.contentLengthLong
            if (total > MAX_BYTES) return false
            var done = 0L
            connection.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        // A mirror that sends more than any APK of this app is refused before the phone fills up.
                        if (done > MAX_BYTES) return false
                        output.write(buffer, 0, n)
                        progress(done, total)
                    }
                }
            }
            return true
        } finally {
            connection.disconnect()
        }
    }

    /** This phone's architectures the release is built for, best first, then the universal APK. */
    private fun variants(): List<String> = Build.SUPPORTED_ABIS.filter { it in ABIS } + UNIVERSAL

    companion object {
        /** The architectures a release has an APK of its own for (`splits` in app/build.gradle.kts). */
        private val ABIS = setOf("arm64-v8a", "x86_64")
        private const val UNIVERSAL = "universal"
        private const val APK_TYPE = "application/vnd.android.package-archive"

        /** Far more than the app's APK, so that a mirror cannot fill the phone. */
        private const val MAX_BYTES = 400L * 1024 * 1024

        private fun prefix(version: Long) = "klippelgo-${Deployment.versionText(version)}-"

        /**
         * The one place the name of a release's APK on a mirror is made: `klippelgo-<version>-<abi>.apk`, with
         * `universal` for the APK that runs everywhere. The release workflow publishes them under these names.
         */
        fun fileName(version: Long, variant: String) = "${prefix(version)}$variant.apk"
    }
}
