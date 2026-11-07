// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Base of a data contract: state that outlives every release, and the smallest checks on it that must
/// never change. The release's code contracts write it, and only those the governor listed as writers. Reads
/// are open.
///
/// A release replaces code contracts and changes this list; the data stays at its address
/// (docs/releases-and-data.md). Listing a writer is the one action that can change what an account has here, so
/// it is the board's, with its approvals and delay.
abstract contract DataContract is Governed {
    /// @notice The code contracts that may write this contract's state.
    mapping(address => bool) public writers;

    event WriterSet(address indexed writer, bool allowed);

    error NotWriter();

    constructor(IPBACGovernor governor_) Governed(governor_) {}

    modifier onlyWriter() {
        if (!writers[msg.sender]) revert NotWriter();
        _;
    }

    /// @notice Lets a code contract write here, or stops it. One being replaced is left listed until what it
    /// holds has settled, so it can still report on it.
    function setWriter(address writer, bool allowed) external onlyGovernor {
        writers[writer] = allowed;
        emit WriterSet(writer, allowed);
    }
}
