// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Account} from "@openzeppelin/contracts/account/Account.sol";
import {ERC7821} from "@openzeppelin/contracts/account/extensions/draft-ERC7821.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/IERC4337.sol";
import {IERC1271} from "@openzeppelin/contracts/interfaces/IERC1271.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Initializable} from "@openzeppelin/contracts/proxy/utils/Initializable.sol";
import {MultiSignerERC7913} from "@openzeppelin/contracts/utils/cryptography/signers/MultiSignerERC7913.sol";

/// @notice A person's account: an ERC-4337 smart account owned by keys the phone keeps in hardware. Each signer
/// is a verifier's address followed by a key: the phone's P-256 key through `DeviceKeyVerifier`, and, for
/// recovery, a passkey through a WebAuthn verifier. Any one of them signs alone (threshold 1).
///
/// Clones of one implementation, made by `AccountFactory`, so an account's address is known from its first key
/// before anything is sent. It runs batches of calls (`execute`, ERC-7821) for the EntryPoint, and answers
/// `isValidSignature` (ERC-1271) so that peers can check what the account signed off chain: the certificate
/// that lets a phone's short-lived gossip key speak for it.
contract KlippelGoAccount is Account, MultiSignerERC7913, ERC7821, Initializable, IERC1271 {
    using SafeERC20 for IERC20;

    IEntryPoint private immutable _entryPoint;

    /// @dev The implementation gets a signer no key matches, so that it can never be used itself; clones start
    /// with none and get theirs in `initialize`.
    constructor(IEntryPoint entryPoint_) MultiSignerERC7913(_unusable(), 1) {
        _entryPoint = entryPoint_;
        _disableInitializers();
    }

    /// @notice Sets the account's first signers, and lets the paymaster take gas in each BRL coin it will be
    /// charged in, since the very first operation must already pay.
    function initialize(bytes[] calldata signers, address paymaster, IERC20[] calldata coins)
        external
        initializer
    {
        _addSigners(signers);
        _setThreshold(1);
        for (uint256 i; i < coins.length; ++i) {
            coins[i].forceApprove(paymaster, type(uint256).max);
        }
    }

    function entryPoint() public view override returns (IEntryPoint) {
        return _entryPoint;
    }

    // ---------------------------------------------------------------- keys, changed by the account itself

    /// @notice Adds keys: a new phone's, or a passkey for recovery. Only through an operation the account signed.
    function addSigners(bytes[] calldata signers) external {
        _checkEntryPointOrSelf();
        _addSigners(signers);
    }

    /// @notice Removes keys, a lost phone's for one. The account must keep at least one.
    function removeSigners(bytes[] calldata signers) external {
        _checkEntryPointOrSelf();
        _removeSigners(signers);
    }

    /// @notice Lets the paymaster take gas in another coin, or stops it (an amount of zero).
    function approvePaymaster(IERC20 coin, address paymaster, uint256 amount) external {
        _checkEntryPointOrSelf();
        coin.forceApprove(paymaster, amount);
    }

    // ---------------------------------------------------------------- ERC-1271

    /// @notice Whether one of the account's keys signed `hash`. The hashes KlippelGo asks this of are its own,
    /// with a domain tag, the chain and the account in them (a session certificate), so the same signature
    /// cannot be shown for another account or another purpose.
    function isValidSignature(bytes32 hash, bytes calldata signature) external view returns (bytes4) {
        return
            _rawSignatureValidation(hash, signature) ? IERC1271.isValidSignature.selector : bytes4(0xffffffff);
    }

    // ---------------------------------------------------------------- internal

    function _erc7821AuthorizedExecutor(address caller, bytes32 mode, bytes calldata executionData)
        internal
        view
        override
        returns (bool)
    {
        return caller == address(entryPoint())
            || super._erc7821AuthorizedExecutor(caller, mode, executionData);
    }

    function _unusable() private pure returns (bytes[] memory s) {
        s = new bytes[](1);
        // An address as a signer is an ECDSA signer; no key recovers to this one.
        s[0] = abi.encodePacked(address(0xdead));
    }
}
