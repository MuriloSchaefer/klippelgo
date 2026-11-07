package org.klippelgo.chain

import com.fasterxml.jackson.databind.ObjectMapper
import org.web3j.abi.datatypes.Address
import org.web3j.utils.Numeric

/**
 * Contract addresses on one network, as written to `deployments/<network>.json` by the deploy script, and the
 * release this build is.
 *
 * The contracts are of three kinds (docs/releases-and-data.md): governance ([governor], [releases]), data that
 * outlives every release ([identities], [stakes], [reputation], [maps], [fares]), and this release's code
 * contracts ([registry], [escrow], [court], [feeVault], [accountFactory], [paymaster]).
 */
data class Deployment(
    val chainId: Long,
    val governor: String,
    /** Where people register: this release's code over [identities]. */
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
    /** Roles, credentials and blocks: a data contract, the same across releases. */
    val identities: String = "",
    /** People's stake: a vault whose code never changes. */
    val stakes: String = "",
    /** Which releases may run, at an address every build has. */
    val releases: String = "",
    /** This release, MAJOR.MINOR.PATCH: the version the deployment was published as and the app was built at. */
    val version: String = "",
    /**
     * Escrows of earlier releases this account may still have a ride or a payment on. A ride settles on the
     * escrow it opened on, so the app keeps reading these until nothing of this account's is left there.
     */
    val previousEscrows: List<PreviousEscrow> = emptyList(),
    /**
     * The block the data contracts were deployed in (`dataDeployBlock`), which an app on a later release still
     * reads registrations from. The same as [startBlock] until a release replaces the code contracts.
     */
    val dataStartBlock: Long = startBlock,
) {
    /** [version] packed the way `ReleaseRegistry` keys releases: `major << 32 | minor << 16 | patch`. Zero when unknown. */
    val versionKey: Long get() = packVersion(version)

    /**
     * The hash `ReleaseRegistry` keeps of this release's code contracts (`contractsHash`): the app compares it
     * with the published one to know the release it runs is the one published under its version.
     */
    val releaseContracts: ByteArray
        get() = KlippelGoClient.abiHash(*listOf(escrow, court, feeVault, registry, accountFactory, paymaster).map { Address(it) }.toTypedArray())

    /**
     * The release as gossip topics carry it: the first eight bytes of [releaseContracts], in hex. Phones built
     * against other code contracts are on other topics, so they never match each other through an escrow one of
     * them does not use. An app-only release keeps the same contracts and so the same topics.
     */
    val gossipRelease: String get() = Numeric.toHexStringNoPrefix(releaseContracts).take(16)


    companion object {
        fun fromJson(json: String): Deployment {
            val n = ObjectMapper().readTree(json)
            fun a(key: String) = n.get(key)?.asText() ?: error("deployment is missing $key")
            return Deployment(
                chainId = n.get("chainId")?.asLong() ?: 31337,
                governor = a("PBACGovernor"),
                registry = a("IdentityRegistry"),
                identities = a("IdentityStore"),
                stakes = a("StakeVault"),
                releases = a("ReleaseRegistry"),
                version = n.get("version")?.asText().orEmpty(),
                previousEscrows = n.get("previousEscrows")?.map {
                    PreviousEscrow(it.get("RideEscrow").asText(), it.get("deployBlock")?.asLong() ?: 0)
                }.orEmpty(),
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
                dataStartBlock = (n.get("dataDeployBlock") ?: n.get("deployBlock"))?.asLong() ?: 0,
            )
        }

        /** MAJOR.MINOR.PATCH packed as `ReleaseRegistry.versionOf` does, or zero for anything else. */
        fun packVersion(version: String): Long {
            val parts = version.trim().split('.').map { it.toLongOrNull() ?: return 0 }
            if (parts.size != 3 || parts.any { it !in 0..65_535 }) return 0
            return (parts[0] shl 32) or (parts[1] shl 16) or parts[2]
        }

        /** The other way: what [packVersion] made, as text. */
        fun versionText(key: Long): String = "${key shr 32}.${(key shr 16) and 0xffff}.${key and 0xffff}"

        /**
         * Addresses that `testnet/deploy.sh` produces on a fresh local testnet. They are deterministic because the
         * deployer account and its transaction order never change; redeploying on a used chain moves them.
         */
        val LOCAL_DEFAULT = Deployment(
            chainId = 31337,
            governor = "0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0",
            registry = "0x0165878A594ca255338adfa4d48449f69242Eb8F",
            reputation = "0xa513E6E4b8f2a923D98304ec87F64353C4D5C853",
            feeVault = "0x2279B7A0a67DB372996a5FaB50D91eAA73d2eBe6",
            escrow = "0x8A791620dd6260079BF849Dc5567aDC3F2FdC318",
            court = "0x610178dA211FEF7D417bC0e6FeD39F05609AD788",
            maps = "0xB7f8BC63BbcaD18155201308C8f3540b07f84F5e",
            fares = "0xA51c1fc2f0D1a1b8494Ed1FE312d7C3a78Ed91C0",
            brla = "0x5FbDB2315678afecb367f032d93F642f64180aa3",
            brz = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
            taxCollector = "0xBcd4042DE499D14e55001CcbB24a551F3b954096",
            // ERC-4337's EntryPoint v0.9, at its canonical address (testnet/entrypoint.sh puts it there locally).
            entryPoint = "0x433709009B8330FDa32311DF1C2AFA402eD8D009",
            accountFactory = "0xc3e53F4d16Ae77Db1c982e75a937B9f60FE63690",
            accountImplementation = "0xE6E340D132b5f46d1e472DebcD681B2aBc16e57E",
            deviceKeyVerifier = "0x09635F643e140090A9A8Dcd712eD6285858ceBef",
            paymaster = "0x67d269191c92Caf3cD7723F116c85e6E9bf55933",
            identities = "0xDc64a140Aa3E981100a9becA4E685f962f0cF6C9",
            stakes = "0x5FC8d32690cc91D4c39d9d3abcBD16989F875707",
            releases = "0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9",
            version = "0.1.0",
        )
    }
}

