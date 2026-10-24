// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Clones} from "@openzeppelin/contracts/proxy/Clones.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {KlippelGoAccount} from "./KlippelGoAccount.sol";

/// @notice Makes people's accounts. An account's address follows from its first key alone, so a phone knows it
/// at once, can receive reais on it, and creates the account with its first operation (the EntryPoint calls
/// `createAccount` from the operation's `initCode`).
///
/// It holds no power: anyone may create any account, and an account belongs to whoever holds its key.
contract AccountFactory {
    using Clones for address;

    KlippelGoAccount public immutable implementation;
    /// @notice The paymaster new accounts let take gas in reais, and the coins they let it take.
    address public immutable paymaster;
    IERC20[] internal _coins;

    event AccountCreated(address indexed account, bytes signer);

    constructor(KlippelGoAccount implementation_, address paymaster_, IERC20[] memory coins_) {
        implementation = implementation_;
        paymaster = paymaster_;
        _coins = coins_;
    }

    /// @notice Creates the account of this first key, or returns it if it exists.
    function createAccount(bytes calldata signer) external returns (KlippelGoAccount account) {
        bytes32 salt = keccak256(signer);
        address predicted = address(implementation).predictDeterministicAddress(salt);
        if (predicted.code.length != 0) return KlippelGoAccount(payable(predicted));
        account = KlippelGoAccount(payable(address(implementation).cloneDeterministic(salt)));
        bytes[] memory signers = new bytes[](1);
        signers[0] = signer;
        account.initialize(signers, paymaster, _coins);
        emit AccountCreated(address(account), signer);
    }

    /// @notice The address the account of this first key has, or will have.
    function accountOf(bytes calldata signer) external view returns (address) {
        return address(implementation).predictDeterministicAddress(keccak256(signer));
    }

    function coins() external view returns (IERC20[] memory) {
        return _coins;
    }
}
