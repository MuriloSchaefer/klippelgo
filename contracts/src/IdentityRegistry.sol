// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";
import {IdentityStore} from "./IdentityStore.sol";

/// @notice Where people register: a release's code contract over `IdentityStore`, which keeps the roles for as
/// long as the person is on the network. A release that changes how people register replaces this contract,
/// and the board lists the new one as a writer of the store (docs/releases-and-data.md).
contract IdentityRegistry is Governed {
    IdentityStore public immutable identities;

    constructor(IPBACGovernor governor_, IdentityStore identities_) Governed(governor_) {
        identities = identities_;
    }

    /// @notice Claim roles. Claiming a role is free; what a role may do is decided by governor policies
    /// (e.g. accepting rides also needs stake and a CNH credential).
    function register(uint8 roles) external {
        identities.addRoles(msg.sender, roles);
    }
}
