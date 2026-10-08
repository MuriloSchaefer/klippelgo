// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Script} from "forge-std/Script.sol";

/// @notice Fixed roles for anvil's default test mnemonic. These keys are public: never use them off the local
/// testnet.
abstract contract LocalAccounts is Script {
    string internal constant ANVIL_MNEMONIC = "test test test test test test test test test test test junk";

    uint256 internal deployerKey; // #0
    uint256 internal institutoKlippelKey; // #1, receives the R$ 1,00 per ride
    uint256 internal attesterKey; // #2, mock KYC/CNH attester
    uint256 internal riderKey; // #3
    uint256 internal driverKey; // #4
    uint256[3] internal validatorKeys; // #5-#7

    address internal deployer;
    address internal institutoKlippel;
    address internal attester;
    address internal rider;
    address internal driver;
    address[3] internal validators;

    function _loadAccounts() internal {
        string memory mnemonic = vm.envOr("MNEMONIC", ANVIL_MNEMONIC);
        deployerKey = vm.deriveKey(mnemonic, 0);
        institutoKlippelKey = vm.deriveKey(mnemonic, 1);
        attesterKey = vm.deriveKey(mnemonic, 2);
        riderKey = vm.deriveKey(mnemonic, 3);
        driverKey = vm.deriveKey(mnemonic, 4);
        for (uint32 i; i < 3; ++i) {
            validatorKeys[i] = vm.deriveKey(mnemonic, 5 + i);
            validators[i] = vm.addr(validatorKeys[i]);
        }
        deployer = vm.addr(deployerKey);
        institutoKlippel = vm.addr(institutoKlippelKey);
        attester = vm.addr(attesterKey);
        rider = vm.addr(riderKey);
        driver = vm.addr(driverKey);
    }
}
