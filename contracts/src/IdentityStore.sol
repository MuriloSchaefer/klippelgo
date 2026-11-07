// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {DataContract} from "./DataContract.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Pseudonymous identities, kept for as long as a person is on the network: roles, when they registered,
/// hashes of credentials and who issued them, the trusted attesters, and the accounts the guardian blocked. The
/// credentials themselves (KYC, CNH, vehicle documents) stay on the user's phone.
///
/// A data contract: a release's registry adds roles through the writer list, and its rules are fixed here, so no
/// release can take a role away or move a registration date. Stake is not here but in `StakeVault`, whose code
/// nobody can wire around. Attesters issue and revoke credentials here directly, by the rules below, which no
/// release changes either.
contract IdentityStore is DataContract {
    uint8 public constant ROLE_RIDER = 1;
    uint8 public constant ROLE_DRIVER = 2;
    uint8 public constant ROLE_VALIDATOR = 4;
    uint8 public constant ALL_ROLES = ROLE_RIDER | ROLE_DRIVER | ROLE_VALIDATOR;

    struct Identity {
        uint8 roles;
        uint64 registeredAt;
    }

    struct Credential {
        bytes32 hash;
        address attester;
        uint64 expiresAt;
        bool revoked;
    }

    mapping(address => Identity) internal _identities;
    mapping(address subject => mapping(bytes32 kind => Credential)) public credentials;
    mapping(address => bool) public trustedAttester;
    /// @notice Accounts governance has blocked. The governor's policies refuse them, so they start nothing new;
    /// nothing here stops a blocked account from finishing a ride under way or taking its stake out.
    mapping(address => bool) public blocked;

    event Registered(address indexed subject, uint8 roles);
    event Attested(address indexed subject, bytes32 indexed kind, bytes32 hash, address indexed attester);
    event Revoked(address indexed subject, bytes32 indexed kind, address by);
    event AttesterSet(address indexed attester, bool trusted);
    event BlockedSet(address indexed subject, bool blocked);

    error InvalidRoles();
    error NotRegistered();
    error UntrustedAttester();
    error NotIssuer();

    constructor(IPBACGovernor governor_) DataContract(governor_) {}

    // ---------------------------------------------------------------- writers

    /// @notice Gives `subject` these roles on top of those it has. Roles only grow, and the first registration's
    /// time never moves: account age is something a policy may require.
    function addRoles(address subject, uint8 roles) external onlyWriter {
        if (roles == 0 || roles & ~ALL_ROLES != 0) revert InvalidRoles();
        Identity storage id = _identities[subject];
        if (id.registeredAt == 0) id.registeredAt = uint64(block.timestamp);
        id.roles |= roles;
        emit Registered(subject, id.roles);
    }

    // ---------------------------------------------------------------- attesters

    function attest(address subject, bytes32 kind, bytes32 hash, uint64 expiresAt) external {
        if (!trustedAttester[msg.sender]) revert UntrustedAttester();
        if (_identities[subject].registeredAt == 0) revert NotRegistered();
        // One attester cannot take over a credential another issued while it still counts: it could then
        // revoke it, which only the issuer and governance may.
        if (credentials[subject][kind].attester != msg.sender && hasCredential(subject, kind)) {
            revert NotIssuer();
        }
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

    /// @notice Block or unblock an account. It applies to every action gated by a policy, from now on.
    function setBlocked(address subject, bool blocked_) external onlyGovernor {
        blocked[subject] = blocked_;
        emit BlockedSet(subject, blocked_);
    }
}
