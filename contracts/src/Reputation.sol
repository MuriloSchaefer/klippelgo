// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {DataContract} from "./DataContract.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Ratings for completed rides. Only the two parties of a ride can rate each other, once each.
///
/// A data contract: it keeps what people earned, and outlives every release's escrow. Its writers are the
/// escrows. Several may report at once, so a new release's escrow can open rides while the one it replaces
/// finishes its own. A ride is known by its escrow and its id there, since each escrow counts its rides from one.
/// The rating rules below are fixed: no release changes how a score is made.
contract Reputation is DataContract {
    struct RideParties {
        address rider;
        address driver;
        bool riderRated;
        bool driverRated;
    }

    mapping(address escrow => mapping(uint256 rideId => RideParties)) public rides;
    mapping(address => uint256) public completedRides;
    mapping(address => uint256) public ratingSum;
    mapping(address => uint256) public ratingCount;

    event RideRecorded(address indexed escrow, uint256 indexed rideId, address indexed rider, address driver);
    event Rated(
        address indexed escrow, uint256 indexed rideId, address from, address indexed to, uint8 stars
    );

    error NotParty();
    error AlreadyRated();
    error InvalidStars();
    error AlreadyRecorded();

    constructor(IPBACGovernor governor_) DataContract(governor_) {}

    function recordCompletion(uint256 rideId, address rider, address driver) external onlyWriter {
        // A ride counts once: an escrow cannot report it again to reset who rated it.
        if (rides[msg.sender][rideId].rider != address(0)) revert AlreadyRecorded();
        rides[msg.sender][rideId] = RideParties(rider, driver, false, false);
        completedRides[rider]++;
        completedRides[driver]++;
        emit RideRecorded(msg.sender, rideId, rider, driver);
    }

    function rate(address escrow, uint256 rideId, uint8 stars) external {
        if (stars < 1 || stars > 5) revert InvalidStars();
        RideParties storage r = rides[escrow][rideId];
        address to;
        if (msg.sender == r.rider) {
            if (r.riderRated) revert AlreadyRated();
            r.riderRated = true;
            to = r.driver;
        } else if (msg.sender == r.driver) {
            if (r.driverRated) revert AlreadyRated();
            r.driverRated = true;
            to = r.rider;
        } else {
            revert NotParty();
        }
        ratingSum[to] += stars;
        ratingCount[to]++;
        emit Rated(escrow, rideId, msg.sender, to, stars);
    }

    /// @notice Average stars times 100 (450 = 4.50). Zero when the address has no ratings yet.
    function scoreOf(address subject) external view returns (uint256) {
        uint256 n = ratingCount[subject];
        return n == 0 ? 0 : ratingSum[subject] * 100 / n;
    }
}
