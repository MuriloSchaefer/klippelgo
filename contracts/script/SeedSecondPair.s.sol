// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Script, console2} from "forge-std/Script.sol";
import {LocalAccounts} from "./LocalAccounts.sol";
import {IdentityRegistry} from "../src/IdentityRegistry.sol";
import {MockBRL} from "../src/mocks/MockBRL.sol";

/// @notice Seeds a second rider (account #8) and a second driver (#9) on the local testnet, set up like the
/// first pair: R$ 1.000 in each mock coin, the driver staked with R$ 100 and holding a CNH credential. It reads
/// deployments/local.json and skips whatever is already done, so it is safe to run on a chain in use.
///
///   forge script script/SeedSecondPair.s.sol --rpc-url http://127.0.0.1:8545 --broadcast
contract SeedSecondPair is Script, LocalAccounts {
    bytes32 constant CREDENTIAL_CNH = keccak256("klippelgo.credential.CNH");

    function run() external {
        _loadAccounts();
        string memory json = vm.readFile("../deployments/local.json");
        IdentityRegistry registry = IdentityRegistry(vm.parseJsonAddress(json, ".IdentityRegistry"));
        MockBRL brla = MockBRL(vm.parseJsonAddress(json, ".BRLA"));
        MockBRL brz = MockBRL(vm.parseJsonAddress(json, ".BRZ"));

        // PIX on-ramp stand-in, as for the first pair. The mock coins mint to anyone.
        vm.startBroadcast(deployerKey);
        if (brz.balanceOf(rider2) == 0) {
            brla.mint(rider2, 1_000e18);
            brz.mint(rider2, 1_000e4);
        }
        if (brz.balanceOf(driver2) == 0) {
            brla.mint(driver2, 1_000e18);
            brz.mint(driver2, 1_000e4);
        }
        vm.stopBroadcast();

        if (registry.rolesOf(rider2) & registry.ROLE_RIDER() == 0) {
            vm.startBroadcast(rider2Key);
            registry.register(registry.ROLE_RIDER());
            vm.stopBroadcast();
        }
        if (registry.rolesOf(driver2) & registry.ROLE_DRIVER() == 0) {
            vm.startBroadcast(driver2Key);
            registry.register(registry.ROLE_DRIVER());
            brla.approve(address(registry), 100e18);
            registry.addStake(100e18);
            vm.stopBroadcast();
        }
        if (!registry.hasCredential(driver2, CREDENTIAL_CNH)) {
            vm.startBroadcast(attesterKey);
            registry.attest(driver2, CREDENTIAL_CNH, keccak256("local test CNH credential 2"), 0);
            vm.stopBroadcast();
        }
        console2.log("Second rider ", rider2);
        console2.log("Second driver", driver2);
    }
}
