// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/IERC4337.sol";
import {ProtocolDeployer} from "./ProtocolDeployer.sol";
import {KlippelGoAccount} from "../src/account/KlippelGoAccount.sol";
import {AccountFactory} from "../src/account/AccountFactory.sol";
import {DeviceKeyVerifier} from "../src/account/DeviceKeyVerifier.sol";
import {BrlPaymaster} from "../src/account/BrlPaymaster.sol";
import {
    ERC7913WebAuthnVerifier
} from "@openzeppelin/contracts/utils/cryptography/verifiers/ERC7913WebAuthnVerifier.sol";

/// @notice Deploys what people's accounts need over a deployed protocol: the verifier of phones' keys, the
/// account implementation and its factory, and the paymaster that charges gas in reais, funded and staked at
/// the EntryPoint. The EntryPoint itself is the network's: on a local testnet the deploy script makes one.
abstract contract AccountsDeployer is ProtocolDeployer {
    struct Accounts {
        IEntryPoint entryPoint;
        DeviceKeyVerifier verifier;
        /// @dev Passkeys (WebAuthn), the recovery signer an account may add (docs/accounts-and-pix.md).
        ERC7913WebAuthnVerifier webAuthnVerifier;
        KlippelGoAccount implementation;
        AccountFactory factory;
        BrlPaymaster paymaster;
    }

    /// @param deposit Native coin the paymaster starts with at the EntryPoint, to pay for people's operations.
    /// @param stake Native coin staked for it, which bundlers ask of a paymaster that reads other contracts.
    function _deployAccounts(
        Protocol memory p,
        IEntryPoint entryPoint,
        IERC20[] memory coins,
        uint256 deposit,
        uint256 stake
    ) internal returns (Accounts memory a) {
        a.entryPoint = entryPoint;
        a.verifier = new DeviceKeyVerifier();
        a.webAuthnVerifier = new ERC7913WebAuthnVerifier();
        a.paymaster = new BrlPaymaster(p.governor, entryPoint, p.escrow, p.fares);
        a.implementation = new KlippelGoAccount(entryPoint);
        a.factory = new AccountFactory(a.implementation, address(a.paymaster), coins);
        if (deposit > 0) a.paymaster.deposit{value: deposit}();
        if (stake > 0) a.paymaster.addStake{value: stake}(1 days);
    }
}
