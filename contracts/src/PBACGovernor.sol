// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";
import {IdentityRegistry} from "./IdentityRegistry.sol";
import {Reputation} from "./Reputation.sol";

/// @notice Policy-based access control, and the one authority over the protocol: the guardian.
///
/// A policy is a rule evaluated against the caller's on-chain attributes, keyed by (target contract, function
/// selector): roles, stake, reputation score, account age and a required credential kind. An account blocked
/// in the registry satisfies no policy.
///
/// The guardian is Instituto Klippel. It governs who may use the network and every parameter of it: whatever
/// a contract guards with `onlyGovernor`, the guardian does through `execute`, at once. Nobody else can, and
/// nobody but the guardian can name its successor. The contracts' code never changes: a fix is a new contract
/// the guardian wires in, next to the ledgers that keep everyone's stake, credentials and reputation.
///
/// Deployment needs a short bootstrap: the deployer wires the contracts together through `bootstrapCall`, then
/// calls `finishBootstrap`, after which the guardian alone holds any privilege.
contract PBACGovernor is IPBACGovernor {
    struct Policy {
        bool enabled;
        uint8 roles; // any of these role bits; 0 = no role needed
        uint16 minScore; // reputation x100; unrated accounts pass until their first rating
        uint32 minAccountAge; // seconds since registration
        uint256 minStake;
        bytes32 credential; // required credential kind, 0 = none
    }

    IdentityRegistry public registry;
    Reputation public reputation;
    address public bootstrapper;
    address public guardian;
    /// @notice Who the guardian named to take over. They become guardian only by accepting.
    address public pendingGuardian;

    mapping(address target => mapping(bytes4 action => Policy)) internal _policies;

    event PolicySet(address indexed target, bytes4 indexed action, Policy policy);
    event ModulesSet(address registry, address reputation);
    event BootstrapFinished();
    event Executed(address indexed target, bytes data);
    event GuardianProposed(address indexed guardian, address indexed proposed);
    event GuardianChanged(address indexed from, address indexed to);

    error NotSelf();
    error NotBootstrapper();
    error NotGuardian();
    error NotPendingGuardian();
    error NoGuardian();
    error InvalidCalls();
    error CallFailed(uint256 index, bytes reason);

    constructor(address guardian_) {
        if (guardian_ == address(0)) revert NoGuardian();
        bootstrapper = msg.sender;
        guardian = guardian_;
        emit GuardianChanged(address(0), guardian_);
    }

    modifier onlySelf() {
        if (msg.sender != address(this)) revert NotSelf();
        _;
    }

    modifier onlyGuardian() {
        if (msg.sender != guardian) revert NotGuardian();
        _;
    }

    // ---------------------------------------------------------------- PBAC

    function hasPermission(address subject, address target, bytes4 action) public view returns (bool) {
        Policy memory p = _policies[target][action];
        if (!p.enabled) return false;
        if (registry.blocked(subject)) return false;

        if (p.roles != 0 || p.minStake != 0 || p.minAccountAge != 0) {
            IdentityRegistry.Identity memory id = registry.identity(subject);
            if (id.registeredAt == 0) return false;
            if (p.roles != 0 && id.roles & p.roles == 0) return false;
            if (id.stake < p.minStake) return false;
            if (block.timestamp - id.registeredAt < p.minAccountAge) return false;
        }
        if (
            p.minScore != 0 && reputation.ratingCount(subject) > 0 && reputation.scoreOf(subject) < p.minScore
        ) {
            return false;
        }
        if (p.credential != bytes32(0) && !registry.hasCredential(subject, p.credential)) return false;
        return true;
    }

    function policy(address target, bytes4 action) external view returns (Policy memory) {
        return _policies[target][action];
    }

    // ---------------------------------------------------------------- the guardian

    /// @notice Runs the calls as the governor, in order, all or none. This is how the guardian sets a
    /// parameter, a policy or a block, and how it wires in a contract that replaces another.
    function execute(address[] calldata targets, bytes[] calldata data) external onlyGuardian {
        if (targets.length == 0 || targets.length != data.length) revert InvalidCalls();
        for (uint256 i; i < targets.length; ++i) {
            (bool ok, bytes memory reason) = targets[i].call(data[i]);
            if (!ok) revert CallFailed(i, reason);
            emit Executed(targets[i], data[i]);
        }
    }

    /// @notice Names who may take over as guardian. Nothing changes until they accept, so a mistyped address
    /// costs nothing: name another. The zero address withdraws the offer. The governor itself cannot be named:
    /// it could accept through `execute`, and then nobody could call `execute` again.
    function proposeGuardian(address proposed) external onlyGuardian {
        if (proposed == address(this)) revert NoGuardian();
        pendingGuardian = proposed;
        emit GuardianProposed(msg.sender, proposed);
    }

    function acceptGuardian() external {
        if (msg.sender != pendingGuardian) revert NotPendingGuardian();
        emit GuardianChanged(guardian, msg.sender);
        guardian = msg.sender;
        pendingGuardian = address(0);
    }

    // ---------------------------------------------------------------- setters, reached through execute

    function setPolicy(address target, bytes4 action, Policy calldata p) external onlySelf {
        _policies[target][action] = p;
        emit PolicySet(target, action, p);
    }

    function setModules(IdentityRegistry registry_, Reputation reputation_) external onlySelf {
        registry = registry_;
        reputation = reputation_;
        emit ModulesSet(address(registry_), address(reputation_));
    }

    // ---------------------------------------------------------------- bootstrap

    /// @notice During deployment only: run a call as the governor (wiring modules, first policies, allow-list).
    function bootstrapCall(address target, bytes calldata data) external returns (bytes memory) {
        if (msg.sender != bootstrapper) revert NotBootstrapper();
        (bool ok, bytes memory ret) = target.call(data);
        if (!ok) revert CallFailed(0, ret);
        return ret;
    }

    function finishBootstrap() external {
        if (msg.sender != bootstrapper) revert NotBootstrapper();
        bootstrapper = address(0);
        emit BootstrapFinished();
    }
}
