// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Ratings for completed rides. Only the two parties of a ride can rate each other, once each.
///
/// This is a ledger: it keeps what people earned, and outlives the escrow that reported it. Several escrows
/// may report at once, so a new escrow can open rides while the one it replaces finishes its own. A ride is
/// known by its escrow and its id there, since each escrow counts its rides from one.
contract Reputation is Governed {
    struct RideParties {
        address rider;
        address driver;
        bool riderRated;
        bool driverRated;
    }

    /// @notice The escrows whose completed rides count.
    mapping(address escrow => bool) public isEscrow;

    mapping(address escrow => mapping(uint256 rideId => RideParties)) public rides;
    mapping(address => uint256) public completedRides;
    mapping(address => uint256) public ratingSum;
    mapping(address => uint256) public ratingCount;

    event RideRecorded(address indexed escrow, uint256 indexed rideId, address indexed rider, address driver);
    event Rated(
        address indexed escrow, uint256 indexed rideId, address from, address indexed to, uint8 stars
    );
    event EscrowSet(address indexed escrow, bool allowed);

    error NotEscrow();
    error NotParty();
    error AlreadyRated();
    error InvalidStars();

    constructor(IPBACGovernor governor_) Governed(governor_) {}

    function recordCompletion(uint256 rideId, address rider, address driver) external {
        if (!isEscrow[msg.sender]) revert NotEscrow();
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

    /// @notice Lets an escrow report completed rides, or stops it. An escrow being replaced is left allowed
    /// until its last ride settled, so the rides it holds still count.
    function setEscrow(address escrow_, bool allowed) external onlyGovernor {
        isEscrow[escrow_] = allowed;
        emit EscrowSet(escrow_, allowed);
    }
}
