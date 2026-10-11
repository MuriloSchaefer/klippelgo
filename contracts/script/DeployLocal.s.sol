// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Script, console2} from "forge-std/Script.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {ProtocolDeployer} from "./ProtocolDeployer.sol";
import {LocalAccounts} from "./LocalAccounts.sol";
import {PBACGovernor} from "../src/PBACGovernor.sol";
import {MockBRL} from "../src/mocks/MockBRL.sol";

/// @notice Deploys KlippelGo to the local anvil testnet with mock BRLA (18 decimals) and BRZ (4 decimals), seeds
/// a rider, a driver and three validators, and writes the addresses to deployments/local.json.
///
///   forge script script/DeployLocal.s.sol --rpc-url http://127.0.0.1:8545 --broadcast
contract DeployLocal is Script, ProtocolDeployer, LocalAccounts {
    function run() external {
        _loadAccounts();

        vm.startBroadcast(deployerKey);
        MockBRL brla = new MockBRL("BRLA (local mock)", "BRLA", 18);
        MockBRL brz = new MockBRL("BRZ (local mock)", "BRZ", 4);

        TokenInit[] memory tokens = new TokenInit[](2);
        tokens[0] = TokenInit(IERC20(address(brla)), 18);
        tokens[1] = TokenInit(IERC20(address(brz)), 4);
        address[] memory attesters = new address[](1);
        attesters[0] = attester;

        Protocol memory p = _deployProtocol(
            Config({
                stakeToken: IERC20(address(brla)),
                stakeDecimals: 18,
                tokens: tokens,
                feeBeneficiary: institutoKlippel,
                attesters: attesters,
                unbondingPeriod: 1 days,
                completionTimeout: 30 minutes,
                commitPeriod: 10 minutes,
                revealPeriod: 10 minutes,
                params: PBACGovernor.Params({
                    votingPeriod: 2 minutes,
                    timelockDelay: 1 minutes,
                    quorum: 3,
                    rideWeight: 1,
                    stakeUnit: 100e18
                }),
                driverMinStakeBrl: 100,
                validatorMinStakeBrl: 500,
                driverMinScore: 450,
                proposerMinAccountAge: 0,
                mapRebuildQuorum: 3,
                mapGracePeriod: 30 days
            })
        );

        // PIX on-ramp stand-in: give every test account R$ 1.000 in each coin.
        address[6] memory funded = [rider, driver, validators[0], validators[1], validators[2], attester];
        for (uint256 i; i < funded.length; ++i) {
            brla.mint(funded[i], 1_000e18);
            brz.mint(funded[i], 1_000e4);
        }
        vm.stopBroadcast();

        // Rider registers.
        vm.startBroadcast(riderKey);
        p.registry.register(p.registry.ROLE_RIDER());
        vm.stopBroadcast();

        // Driver registers, stakes R$ 100 and receives a CNH credential hash from the attester.
        vm.startBroadcast(driverKey);
        p.registry.register(p.registry.ROLE_DRIVER());
        brla.approve(address(p.registry), 100e18);
        p.registry.addStake(100e18);
        vm.stopBroadcast();

        vm.startBroadcast(attesterKey);
        p.registry.attest(driver, CREDENTIAL_CNH, keccak256("local test CNH credential"), 0);
        vm.stopBroadcast();

        // Validators register and stake R$ 500 each so they can sit on disputes.
        for (uint256 i; i < validators.length; ++i) {
            vm.startBroadcast(validatorKeys[i]);
            p.registry.register(p.registry.ROLE_VALIDATOR());
            brla.approve(address(p.registry), 500e18);
            p.registry.addStake(500e18);
            vm.stopBroadcast();
        }

        string memory k = "deployment";
        vm.serializeUint(k, "chainId", block.chainid);
        vm.serializeAddress(k, "BRLA", address(brla));
        vm.serializeAddress(k, "BRZ", address(brz));
        vm.serializeAddress(k, "PBACGovernor", address(p.governor));
        vm.serializeAddress(k, "IdentityRegistry", address(p.registry));
        vm.serializeAddress(k, "Reputation", address(p.reputation));
        vm.serializeAddress(k, "FeeVault", address(p.feeVault));
        vm.serializeAddress(k, "RideEscrow", address(p.escrow));
        vm.serializeAddress(k, "DisputeCourt", address(p.court));
        vm.serializeAddress(k, "MapRegistry", address(p.maps));
        vm.serializeAddress(k, "FareGuide", address(p.fares));
        vm.serializeAddress(k, "institutoKlippel", institutoKlippel);
        vm.serializeAddress(k, "attester", attester);
        vm.serializeAddress(k, "rider", rider);
        string memory json = vm.serializeAddress(k, "driver", driver);
        vm.writeJson(json, "../deployments/local.json");

        console2.log("PBACGovernor     ", address(p.governor));
        console2.log("IdentityRegistry ", address(p.registry));
        console2.log("Reputation       ", address(p.reputation));
        console2.log("FeeVault         ", address(p.feeVault));
        console2.log("RideEscrow       ", address(p.escrow));
        console2.log("DisputeCourt     ", address(p.court));
        console2.log("MapRegistry      ", address(p.maps));
        console2.log("FareGuide        ", address(p.fares));
        console2.log("BRLA (mock)      ", address(brla));
        console2.log("BRZ (mock)       ", address(brz));
        console2.log("Written to deployments/local.json");
    }
}
