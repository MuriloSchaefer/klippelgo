// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Base for every protocol contract. There is no owner: privileged setters are callable only by the
/// governor, and user-facing actions are gated by the governor's policies.
abstract contract Governed {
    IPBACGovernor public immutable governor;

    error NotGovernor();
    error NotPermitted(address subject, bytes4 action);

    constructor(IPBACGovernor governor_) {
        governor = governor_;
    }

    modifier onlyGovernor() {
        if (msg.sender != address(governor)) revert NotGovernor();
        _;
    }

    /// @dev Asks the governor whether msg.sender satisfies the policy for this function.
    modifier permitted() {
        if (!governor.hasPermission(msg.sender, address(this), msg.sig)) {
            revert NotPermitted(msg.sender, msg.sig);
        }
        _;
    }
}
