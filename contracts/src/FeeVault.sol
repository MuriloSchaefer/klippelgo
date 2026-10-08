// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice Collects the flat R$ 1,00 per completed ride and forwards it to Instituto Klippel.
contract FeeVault is Governed {
    using SafeERC20 for IERC20;

    address public beneficiary;

    event Swept(address indexed token, address indexed to, uint256 amount);
    event ClaimPaid(address indexed token, address indexed to, uint256 amount);
    event BeneficiarySet(address beneficiary);

    constructor(IPBACGovernor governor_, address beneficiary_) Governed(governor_) {
        beneficiary = beneficiary_;
    }

    /// @notice Anyone can push the collected fees of a token to the beneficiary.
    function sweep(IERC20 token) external {
        uint256 amount = token.balanceOf(address(this));
        token.safeTransfer(beneficiary, amount);
        emit Swept(address(token), beneficiary, amount);
    }

    function payClaim(IERC20 token, address to, uint256 amount) external onlyGovernor {
        token.safeTransfer(to, amount);
        emit ClaimPaid(address(token), to, amount);
    }

    function setBeneficiary(address beneficiary_) external onlyGovernor {
        beneficiary = beneficiary_;
        emit BeneficiarySet(beneficiary_);
    }
}
