// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @notice Stand-in for a BRL stablecoin on the local testnet (BRLA uses 18 decimals, BRZ uses 4).
/// Anyone can mint: it plays the role of a PIX on-ramp.
contract MockBRL is ERC20 {
    uint8 internal immutable _decimals;

    constructor(string memory name_, string memory symbol_, uint8 decimals_) ERC20(name_, symbol_) {
        _decimals = decimals_;
    }

    function decimals() public view override returns (uint8) {
        return _decimals;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }
}
