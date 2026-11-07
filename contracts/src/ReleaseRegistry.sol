// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Which releases of KlippelGo people may run. A release is code built once at a tag: its code
/// contracts and the app built with their addresses. Each one is published here with the hash of those
/// addresses and the SHA-256 of every APK built from it, so a phone checks its own build and any download, from
/// any mirror, against the chain (docs/releases-and-data.md).
///
/// Every app build has this contract's address compiled in next to the governor's, so it stays for the network's
/// life. The app reads its own release's status when it starts, and this contract's events in the log batch it
/// already makes:
///
///   Active      works
///   Deprecated  works, and the app asks its user to update
///   Revoked     the app lets a ride under way finish and money be withdrawn, and starts no new ride
///
/// This is what an honest app obeys. What stops anyone, with any app, from opening a ride on code with a known
/// bug is closing that release's escrow, which the `revoke-release` playbook does in the same action.
contract ReleaseRegistry is Governed {
    enum Status {
        None,
        Active,
        Deprecated,
        Revoked
    }

    struct Release {
        bytes32 contracts;
        Status status;
        uint64 publishedAt;
    }

    /// @notice APKs one release may name: one per architecture and a universal one, with room to spare.
    uint256 public constant MAX_APKS = 8;

    /// @notice A version is MAJOR.MINOR.PATCH, packed as `major << 32 | minor << 16 | patch` (`versionOf`).
    mapping(uint48 version => Release) internal _releases;
    mapping(uint48 version => mapping(bytes32 sha256 => bool)) public isApk;
    /// @notice The highest version published: the one an app offers to update to, while it is active. A backport
    /// published after it (1.9.1 after 2.0.0) does not take its place.
    uint48 public latest;

    event Published(uint48 indexed version, bytes32 contracts, bytes32[] apks);
    event StatusSet(uint48 indexed version, Status status);

    error InvalidRelease();
    error AlreadyPublished();
    error WrongStatus(Status status);

    constructor(IPBACGovernor governor_) Governed(governor_) {}

    // ---------------------------------------------------------------- governance

    /// @notice Publishes a release as active. A version is published once; a fix is a new version.
    function publish(uint48 version, bytes32 contracts, bytes32[] calldata apks) external onlyGovernor {
        if (version == 0 || contracts == bytes32(0) || apks.length > MAX_APKS) revert InvalidRelease();
        Release storage r = _releases[version];
        if (r.status != Status.None) revert AlreadyPublished();
        r.contracts = contracts;
        r.status = Status.Active;
        r.publishedAt = uint64(block.timestamp);
        for (uint256 i; i < apks.length; ++i) {
            if (apks[i] == bytes32(0)) revert InvalidRelease();
            isApk[version][apks[i]] = true;
        }
        if (version > latest) latest = version;
        emit Published(version, contracts, apks);
        emit StatusSet(version, Status.Active);
    }

    /// @notice The release still works, and apps on it ask their users to update.
    function deprecate(uint48 version) external onlyGovernor {
        _move(version, Status.Active, Status.Active, Status.Deprecated);
    }

    /// @notice Apps on the release start no new ride. Segurança's, at once, after a vulnerability.
    function revoke(uint48 version) external onlyGovernor {
        _move(version, Status.Active, Status.Deprecated, Status.Revoked);
    }

    /// @notice Puts a deprecated or revoked release back, as the board's `resume-rides` puts policies back.
    function reinstate(uint48 version) external onlyGovernor {
        _move(version, Status.Deprecated, Status.Revoked, Status.Active);
    }

    function _move(uint48 version, Status from1, Status from2, Status to) internal {
        Release storage r = _releases[version];
        if (r.status != from1 && r.status != from2) revert WrongStatus(r.status);
        r.status = to;
        emit StatusSet(version, to);
    }

    // ---------------------------------------------------------------- views

    function release(uint48 version) external view returns (Release memory) {
        return _releases[version];
    }

    function statusOf(uint48 version) external view returns (Status) {
        return _releases[version].status;
    }

    function versionOf(uint16 major, uint16 minor, uint16 patch) public pure returns (uint48) {
        return (uint48(major) << 32) | (uint48(minor) << 16) | uint48(patch);
    }

    /// @notice What a release's `contracts` is: the hash of its code contracts' addresses, in this order. The app
    /// computes the same from the addresses compiled into it (`Deployment.releaseContracts` in the app).
    function contractsHash(
        address escrow,
        address court,
        address feeVault,
        address registry,
        address accountFactory,
        address paymaster
    ) public pure returns (bytes32) {
        return keccak256(abi.encode(escrow, court, feeVault, registry, accountFactory, paymaster));
    }
}
