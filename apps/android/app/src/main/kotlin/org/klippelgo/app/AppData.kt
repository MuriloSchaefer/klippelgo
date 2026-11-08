package org.klippelgo.app

import android.content.SharedPreferences
import java.io.File

/**
 * The shape of what the app keeps on the phone, and the steps that bring an older shape up to date. An update
 * replaces the app and keeps its data (docs/releases-and-data.md), so a release that changes what is kept adds a
 * step here instead of asking people to reinstall.
 *
 * What is kept: the `klippelgo` preferences (role, node address, first name, the driver's skip rules, the
 * seeded-account choice of debug builds, the account this phone joined, the gossip session key and its certificate,
 * voided quotes, saved event scans per account), and in the app's files the map packs (`packs/`) and each ride's
 * quote, route and rider key (`rides/`, sealed by [SealedFiles]). The device key and the files' key live in the
 * Android Keystore and are not touched here.
 */
object AppData {
    /** The shape this build writes. Raise it with each step added to [steps]. */
    const val SCHEMA = 2

    private const val KEY = "schema"

    /**
     * Step `n` brings data of shape `n` to shape `n + 1`. Shape 0 is data from before the schema was recorded,
     * which is shape 1's in every key, so it has no step: only the number is written.
     */
    private val steps: Map<Int, (SharedPreferences.Editor, SharedPreferences, File) -> Unit> = mapOf(
        // Shape 2 seals the rides' files, which shape 1 wrote in the clear (P8).
        1 to { _, _, files -> SealedFiles.rides(files).sealPlain() },
    )

    /** Brings the preferences and the app's files to [SCHEMA], one step at a time, before anything reads them. */
    fun migrate(prefs: SharedPreferences, filesDir: File) {
        var at = prefs.getInt(KEY, 0)
        // Data written by a newer release than this one (after a downgrade) is left as it is.
        if (at >= SCHEMA) return
        val edit = prefs.edit()
        while (at < SCHEMA) {
            // A step that fails (the Keystore not answering, say) leaves the shape it reached, and is run again at the
            // next start: an app that cannot open at all would be worse than one that waits a start.
            if (runCatching { steps[at]?.invoke(edit, prefs, filesDir) }.isFailure) break
            at++
        }
        edit.putInt(KEY, at).commit()
    }
}
