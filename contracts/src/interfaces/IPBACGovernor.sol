// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

interface IPBACGovernor {
    function hasPermission(address subject, address target, bytes4 action) external view returns (bool);
}
