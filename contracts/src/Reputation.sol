// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Ratings for completed rides. Only the two parties of a ride can rate each other, once each.
contract Reputation is Governed {
    struct RideParties {
        address rider;
        address driver;
        bool riderRated;
        bool driverRated;
    }

    address public escrow;

    mapping(uint256 rideId => RideParties) public rides;
    mapping(address => uint256) public completedRides;
    mapping(address => uint256) public ratingSum;
    mapping(address => uint256) public ratingCount;

    event RideRecorded(uint256 indexed rideId, address indexed rider, address indexed driver);
    event Rated(uint256 indexed rideId, address indexed from, address indexed to, uint8 stars);
    event EscrowSet(address escrow);

    error NotEscrow();
    error NotParty();
    error AlreadyRated();
    error InvalidStars();

    constructor(IPBACGovernor governor_) Governed(governor_) {}

    function recordCompletion(uint256 rideId, address rider, address driver) external {
        if (msg.sender != escrow) revert NotEscrow();
        rides[rideId] = RideParties(rider, driver, false, false);
        completedRides[rider]++;
        completedRides[driver]++;
        emit RideRecorded(rideId, rider, driver);
    }

    function rate(uint256 rideId, uint8 stars) external {
        if (stars < 1 || stars > 5) revert InvalidStars();
        RideParties storage r = rides[rideId];
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
        emit Rated(rideId, msg.sender, to, stars);
    }

    /// @notice Average stars times 100 (450 = 4.50). Zero when the address has no ratings yet.
    function scoreOf(address subject) external view returns (uint256) {
        uint256 n = ratingCount[subject];
        return n == 0 ? 0 : ratingSum[subject] * 100 / n;
    }

    function setEscrow(address escrow_) external onlyGovernor {
        escrow = escrow_;
        emit EscrowSet(escrow_);
    }
}
