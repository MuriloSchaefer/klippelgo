// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Script, console2} from "forge-std/Script.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {LocalAccounts} from "./LocalAccounts.sol";
import {RideEscrow} from "../src/RideEscrow.sol";
import {Reputation} from "../src/Reputation.sol";
import {FeeVault} from "../src/FeeVault.sol";

/// @notice Runs one R$ 23,40 ride in BRZ on the local testnet: open, accept, start, complete, rate, sweep. The
/// quote, pickup proof and receipt hashes are stand-ins: no phones are involved here.
///
///   forge script script/DemoRide.s.sol --rpc-url http://127.0.0.1:8545 --broadcast
contract DemoRide is Script, LocalAccounts {
    function run() external {
        _loadAccounts();
        string memory json = vm.readFile("../deployments/local.json");
        RideEscrow escrow = RideEscrow(vm.parseJsonAddress(json, ".RideEscrow"));
        Reputation reputation = Reputation(vm.parseJsonAddress(json, ".Reputation"));
        FeeVault vault = FeeVault(vm.parseJsonAddress(json, ".FeeVault"));
        IERC20 brz = IERC20(vm.parseJsonAddress(json, ".BRZ"));

        uint256 fare = 23_4000; // R$ 23,40 with 4 decimals
        (, uint256 riderBond, uint256 driverBond) = escrow.tokenConfig(brz);
        bytes32 quoteHash = keccak256("quote signed by driver in the P2P handshake");
        uint256 driverBefore = brz.balanceOf(driver);
        uint256 instituteBefore = brz.balanceOf(institutoKlippel);

        vm.startBroadcast(riderKey);
        brz.approve(address(escrow), fare + riderBond);
        uint256 rideId = escrow.open(driver, brz, fare, quoteHash);
        vm.stopBroadcast();

        vm.startBroadcast(driverKey);
        brz.approve(address(escrow), driverBond);
        escrow.accept(rideId);
        escrow.start(rideId, keccak256("pickup proof signed by both phones"));
        vm.stopBroadcast();

        vm.startBroadcast(riderKey);
        escrow.complete(rideId, keccak256("trip receipt signed by both phones"));
        reputation.rate(rideId, 5);
        vm.stopBroadcast();

        vm.startBroadcast(driverKey);
        reputation.rate(rideId, 5);
        vm.stopBroadcast();

        vm.startBroadcast(deployerKey);
        vault.sweep(brz);
        vm.stopBroadcast();

        console2.log("Ride id", rideId);
        console2.log("Driver received (BRZ base units)", brz.balanceOf(driver) - driverBefore);
        console2.log(
            "Instituto Klippel received (BRZ base units)", brz.balanceOf(institutoKlippel) - instituteBefore
        );
        console2.log("Tax on the ride (BRZ base units)", escrow.ride(rideId).tax);
        console2.log("Driver score x100", reputation.scoreOf(driver));
    }
}
