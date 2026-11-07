package org.klippelgo.chain

import org.web3j.utils.Numeric
import kotlin.test.Test
import kotlin.test.assertEquals

/** How a build names its release to the registry and to gossip. Needs no chain. */
class DeploymentTest {
    /** The addresses of one local deploy, whose release `ReleaseRegistry.contractsHash` was read from the chain. */
    private val deployed = Deployment.LOCAL_DEFAULT.copy(
        escrow = "0x8A791620dd6260079BF849Dc5567aDC3F2FdC318",
        court = "0x610178dA211FEF7D417bC0e6FeD39F05609AD788",
        feeVault = "0x2279B7A0a67DB372996a5FaB50D91eAA73d2eBe6",
        registry = "0x0165878A594ca255338adfa4d48449f69242Eb8F",
        accountFactory = "0xc3e53F4d16Ae77Db1c982e75a937B9f60FE63690",
        paymaster = "0x67d269191c92Caf3cD7723F116c85e6E9bf55933",
    )

    @Test
    fun aVersionIsPackedAsTheRegistryKeysIt() {
        assertEquals(65_536L, Deployment.packVersion("0.1.0"))
        assertEquals((1L shl 32) or (2L shl 16) or 3L, Deployment.packVersion("1.2.3"))
        assertEquals("1.2.3", Deployment.versionText(Deployment.packVersion("1.2.3")))
        // Anything else names no release.
        assertEquals(0L, Deployment.packVersion(""))
        assertEquals(0L, Deployment.packVersion("1.2"))
        assertEquals(0L, Deployment.packVersion("1.2.x"))
        assertEquals(0L, Deployment.packVersion("1.2.70000"))
    }

    @Test
    fun theReleaseContractsAreTheHashTheRegistryComputes() {
        // cast call <ReleaseRegistry> 'contractsHash(address,address,address,address,address,address)(bytes32)' …
        assertEquals("0x9206bc18e70d7a15094fed81aa4216d0ce81e6e8a9420f2f175d927a9a1521dc", Numeric.toHexString(deployed.releaseContracts))
        // Gossip carries its first eight bytes, which the core's topic filter accepts as they are.
        assertEquals("9206bc18e70d7a15", deployed.gossipRelease)
        assertEquals("/klippelgo/match/9206bc18e70d7a15/6g9fp", Matchmaker.topicFor(deployed.gossipRelease, "6g9fp"))
    }

    @Test
    fun aDeploymentFileNamesItsReleaseAndEarlierEscrows() {
        val json = """
            {"chainId":31337,"version":"1.4.0","deployBlock":900,"dataDeployBlock":2,
             "PBACGovernor":"0x1","IdentityRegistry":"0x2","IdentityStore":"0x3","StakeVault":"0x4","ReleaseRegistry":"0x5",
             "Reputation":"0x6","FeeVault":"0x7","RideEscrow":"0x8","DisputeCourt":"0x9","MapRegistry":"0xa","FareGuide":"0xb",
             "BRLA":"0xc","BRZ":"0xd","EntryPoint":"0xe","AccountFactory":"0xf","KlippelGoAccount":"0x10",
             "DeviceKeyVerifier":"0x11","BrlPaymaster":"0x12",
             "previousEscrows":[{"RideEscrow":"0x99","deployBlock":5}]}
        """.trimIndent()
        val d = Deployment.fromJson(json)
        assertEquals("1.4.0", d.version)
        assertEquals((1L shl 32) or (4L shl 16), d.versionKey)
        assertEquals(listOf(PreviousEscrow("0x99", 5)), d.previousEscrows)
        assertEquals(900L, d.startBlock)
        assertEquals(2L, d.dataStartBlock)
        assertEquals("0x3", d.identities)
        assertEquals("0x4", d.stakes)
        assertEquals("0x5", d.releases)
    }
}
