// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Script, console2} from "forge-std/Script.sol";
import {LocalAccounts} from "./LocalAccounts.sol";
import {IdentityRegistry} from "../src/IdentityRegistry.sol";
import {MockBRL} from "../src/mocks/MockBRL.sol";

/// @notice Sets up the four accounts the test phones use on the local testnet so that each phone can be a rider
/// or a driver: accounts #3 and #4 (the first rider and driver) and #8 and #9 (the second pair). Each gets
/// R$ 1.000 in each mock coin, both roles, R$ 100 staked and a CNH credential. A phone keeps its one account
/// when it switches between passenger and driver mode, so every account needs what both modes ask for.
///
/// It reads deployments/local.json and skips whatever is already done, so it is safe to run on a chain in use.
///
///   forge script script/SeedPhones.s.sol --rpc-url http://127.0.0.1:8545 --broadcast
contract SeedPhones is Script, LocalAccounts {
    bytes32 constant CREDENTIAL_CNH = keccak256("klippelgo.credential.CNH");
    uint256 constant STAKE = 100e18;

    function run() external {
        _loadAccounts();
        string memory json = vm.readFile("../deployments/local.json");
        IdentityRegistry registry = IdentityRegistry(vm.parseJsonAddress(json, ".IdentityRegistry"));
        MockBRL brla = MockBRL(vm.parseJsonAddress(json, ".BRLA"));
        MockBRL brz = MockBRL(vm.parseJsonAddress(json, ".BRZ"));

        uint256[4] memory keys = [riderKey, driverKey, rider2Key, driver2Key];
        uint8 both = registry.ROLE_RIDER() | registry.ROLE_DRIVER();
        for (uint256 i; i < keys.length; ++i) {
            address phone = vm.addr(keys[i]);

            // PIX on-ramp stand-in. The mock coins mint to anyone.
            if (brz.balanceOf(phone) == 0 && brla.balanceOf(phone) == 0) {
                vm.startBroadcast(deployerKey);
                brla.mint(phone, 1_000e18);
                brz.mint(phone, 1_000e4);
                vm.stopBroadcast();
            }

            uint256 stake = registry.identity(phone).stake;
            bool unregistered = registry.rolesOf(phone) & both != both;
            if (unregistered || stake < STAKE) {
                vm.startBroadcast(keys[i]);
                if (unregistered) registry.register(both);
                if (stake < STAKE) {
                    brla.approve(address(registry), STAKE - stake);
                    registry.addStake(STAKE - stake);
                }
                vm.stopBroadcast();
            }
            if (!registry.hasCredential(phone, CREDENTIAL_CNH)) {
                vm.startBroadcast(attesterKey);
                registry.attest(
                    phone, CREDENTIAL_CNH, keccak256(abi.encode("local test CNH credential", phone)), 0
                );
                vm.stopBroadcast();
            }
            console2.log("Phone account", phone);
        }
    }
}
