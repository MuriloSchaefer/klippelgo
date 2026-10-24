// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IEntryPoint, PackedUserOperation} from "@openzeppelin/contracts/interfaces/IERC4337.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {ERC4337Utils} from "@openzeppelin/contracts/account/utils/ERC4337Utils.sol";
import {Paymaster} from "@openzeppelin/contracts/account/paymaster/Paymaster.sol";
import {PaymasterERC20} from "@openzeppelin/contracts/account/paymaster/extensions/PaymasterERC20.sol";
import {Governed} from "../Governed.sol";
import {IPBACGovernor} from "../interfaces/IPBACGovernor.sol";
import {RideEscrow} from "../RideEscrow.sol";
import {FareGuide} from "../FareGuide.sol";

/// @notice Pays the network for people's operations and charges them the same in reais, so nobody holds the
/// chain's own coin. An operation names the BRL coin it pays in (`paymasterData`, 20 bytes); it must be on the
/// escrow's allow-list. The price is what `FareGuide` says one native coin is worth, the rate governance already
/// keeps for showing a ride's infrastructure cost.
///
/// The paymaster charges the most the operation could cost before it runs, and gives back what it did not use.
/// What it collects stays here until Finanças sends it on (`withdrawTokens`); the native coin it spends comes
/// from its deposit at the EntryPoint, which anyone may top up.
contract BrlPaymaster is PaymasterERC20, Governed {
    IEntryPoint private immutable _entryPoint;
    RideEscrow public immutable escrow;
    FareGuide public immutable fares;

    constructor(IPBACGovernor governor_, IEntryPoint entryPoint_, RideEscrow escrow_, FareGuide fares_)
        Governed(governor_)
    {
        _entryPoint = entryPoint_;
        escrow = escrow_;
        fares = fares_;
    }

    function entryPoint() public view override returns (IEntryPoint) {
        return _entryPoint;
    }

    // ---------------------------------------------------------------- the deposit and the stake

    /// @notice Adds to the native coin the paymaster spends at the EntryPoint. Anyone may.
    function deposit() external payable {
        _deposit(msg.value);
    }

    /// @notice Stakes at the EntryPoint, which bundlers ask of a paymaster that reads other contracts. Anyone may
    /// add to it; only the guardian may take it out.
    function addStake(uint32 unstakeDelaySec) external payable {
        _addStake(msg.value, unstakeDelaySec);
    }

    function withdraw(address payable to, uint256 value) external onlyGovernor {
        _withdraw(to, value);
    }

    function unlockStake() external onlyGovernor {
        _unlockStake();
    }

    function withdrawStake(address payable to) external onlyGovernor {
        _withdrawStake(to);
    }

    /// @notice Sends on the reais collected for gas.
    function withdrawTokens(IERC20 token, address recipient, uint256 amount) external onlyGovernor {
        _withdrawTokens(token, recipient, amount);
    }

    // ---------------------------------------------------------------- pricing

    /// @dev The coin comes from the operation; its price in reais is fixed, one real per real, and the native
    /// coin's price comes from `FareGuide` in centavos. A coin off the allow-list, or no price set, fails the
    /// operation rather than giving gas away.
    function _fetchDetails(PackedUserOperation calldata userOp, bytes32)
        internal
        view
        override
        returns (uint256 validationData, IERC20 token, uint256 tokenPerNative)
    {
        bytes calldata data = ERC4337Utils.paymasterData(userOp);
        if (data.length < 20) return (ERC4337Utils.SIG_VALIDATION_FAILED, IERC20(address(0)), 0);
        token = IERC20(address(bytes20(data[0:20])));
        (uint256 fee,,) = escrow.tokenConfig(token);
        uint256 centavos = fares.nativeCoinCentavos();
        if (fee == 0 || centavos == 0) return (ERC4337Utils.SIG_VALIDATION_FAILED, token, 0);
        // Token units for one native coin: centavos ÷ 100 reais, each real being the escrow's fee, which governance
        // set as R$ 1,00 in this coin's units. Not the coin's own `decimals()`, which its issuer could change.
        tokenPerNative = centavos * fee / 100;
        return (ERC4337Utils.SIG_VALIDATION_SUCCESS, token, tokenPerNative);
    }

    /// @dev Never charge nothing: an operation priced at zero would be gas given away.
    function _minTokensPerNative() internal pure override returns (uint256) {
        return 1;
    }
}
