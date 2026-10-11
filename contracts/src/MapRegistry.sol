// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice The public record of the map packs phones compute routes and trip proofs on, and of the tolerances
/// the proof verifier uses.
///
/// A pack is everything a phone needs for one region, built from one OpenStreetMap snapshot. Its id is the
/// keccak-256 of its manifest. Every route quote and trip receipt names the pack it was computed on, and
/// anyone checking one looks up the pack's `proofRoot` here: the Merkle root of the pack's proof index, the
/// canonical table of the road network (docs/maps-and-routing.md).
///
/// Nobody is trusted for that root. Validators rebuild the proof index from the same snapshot and attest the
/// root they got, and governance can activate a pack only once a quorum of them reported the same one.
contract MapRegistry is Governed {
    struct Pack {
        string region; // e.g. "br-pr-cascavel"
        uint32 version; // year and month, e.g. 202611
        uint64 osmSequence; // replication sequence number of the Geofabrik extract
        bytes32 osmSha256; // SHA-256 of the .osm.pbf
        bytes32 proofRoot;
        string cid; // IPFS CID of the pack
        uint64 activeFrom;
        uint64 retiredAfter; // 0 while no newer pack has replaced it
    }

    /// @dev Mirrors `Params` in the verifier crate (core/verify).
    struct VerifierParams {
        uint32 endpointToleranceM; // how far a path may start from the pickup and end from the drop-off
        uint32 distanceToleranceBps; // how far the two phones' route distances may differ
        uint32 traceMarginM; // slack on top of a GPS fix's own accuracy
        uint32 maxAccuracyM; // fixes worse than this do not place a trip on its path
        uint32 detourToleranceBps; // how much longer the driven path may be than the log's own movement
        uint32 maxSpeedCmS;
        uint32 maxAccelCmS2;
        uint32 maxHeadIntervalMs; // longest stretch of trace one signed head may cover
    }

    mapping(bytes32 manifestHash => Pack) internal _packs;
    /// @notice The newest activated pack of a region, by keccak256 of the region name.
    mapping(bytes32 regionKey => bytes32 manifestHash) public latest;
    mapping(bytes32 manifestHash => mapping(address validator => bytes32 proofRoot)) public attestedRoot;
    mapping(bytes32 manifestHash => mapping(bytes32 proofRoot => uint32)) public attestations;

    /// @notice Independent rebuilds that must report the same root before a pack can be activated.
    uint32 public rebuildQuorum;
    /// @notice How long a pack stays valid after a newer one of its region becomes active.
    uint64 public gracePeriod;
    VerifierParams public verifierParams;

    event RootAttested(
        bytes32 indexed manifestHash, address indexed validator, bytes32 proofRoot, uint32 count
    );
    event PackActivated(bytes32 indexed manifestHash, bytes32 indexed regionKey, Pack pack);
    event PackRetired(bytes32 indexed manifestHash, uint64 retiredAfter);
    event RebuildQuorumSet(uint32 quorum);
    event GracePeriodSet(uint64 gracePeriod);
    event VerifierParamsSet(VerifierParams params);

    error InvalidRoot();
    error AlreadyAttested();
    error AlreadyActivated();
    error QuorumNotReached(uint32 attestations, uint32 quorum);
    error UnknownPack();

    constructor(
        IPBACGovernor governor_,
        uint32 rebuildQuorum_,
        uint64 gracePeriod_,
        VerifierParams memory params_
    ) Governed(governor_) {
        rebuildQuorum = rebuildQuorum_;
        gracePeriod = gracePeriod_;
        verifierParams = params_;
    }

    // ---------------------------------------------------------------- validators

    /// @notice A validator reports the proof root it got by rebuilding a pack's proof index. Gated by policy.
    function attest(bytes32 manifestHash, bytes32 proofRoot) external permitted {
        if (proofRoot == bytes32(0)) revert InvalidRoot();
        if (attestedRoot[manifestHash][msg.sender] != bytes32(0)) revert AlreadyAttested();
        attestedRoot[manifestHash][msg.sender] = proofRoot;
        uint32 count = ++attestations[manifestHash][proofRoot];
        emit RootAttested(manifestHash, msg.sender, proofRoot, count);
    }

    // ---------------------------------------------------------------- governance

    /// @notice Activates a pack whose root a quorum of rebuilds agreed on. The region's previous pack stays
    /// valid for the grace period, so rides quoted on it can still finish.
    /// @param info `activeFrom` of 0 means now; `retiredAfter` is ignored.
    function activate(bytes32 manifestHash, Pack calldata info) external onlyGovernor {
        if (_packs[manifestHash].proofRoot != bytes32(0)) revert AlreadyActivated();
        if (info.proofRoot == bytes32(0)) revert InvalidRoot();
        uint32 count = attestations[manifestHash][info.proofRoot];
        if (count < rebuildQuorum) revert QuorumNotReached(count, rebuildQuorum);

        uint64 activeFrom = info.activeFrom == 0 ? uint64(block.timestamp) : info.activeFrom;
        bytes32 regionKey = keccak256(bytes(info.region));
        bytes32 previous = latest[regionKey];
        if (previous != bytes32(0)) {
            _packs[previous].retiredAfter = activeFrom + gracePeriod;
            emit PackRetired(previous, activeFrom + gracePeriod);
        }
        latest[regionKey] = manifestHash;

        Pack storage p = _packs[manifestHash];
        p.region = info.region;
        p.version = info.version;
        p.osmSequence = info.osmSequence;
        p.osmSha256 = info.osmSha256;
        p.proofRoot = info.proofRoot;
        p.cid = info.cid;
        p.activeFrom = activeFrom;
        emit PackActivated(manifestHash, regionKey, p);
    }

    /// @notice Sets when a pack stops being valid, e.g. to pull one built from a vandalised snapshot.
    function retire(bytes32 manifestHash, uint64 retiredAfter) external onlyGovernor {
        if (_packs[manifestHash].proofRoot == bytes32(0)) revert UnknownPack();
        _packs[manifestHash].retiredAfter = retiredAfter;
        emit PackRetired(manifestHash, retiredAfter);
    }

    function setRebuildQuorum(uint32 quorum) external onlyGovernor {
        rebuildQuorum = quorum;
        emit RebuildQuorumSet(quorum);
    }

    function setGracePeriod(uint64 gracePeriod_) external onlyGovernor {
        gracePeriod = gracePeriod_;
        emit GracePeriodSet(gracePeriod_);
    }

    function setVerifierParams(VerifierParams calldata params_) external onlyGovernor {
        verifierParams = params_;
        emit VerifierParamsSet(params_);
    }

    // ---------------------------------------------------------------- views

    function pack(bytes32 manifestHash) external view returns (Pack memory) {
        return _packs[manifestHash];
    }

    /// @notice The root to check a proof against, or zero for a pack that was never activated.
    function proofRootOf(bytes32 manifestHash) external view returns (bytes32) {
        return _packs[manifestHash].proofRoot;
    }

    /// @notice Whether quotes may be made on the pack at `timestamp`.
    function isActive(bytes32 manifestHash, uint64 timestamp) public view returns (bool) {
        Pack storage p = _packs[manifestHash];
        return p.proofRoot != bytes32(0) && p.activeFrom <= timestamp
            && (p.retiredAfter == 0 || timestamp <= p.retiredAfter);
    }
}