/** An earlier release's escrow, and the block it was deployed in: where reading its rides starts. */
data class PreviousEscrow(val address: String, val startBlock: Long)

/**
 * Seeded accounts on the local testnet: smart accounts whose device keys are P-256 secrets everyone knows, so that
 * test peers and emulators launched for a role have accounts that are funded, registered in both roles, staked
 * and licensed (`contracts/script/SeedPhones.s.sol`, which signs their operations with the same secrets). A real
 * phone makes its own key in its chip instead. Never send real money to these accounts.
 *
 * The end-to-end tests have four of their own, so that phones played by hand on the same chain and gossip network
 * are other people to them: a request from a hand-played rider never looks like one from a test's rider, and the
 * tests never end a ride someone is playing.
 */
enum class DevAccount(val deviceKey: String) {
    RIDER("0x00000000000000000000000000000000000000000000000000000000000ab001"),
    DRIVER("0x00000000000000000000000000000000000000000000000000000000000ab002"),

    /** The second pair of phones. */
    RIDER_2("0x00000000000000000000000000000000000000000000000000000000000ab003"),
    DRIVER_2("0x00000000000000000000000000000000000000000000000000000000000ab004"),

    /** The end-to-end tests' accounts (tests/), in the same order. */
    E2E_RIDER("0x00000000000000000000000000000000000000000000000000000000000ab011"),
    E2E_DRIVER("0x00000000000000000000000000000000000000000000000000000000000ab012"),
    E2E_RIDER_2("0x00000000000000000000000000000000000000000000000000000000000ab013"),
    E2E_DRIVER_2("0x00000000000000000000000000000000000000000000000000000000000ab014"),
    ;

    fun key(): DeviceKey = SoftwareDeviceKey.fromHex(deviceKey)

    companion object {
        /** The account of a role (`driver` or not), of pair 1 or 2, from the end-to-end tests' set or the other. */
        fun of(driver: Boolean, second: Boolean, e2e: Boolean): DevAccount = when {
            e2e -> if (driver) (if (second) E2E_DRIVER_2 else E2E_DRIVER) else (if (second) E2E_RIDER_2 else E2E_RIDER)
            else -> if (driver) (if (second) DRIVER_2 else DRIVER) else (if (second) RIDER_2 else RIDER)
        }

        /** The end-to-end tests' accounts. */
        val e2e: List<DevAccount> get() = listOf(E2E_RIDER, E2E_DRIVER, E2E_RIDER_2, E2E_DRIVER_2)
    }
}
