// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Pseudonymous identities: roles, stake and hashes of credentials. The credentials themselves
/// (KYC, CNH, vehicle documents) stay on the user's phone; only their hash and the attester go on-chain.
contract IdentityRegistry is Governed {
    using SafeERC20 for IERC20;

    uint8 public constant ROLE_RIDER = 1;
    uint8 public constant ROLE_DRIVER = 2;
    uint8 public constant ROLE_VALIDATOR = 4;
    uint8 internal constant ALL_ROLES = ROLE_RIDER | ROLE_DRIVER | ROLE_VALIDATOR;

    struct Identity {
        uint8 roles;
        uint64 registeredAt;
        uint64 unbondingAt;
        uint256 stake;
        uint256 unbonding;
    }

    struct Credential {
        bytes32 hash;
        address attester;
        uint64 expiresAt;
        bool revoked;
    }

    IERC20 public immutable stakeToken;
    uint64 public unbondingPeriod;

    mapping(address => Identity) internal _identities;
    mapping(address subject => mapping(bytes32 kind => Credential)) public credentials;
    mapping(address => bool) public trustedAttester;

    event Registered(address indexed subject, uint8 roles);
    event Staked(address indexed subject, uint256 amount, uint256 total);
    event UnstakeRequested(address indexed subject, uint256 amount, uint64 availableAt);
    event Withdrawn(address indexed subject, uint256 amount);
    event Attested(address indexed subject, bytes32 indexed kind, bytes32 hash, address indexed attester);
    event Revoked(address indexed subject, bytes32 indexed kind, address by);
    event AttesterSet(address indexed attester, bool trusted);
    event UnbondingPeriodSet(uint64 period);

    error InvalidRoles();
    error NotRegistered();
    error InsufficientStake();
    error StillUnbonding();
    error UntrustedAttester();
    error NotIssuer();

    constructor(IPBACGovernor governor_, IERC20 stakeToken_, uint64 unbondingPeriod_) Governed(governor_) {
        stakeToken = stakeToken_;
        unbondingPeriod = unbondingPeriod_;
    }

    // ---------------------------------------------------------------- users

    /// @notice Claim roles. Claiming a role is free; what a role may do is decided by governor policies
    /// (e.g. accepting rides also needs stake and a CNH credential).
    function register(uint8 roles) external {
        if (roles == 0 || roles & ~ALL_ROLES != 0) revert InvalidRoles();
        Identity storage id = _identities[msg.sender];
        if (id.registeredAt == 0) id.registeredAt = uint64(block.timestamp);
        id.roles |= roles;
        emit Registered(msg.sender, id.roles);
    }

    function addStake(uint256 amount) external {
        Identity storage id = _identities[msg.sender];
        if (id.registeredAt == 0) revert NotRegistered();
        stakeToken.safeTransferFrom(msg.sender, address(this), amount);
        id.stake += amount;
        emit Staked(msg.sender, amount, id.stake);
    }

    /// @notice Stake leaves only after an unbonding period, so it cannot be flash-borrowed to vote or to pass
    /// a policy check.
    function requestUnstake(uint256 amount) external {
        Identity storage id = _identities[msg.sender];
        if (amount > id.stake) revert InsufficientStake();
        id.stake -= amount;
        id.unbonding += amount;
        id.unbondingAt = uint64(block.timestamp) + unbondingPeriod;
        emit UnstakeRequested(msg.sender, amount, id.unbondingAt);
    }

    function withdrawStake() external {
        Identity storage id = _identities[msg.sender];
        if (block.timestamp < id.unbondingAt) revert StillUnbonding();
        uint256 amount = id.unbonding;
        id.unbonding = 0;
        stakeToken.safeTransfer(msg.sender, amount);
        emit Withdrawn(msg.sender, amount);
    }

    // ---------------------------------------------------------------- attesters

    function attest(address subject, bytes32 kind, bytes32 hash, uint64 expiresAt) external {
        if (!trustedAttester[msg.sender]) revert UntrustedAttester();
        if (_identities[subject].registeredAt == 0) revert NotRegistered();
        credentials[subject][kind] = Credential(hash, msg.sender, expiresAt, false);
        emit Attested(subject, kind, hash, msg.sender);
    }

    function revoke(address subject, bytes32 kind) external {
        Credential storage c = credentials[subject][kind];
        if (msg.sender != c.attester && msg.sender != address(governor)) revert NotIssuer();
        c.revoked = true;
        emit Revoked(subject, kind, msg.sender);
    }

    // ---------------------------------------------------------------- views

    function identity(address subject) external view returns (Identity memory) {
        return _identities[subject];
    }

    function rolesOf(address subject) external view returns (uint8) {
        return _identities[subject].roles;
    }

    function stakeOf(address subject) external view returns (uint256) {
        return _identities[subject].stake;
    }

    function registeredAt(address subject) external view returns (uint64) {
        return _identities[subject].registeredAt;
    }

    /// @notice True if the subject holds an unexpired, unrevoked credential of this kind from an attester that
    /// governance still trusts.
    function hasCredential(address subject, bytes32 kind) public view returns (bool) {
        Credential storage c = credentials[subject][kind];
        return c.hash != bytes32(0) && !c.revoked && trustedAttester[c.attester]
            && (c.expiresAt == 0 || c.expiresAt > block.timestamp);
    }

    // ---------------------------------------------------------------- governance

    function setAttester(address attester, bool trusted) external onlyGovernor {
        trustedAttester[attester] = trusted;
        emit AttesterSet(attester, trusted);
    }

    function setUnbondingPeriod(uint64 period) external onlyGovernor {
        unbondingPeriod = period;
        emit UnbondingPeriodSet(period);
    }
}
