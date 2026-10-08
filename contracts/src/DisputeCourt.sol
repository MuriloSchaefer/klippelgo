// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";
import {RideEscrow} from "./RideEscrow.sol";

/// @notice Commit-reveal juror court. A party raises a dispute with a hash of their evidence, which they reveal
/// off-chain to jurors. Jurors (validators allowed by policy) commit to a rider share in basis points, then
/// reveal it. The ride settles on the average revealed share, or 50/50 if nobody reveals.
contract DisputeCourt is Governed {
    struct Dispute {
        address raisedBy;
        bytes32 evidenceHash;
        uint64 commitEnd;
        uint64 revealEnd;
        uint256 bpsSum;
        uint256 reveals;
        bool settled;
    }

    RideEscrow public immutable escrow;
    uint64 public commitPeriod;
    uint64 public revealPeriod;

    mapping(uint256 rideId => Dispute) public disputes;
    mapping(uint256 rideId => mapping(address juror => bytes32)) public commitments;
    mapping(uint256 rideId => mapping(address juror => bool)) public revealed;

    event DisputeRaised(uint256 indexed rideId, address indexed by, bytes32 evidenceHash);
    event VoteCommitted(uint256 indexed rideId, address indexed juror);
    event VoteRevealed(uint256 indexed rideId, address indexed juror, uint256 riderBps);
    event DisputeSettled(uint256 indexed rideId, uint256 riderBps);
    event PeriodsSet(uint64 commitPeriod, uint64 revealPeriod);

    error NotParty();
    error AlreadyRaised();
    error NoDispute();
    error PartyCannotJudge();
    error NotInCommitPhase();
    error NotInRevealPhase();
    error AlreadyCommitted();
    error BadReveal();
    error TooEarly();
    error AlreadySettled();
    error InvalidBps();

    constructor(IPBACGovernor governor_, RideEscrow escrow_, uint64 commitPeriod_, uint64 revealPeriod_)
        Governed(governor_)
    {
        escrow = escrow_;
        commitPeriod = commitPeriod_;
        revealPeriod = revealPeriod_;
    }

    function raise(uint256 rideId, bytes32 evidenceHash) external {
        (address rider, address driver) = escrow.parties(rideId);
        if (msg.sender != rider && msg.sender != driver) revert NotParty();
        Dispute storage d = disputes[rideId];
        if (d.raisedBy != address(0)) revert AlreadyRaised();

        uint64 commitEnd = uint64(block.timestamp) + commitPeriod;
        disputes[rideId] = Dispute(msg.sender, evidenceHash, commitEnd, commitEnd + revealPeriod, 0, 0, false);
        escrow.freeze(rideId);
        emit DisputeRaised(rideId, msg.sender, evidenceHash);
    }

    /// @param commitment keccak256(abi.encode(rideId, juror, riderBps, salt))
    function commit(uint256 rideId, bytes32 commitment) external permitted {
        Dispute storage d = disputes[rideId];
        if (d.raisedBy == address(0)) revert NoDispute();
        if (block.timestamp >= d.commitEnd) revert NotInCommitPhase();
        (address rider, address driver) = escrow.parties(rideId);
        if (msg.sender == rider || msg.sender == driver) revert PartyCannotJudge();
        if (commitments[rideId][msg.sender] != bytes32(0)) revert AlreadyCommitted();
        commitments[rideId][msg.sender] = commitment;
        emit VoteCommitted(rideId, msg.sender);
    }

    function reveal(uint256 rideId, uint256 riderBps, bytes32 salt) external {
        Dispute storage d = disputes[rideId];
        if (block.timestamp < d.commitEnd || block.timestamp >= d.revealEnd) revert NotInRevealPhase();
        if (riderBps > escrow.BPS()) revert InvalidBps();
        if (revealed[rideId][msg.sender]) revert BadReveal();
        bytes32 expected = keccak256(abi.encode(rideId, msg.sender, riderBps, salt));
        if (commitments[rideId][msg.sender] != expected) revert BadReveal();
        revealed[rideId][msg.sender] = true;
        d.bpsSum += riderBps;
        d.reveals++;
        emit VoteRevealed(rideId, msg.sender, riderBps);
    }

    function settle(uint256 rideId) external {
        Dispute storage d = disputes[rideId];
        if (d.raisedBy == address(0)) revert NoDispute();
        if (block.timestamp < d.revealEnd) revert TooEarly();
        if (d.settled) revert AlreadySettled();
        d.settled = true;
        uint256 riderBps = d.reveals == 0 ? escrow.BPS() / 2 : d.bpsSum / d.reveals;
        escrow.resolve(rideId, riderBps);
        emit DisputeSettled(rideId, riderBps);
    }

    function setPeriods(uint64 commitPeriod_, uint64 revealPeriod_) external onlyGovernor {
        commitPeriod = commitPeriod_;
        revealPeriod = revealPeriod_;
        emit PeriodsSet(commitPeriod_, revealPeriod_);
    }
}
