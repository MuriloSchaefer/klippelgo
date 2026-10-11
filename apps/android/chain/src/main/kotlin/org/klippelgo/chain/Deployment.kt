package org.klippelgo.chain

import com.fasterxml.jackson.databind.ObjectMapper

/** Contract addresses on one network, as written to `deployments/<network>.json` by the deploy script. */
data class Deployment(
    val chainId: Long,
    val governor: String,
    val registry: String,
    val reputation: String,
    val feeVault: String,
    val escrow: String,
    val court: String,
    val maps: String,
    val brla: String,
    val brz: String,
) {
    companion object {
        fun fromJson(json: String): Deployment {
            val n = ObjectMapper().readTree(json)
            fun a(key: String) = n.get(key)?.asText() ?: error("deployment is missing $key")
            return Deployment(
                chainId = n.get("chainId")?.asLong() ?: 31337,
                governor = a("PBACGovernor"),
                registry = a("IdentityRegistry"),
                reputation = a("Reputation"),
                feeVault = a("FeeVault"),
                escrow = a("RideEscrow"),
                court = a("DisputeCourt"),
                maps = a("MapRegistry"),
                brla = a("BRLA"),
                brz = a("BRZ"),
            )
        }

        /**
         * Addresses that `testnet/deploy.sh` produces on a fresh local testnet. They are deterministic because the
         * deployer account and its transaction order never change; redeploying on a used chain moves them.
         */
        val LOCAL_DEFAULT = Deployment(
            chainId = 31337,
            governor = "0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0",
            registry = "0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9",
            reputation = "0xDc64a140Aa3E981100a9becA4E685f962f0cF6C9",
            feeVault = "0x5FC8d32690cc91D4c39d9d3abcBD16989F875707",
            escrow = "0x0165878A594ca255338adfa4d48449f69242Eb8F",
            court = "0xa513E6E4b8f2a923D98304ec87F64353C4D5C853",
            maps = "0x2279B7A0a67DB372996a5FaB50D91eAA73d2eBe6",
            brla = "0x5FbDB2315678afecb367f032d93F642f64180aa3",
            brz = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
        )
    }
}

/**
 * Seeded accounts on the local testnet, from anvil's public test mnemonic. These keys are known to everyone: they
 * exist only so two emulators can play rider and driver. Real phones will sign with a Secure Enclave or StrongBox
 * P-256 key through an ERC-4337 smart account.
 */
enum class DevAccount(val privateKey: String) {
    RIDER("0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6"),
    DRIVER("0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a"),

    /** A second rider and driver (accounts #8 and #9), seeded by `contracts/script/SeedSecondPair.s.sol`. */
    RIDER_2("0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97"),
    DRIVER_2("0x2a871d0798f97d79848a013d4936a73bf4cc922c825d33c1cf7073dff6d409c6"),
}
