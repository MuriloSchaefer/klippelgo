// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";
import {IdentityStore} from "./IdentityStore.sol";

/// @notice People's stake, held for as long as they drive or validate. The policies read it through `stakeOf`.
///
/// Its code is final and nothing can be wired into it: there is no writer list, no slashing, and only the owner
/// of a stake moves it. A data contract a release's code could write would let a board action wire in code that
/// takes the stake; this one cannot be. A release that wants slashing deploys a new vault and asks people to move
/// their stake into it (docs/releases-and-data.md).
///
/// The guardian sets the unbonding period, never above `MAX_UNBONDING_PERIOD`, so it cannot hold anyone's
/// stake for longer than that.
contract StakeVault is Governed, ReentrancyGuard {
    using SafeERC20 for IERC20;

    /// @notice The longest the guardian may make anyone wait for their stake.
    uint64 public constant MAX_UNBONDING_PERIOD = 30 days;

    struct Stake {
        uint256 stake;
        uint256 unbonding;
        uint64 unbondingAt;
    }

    IERC20 public immutable stakeToken;
    /// @notice Where registrations are: only a registered account stakes.
    IdentityStore public immutable identities;
    uint64 public unbondingPeriod;

    mapping(address => Stake) internal _stakes;

    event Staked(address indexed subject, uint256 amount, uint256 total);
    event UnstakeRequested(address indexed subject, uint256 amount, uint64 availableAt);
    event Withdrawn(address indexed subject, uint256 amount);
    event UnbondingPeriodSet(uint64 period);

    error NotRegistered();
    error InsufficientStake();
    error StillUnbonding();
    error InvalidPeriod();

    constructor(
        IPBACGovernor governor_,
        IdentityStore identities_,
        IERC20 stakeToken_,
        uint64 unbondingPeriod_
    ) Governed(governor_) {
        if (unbondingPeriod_ > MAX_UNBONDING_PERIOD) revert InvalidPeriod();
        identities = identities_;
        stakeToken = stakeToken_;
        unbondingPeriod = unbondingPeriod_;
    }

    // ---------------------------------------------------------------- users

    function addStake(uint256 amount) external nonReentrant {
        if (identities.registeredAt(msg.sender) == 0) revert NotRegistered();
        Stake storage s = _stakes[msg.sender];
        s.stake += amount;
        emit Staked(msg.sender, amount, s.stake);
        stakeToken.safeTransferFrom(msg.sender, address(this), amount);
    }

    /// @notice Stake leaves only after an unbonding period, so it cannot be flash-borrowed to pass a policy check.
    function requestUnstake(uint256 amount) external {
        Stake storage s = _stakes[msg.sender];
        if (amount > s.stake) revert InsufficientStake();
        s.stake -= amount;
        s.unbonding += amount;
        s.unbondingAt = uint64(block.timestamp) + unbondingPeriod;
        emit UnstakeRequested(msg.sender, amount, s.unbondingAt);
    }

    function withdrawStake() external nonReentrant {
        Stake storage s = _stakes[msg.sender];
        if (block.timestamp < s.unbondingAt) revert StillUnbonding();
        uint256 amount = s.unbonding;
        s.unbonding = 0;
        emit Withdrawn(msg.sender, amount);
        stakeToken.safeTransfer(msg.sender, amount);
    }

    // ---------------------------------------------------------------- views

    function stakeOf(address subject) external view returns (uint256) {
        return _stakes[subject].stake;
    }

    function stakeInfo(address subject) external view returns (Stake memory) {
        return _stakes[subject];
    }

    // ---------------------------------------------------------------- governance

    function setUnbondingPeriod(uint64 period) external onlyGovernor {
        if (period > MAX_UNBONDING_PERIOD) revert InvalidPeriod();
        unbondingPeriod = period;
        emit UnbondingPeriodSet(period);
    }
}
