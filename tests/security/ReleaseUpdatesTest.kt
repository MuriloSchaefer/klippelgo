package org.klippelgo.tests.security

import org.klippelgo.chain.Deployment
import org.klippelgo.tests.Scenario
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An update comes from any mirror, so nothing a mirror serves is trusted: the app installs only a file whose
 * SHA-256 the release registry names for that release (`KlippelGoClient.publishedApk`, which `Updater` asks
 * before it hands the file to Android's installer). A chain-level test: it checks the same function the app
 * calls, against the real registry, without an emulator or a mirror.
 */
class ReleaseUpdatesTest : Scenario() {
    @Test
    fun `an APK the registry does not name is refused, and the one it names is accepted`() {
        val gov = governance()
        val dir = kotlin.io.path.createTempDirectory("klippelgo-apk").toFile()
        try {
            val published = File(dir, "published.apk").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
            val version = Deployment.packVersion(gov.publishNextRelease(published))

            // A mirror serving the same file with one byte changed: refused.
            val altered = File(dir, "altered.apk").apply { writeBytes(published.readBytes().also { it[100] = (it[100] + 1).toByte() }) }
            assertFalse(chain.publishedApk(version, altered))
            // The right file, named for another release: refused too.
            assertFalse(chain.publishedApk(version - 1, published))
            // And the file the registry names: accepted.
            assertTrue(chain.publishedApk(version, published))
        } finally {
            dir.deleteRecursively()
        }
    }
}
