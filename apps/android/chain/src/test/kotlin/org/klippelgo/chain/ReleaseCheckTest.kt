package org.klippelgo.chain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the app does with what the release registry says (A16 in apps/docs/security.md): only a revoked release
 * refuses new rides, an update is offered only to a higher active release, and nothing the app cannot read or
 * tie to its own code contracts refuses anything. The chain part runs when KLIPPELGO_RPC is set.
 */
class ReleaseCheckTest {
    private val v1 = Deployment.packVersion("1.0.0")
    private val v2 = Deployment.packVersion("2.0.0")

    @Test
    fun onlyARevokedReleaseRefusesNewRides() {
        for (status in ReleaseStatus.entries) {
            assertEquals(status != ReleaseStatus.Revoked, ReleaseCheck(status, v1, v1, status).mayStartRides, "$status")
        }
    }

    @Test
    fun anUpdateIsOfferedOnlyToAHigherActiveRelease() {
        assertTrue(ReleaseCheck(ReleaseStatus.Active, v1, v2, ReleaseStatus.Active).updateAvailable)
        assertTrue(ReleaseCheck(ReleaseStatus.Revoked, v1, v2, ReleaseStatus.Active).updateAvailable)
        // Not to the same release, nor to a revoked or deprecated one, nor back to an older one.
        assertFalse(ReleaseCheck(ReleaseStatus.Active, v1, v1, ReleaseStatus.Active).updateAvailable)
        assertFalse(ReleaseCheck(ReleaseStatus.Active, v1, v2, ReleaseStatus.Revoked).updateAvailable)
        assertFalse(ReleaseCheck(ReleaseStatus.Active, v1, v2, ReleaseStatus.Deprecated).updateAvailable)
        assertFalse(ReleaseCheck(ReleaseStatus.Active, v2, v1, ReleaseStatus.Active).updateAvailable)
        // A build that knows no version of its own is offered nothing.
        assertFalse(ReleaseCheck(ReleaseStatus.Unknown, 0, v2, ReleaseStatus.Active).updateAvailable)
    }

    @Test
    fun aRegistryThatCannotBeReadRefusesNothing() {
        // Nothing listens on port 1: every read fails.
        val client = KlippelGoClient("http://127.0.0.1:1", DevAccount.RIDER.key(), Deployment.LOCAL_DEFAULT, "http://127.0.0.1:1")
        val check = client.releaseCheck()
        assertEquals(ReleaseStatus.Unknown, check.status)
        assertTrue(check.mayStartRides)
        assertFalse(check.updateAvailable)
    }

    @Test
    fun aReleasePublishedWithOtherCodeContractsIsNotThisBuilds() {
        val rpc = System.getenv("KLIPPELGO_RPC").orEmpty()
        if (rpc.isBlank()) return
        val deployment = System.getenv("KLIPPELGO_DEPLOYMENT").orEmpty()
            .takeIf { it.isNotBlank() }?.let { Deployment.fromJson(File(it).readText()) } ?: Deployment.LOCAL_DEFAULT
        // The build as deployed is the published release: known, so the comparison below is what makes it unknown.
        assertTrue(KlippelGoClient(rpc, DevAccount.RIDER.key(), deployment).releaseCheck().status != ReleaseStatus.Unknown)
        // The same version, built against another escrow: the published release is not this build.
        val other = deployment.copy(escrow = "0x000000000000000000000000000000000000dEaD")
        val check = KlippelGoClient(rpc, DevAccount.RIDER.key(), other).releaseCheck()
        assertEquals(ReleaseStatus.Unknown, check.status)
        assertTrue(check.mayStartRides)
    }
}
