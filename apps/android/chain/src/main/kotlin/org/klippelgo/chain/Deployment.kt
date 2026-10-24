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
    val fares: String,
    val brla: String,
    val brz: String,
    /** The account the government's tax on each ride is paid to, when the deployment file names it. */
    val taxCollector: String = "",
    /** ERC-4337: the network's EntryPoint, and what people's accounts are made of and paid through. */
    val entryPoint: String = "",
    val accountFactory: String = "",
    val accountImplementation: String = "",
    val deviceKeyVerifier: String = "",
    val paymaster: String = "",
    /** The block the contracts were deployed in, or an earlier one (`deployBlock`): event scans start there. */
    val startBlock: Long = 0,
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
                fares = a("FareGuide"),
                brla = a("BRLA"),
                brz = a("BRZ"),
                taxCollector = n.get("taxCollector")?.asText().orEmpty(),
                entryPoint = a("EntryPoint"),
                accountFactory = a("AccountFactory"),
                accountImplementation = a("KlippelGoAccount"),
                deviceKeyVerifier = a("DeviceKeyVerifier"),
                paymaster = a("BrlPaymaster"),
                startBlock = n.get("deployBlock")?.asLong() ?: 0,
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
            fares = "0x8A791620dd6260079BF849Dc5567aDC3F2FdC318",
            brla = "0x5FbDB2315678afecb367f032d93F642f64180aa3",
            brz = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
            taxCollector = "0xBcd4042DE499D14e55001CcbB24a551F3b954096",
            // ERC-4337's EntryPoint v0.9, at its canonical address (testnet/entrypoint.sh puts it there locally).
            entryPoint = "0x433709009B8330FDa32311DF1C2AFA402eD8D009",
            accountFactory = "0x09635F643e140090A9A8Dcd712eD6285858ceBef",
            accountImplementation = "0x7a2088a1bFc9d81c55368AE168C2C02570cB814F",
            deviceKeyVerifier = "0x322813Fd9A801c5507c9de605d63CEA4f2CE6c44",
            paymaster = "0x4A679253410272dd5232B3Ff7cF5dbB88f295319",
        )
    }
}

/**
 * Seeded accounts on the local testnet: smart accounts whose device keys are P-256 secrets everyone knows, so that
 * test peers and emulators launched for a role have accounts that are funded, registered in both roles, staked
 * and licensed (`contracts/script/SeedPhones.s.sol`, which signs their operations with the same secrets). A real
 * phone makes its own key in its chip instead. Never send real money to these accounts.
 */
enum class DevAccount(val deviceKey: String) {
    RIDER("0x00000000000000000000000000000000000000000000000000000000000ab001"),
    DRIVER("0x00000000000000000000000000000000000000000000000000000000000ab002"),

    /** The second pair of phones. */
    RIDER_2("0x00000000000000000000000000000000000000000000000000000000000ab003"),
    DRIVER_2("0x00000000000000000000000000000000000000000000000000000000000ab004"),
    ;

    fun key(): DeviceKey = SoftwareDeviceKey.fromHex(deviceKey)
}
