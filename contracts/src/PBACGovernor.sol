// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";
import {IdentityRegistry} from "./IdentityRegistry.sol";
import {Reputation} from "./Reputation.sol";

/// @notice Policy-based access control plus on-chain governance. Every protocol parameter and every policy is
/// changed only through a proposal that passes a vote and waits out a timelock.
///
/// A policy is a rule evaluated against the caller's on-chain attributes, keyed by (target contract, function
/// selector): roles, stake, reputation score, account age and a required credential kind. Changing a policy
/// is itself an action, guarded by the policy on this contract's `propose` (the meta-policy) plus quorum and
/// timelock.
///
/// Votes are weighted by completed rides and stake, not by a tradeable token.
///
/// Deployment needs a short bootstrap: the deployer wires the contracts together through `bootstrapCall`, then
/// calls `finishBootstrap`, after which nobody holds any privilege outside governance.
contract PBACGovernor is IPBACGovernor {
    struct Policy {
        bool enabled;
        uint8 roles; // any of these role bits; 0 = no role needed
        uint16 minScore; // reputation x100; unrated accounts pass until their first rating
        uint32 minAccountAge; // seconds since registration
        uint256 minStake;
        bytes32 credential; // required credential kind, 0 = none
    }

    struct Proposal {
        address proposer;
        uint64 voteEnd;
        uint64 eta;
        bool executed;
        uint256 forVotes;
        uint256 againstVotes;
        bytes32 callsHash;
    }

    struct Params {
        uint64 votingPeriod;
        uint64 timelockDelay;
        uint256 quorum; // minimum forVotes
        uint256 rideWeight; // votes per completed ride
        uint256 stakeUnit; // stake base units per vote
    }

    IdentityRegistry public registry;
    Reputation public reputation;
    address public bootstrapper;
    Params public params;

    mapping(address target => mapping(bytes4 action => Policy)) internal _policies;
    mapping(uint256 => Proposal) public proposals;
    mapping(uint256 => mapping(address => bool)) public hasVoted;
    uint256 public proposalCount;

    event PolicySet(address indexed target, bytes4 indexed action, Policy policy);
    event ParamsSet(Params params);
    event ModulesSet(address registry, address reputation);
    event BootstrapFinished();
    event ProposalCreated(
        uint256 indexed id,
        address indexed proposer,
        address[] targets,
        uint256[] values,
        bytes[] calldatas,
        string description
    );
    event VoteCast(uint256 indexed id, address indexed voter, bool support, uint256 weight);
    event ProposalQueued(uint256 indexed id, uint64 eta);
    event ProposalExecuted(uint256 indexed id);

    error NotSelf();
    error NotBootstrapper();
    error NotPermitted(address subject, bytes4 action);
    error InvalidProposal();
    error VotingClosed();
    error VotingOpen();
    error AlreadyVoted();
    error NoVotingWeight();
    error Defeated();
    error NotQueued();
    error AlreadyQueued();
    error TimelockActive();
    error CallFailed(uint256 index, bytes reason);

    constructor(Params memory params_) {
        bootstrapper = msg.sender;
        params = params_;
        emit ParamsSet(params_);
    }

    modifier onlySelf() {
        if (msg.sender != address(this)) revert NotSelf();
        _;
    }

    // ---------------------------------------------------------------- PBAC

    function hasPermission(address subject, address target, bytes4 action) public view returns (bool) {
        Policy memory p = _policies[target][action];
        if (!p.enabled) return false;

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

    /// @notice Votes = completed rides x rideWeight + stake / stakeUnit.
    function votingWeight(address voter) public view returns (uint256) {
        Params memory p = params;
        return reputation.completedRides(voter) * p.rideWeight + registry.stakeOf(voter) / p.stakeUnit;
    }

    // ---------------------------------------------------------------- proposals

    function propose(
        address[] calldata targets,
        uint256[] calldata values,
        bytes[] calldata calldatas,
        string calldata description
    ) external returns (uint256 id) {
        if (!hasPermission(msg.sender, address(this), this.propose.selector)) {
            revert NotPermitted(msg.sender, this.propose.selector);
        }
        if (targets.length == 0 || targets.length != values.length || targets.length != calldatas.length) {
            revert InvalidProposal();
        }
        id = ++proposalCount;
        proposals[id] = Proposal({
            proposer: msg.sender,
            voteEnd: uint64(block.timestamp) + params.votingPeriod,
            eta: 0,
            executed: false,
            forVotes: 0,
            againstVotes: 0,
            callsHash: keccak256(abi.encode(targets, values, calldatas))
        });
        emit ProposalCreated(id, msg.sender, targets, values, calldatas, description);
    }

    function vote(uint256 id, bool support) external {
        Proposal storage p = proposals[id];
        if (p.proposer == address(0)) revert InvalidProposal();
        if (block.timestamp >= p.voteEnd) revert VotingClosed();
        if (hasVoted[id][msg.sender]) revert AlreadyVoted();
        uint256 weight = votingWeight(msg.sender);
        if (weight == 0) revert NoVotingWeight();
        hasVoted[id][msg.sender] = true;
        if (support) p.forVotes += weight;
        else p.againstVotes += weight;
        emit VoteCast(id, msg.sender, support, weight);
    }

    function queue(uint256 id) external {
        Proposal storage p = proposals[id];
        if (p.proposer == address(0)) revert InvalidProposal();
        if (block.timestamp < p.voteEnd) revert VotingOpen();
        if (p.eta != 0) revert AlreadyQueued();
        if (p.forVotes <= p.againstVotes || p.forVotes < params.quorum) revert Defeated();
        p.eta = uint64(block.timestamp) + params.timelockDelay;
        emit ProposalQueued(id, p.eta);
    }

    function execute(
        uint256 id,
        address[] calldata targets,
        uint256[] calldata values,
        bytes[] calldata calldatas
    ) external payable {
        Proposal storage p = proposals[id];
        if (p.eta == 0 || p.executed) revert NotQueued();
        if (block.timestamp < p.eta) revert TimelockActive();
        if (keccak256(abi.encode(targets, values, calldatas)) != p.callsHash) revert InvalidProposal();
        p.executed = true;
        for (uint256 i; i < targets.length; ++i) {
            (bool ok, bytes memory reason) = targets[i].call{value: values[i]}(calldatas[i]);
            if (!ok) revert CallFailed(i, reason);
        }
        emit ProposalExecuted(id);
    }

    // ---------------------------------------------------------------- self-governed setters

    function setPolicy(address target, bytes4 action, Policy calldata p) external onlySelf {
        _policies[target][action] = p;
        emit PolicySet(target, action, p);
    }

    function setParams(Params calldata p) external onlySelf {
        params = p;
        emit ParamsSet(p);
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
