// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Script, console2} from "forge-std/Script.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {Execution} from "@openzeppelin/contracts/interfaces/draft-IERC7579.sol";
import {PackedUserOperation} from "account-abstraction/interfaces/PackedUserOperation.sol";
import {IEntryPoint} from "account-abstraction/interfaces/IEntryPoint.sol";
import {LocalAccounts} from "./LocalAccounts.sol";
import {IdentityRegistry} from "../src/IdentityRegistry.sol";
import {IdentityStore} from "../src/IdentityStore.sol";
import {StakeVault} from "../src/StakeVault.sol";
import {AccountFactory} from "../src/account/AccountFactory.sol";
import {MockBRL} from "../src/mocks/MockBRL.sol";

/// @notice Sets up the eight smart accounts the test phones and peers use on the local testnet, so that each can
/// ride or drive: R$ 1.000 in each mock coin, both roles, R$ 100 staked and a CNH credential. Four are for phones
/// played by hand and four for the end-to-end tests, so the two never meet as the same person. Their device keys are
/// P-256 secrets everyone knows (`DevAccount` in apps/android/chain): this script signs their operations with the
/// same secrets, as a phone's chip would, and hands them to the EntryPoint itself, playing the bundler.
///
/// It reads deployments/local.json and skips whatever is already done, so it is safe to run on a chain in use.
///
///   forge script script/SeedPhones.s.sol --rpc-url http://127.0.0.1:8545 --broadcast
contract SeedPhones is Script, LocalAccounts {
    bytes32 constant CREDENTIAL_CNH = keccak256("klippelgo.credential.CNH");
    uint256 constant STAKE = 100e18;
    uint256 constant N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551;

    /// @dev `DevAccount` RIDER, DRIVER, RIDER_2, DRIVER_2, then E2E_RIDER to E2E_DRIVER_2: the same secrets as the
    /// app's.
    uint256[8] devKeys = [uint256(0xab001), 0xab002, 0xab003, 0xab004, 0xab011, 0xab012, 0xab013, 0xab014];

    IdentityRegistry registry;
    IdentityStore identities;
    StakeVault stakes;
    AccountFactory factory;
    IEntryPoint entryPoint;
    address verifier;
    address paymaster;
    MockBRL brla;
    MockBRL brz;

    function run() external {
        _loadAccounts();
        string memory json = vm.readFile("../deployments/local.json");
        registry = IdentityRegistry(vm.parseJsonAddress(json, ".IdentityRegistry"));
        identities = IdentityStore(vm.parseJsonAddress(json, ".IdentityStore"));
        stakes = StakeVault(vm.parseJsonAddress(json, ".StakeVault"));
        factory = AccountFactory(vm.parseJsonAddress(json, ".AccountFactory"));
        entryPoint = IEntryPoint(vm.parseJsonAddress(json, ".EntryPoint"));
        verifier = vm.parseJsonAddress(json, ".DeviceKeyVerifier");
        paymaster = vm.parseJsonAddress(json, ".BrlPaymaster");
        brla = MockBRL(vm.parseJsonAddress(json, ".BRLA"));
        brz = MockBRL(vm.parseJsonAddress(json, ".BRZ"));

        uint8 both = identities.ROLE_RIDER() | identities.ROLE_DRIVER();
        for (uint256 i; i < devKeys.length; ++i) {
            bytes memory signer = _signer(devKeys[i]);
            address account = factory.accountOf(signer);

            // The Pix stand-in: the mock coins mint to anyone, even an account that does not exist yet.
            if (brz.balanceOf(account) == 0 && brla.balanceOf(account) == 0) {
                vm.startBroadcast(deployerKey);
                brla.mint(account, 1_000e18);
                brz.mint(account, 1_000e4);
                vm.stopBroadcast();
            }

            uint256 stake = stakes.stakeOf(account);
            bool unregistered = identities.rolesOf(account) & both != both;
            if (unregistered || stake < STAKE) {
                Execution[] memory calls = new Execution[](3);
                uint256 n;
                if (unregistered) {
                    calls[n++] = Execution(address(registry), 0, abi.encodeCall(registry.register, (both)));
                }
                if (stake < STAKE) {
                    calls[n++] = Execution(
                        address(brla), 0, abi.encodeCall(IERC20.approve, (address(stakes), STAKE - stake))
                    );
                    calls[n++] =
                        Execution(address(stakes), 0, abi.encodeCall(stakes.addStake, (STAKE - stake)));
                }
                assembly {
                    mstore(calls, n)
                }
                _operate(devKeys[i], account, signer, calls);
            }

            if (!identities.hasCredential(account, CREDENTIAL_CNH)) {
                vm.startBroadcast(attesterKey);
                identities.attest(
                    account, CREDENTIAL_CNH, keccak256(abi.encode("local test CNH credential", account)), 0
                );
                vm.stopBroadcast();
            }
            console2.log("Phone account", account);
        }
    }

    function _signer(uint256 key) internal view returns (bytes memory) {
        (uint256 qx, uint256 qy) = vm.publicKeyP256(key);
        return abi.encodePacked(verifier, qx, qy);
    }

    /// @dev One operation of the account, signed with its device key and paid in BRZ through the paymaster.
    function _operate(uint256 key, address account, bytes memory signer, Execution[] memory calls) internal {
        PackedUserOperation memory op;
        op.sender = account;
        op.nonce = entryPoint.getNonce(account, 0);
        if (account.code.length == 0) {
            op.initCode = abi.encodePacked(address(factory), abi.encodeCall(factory.createAccount, (signer)));
        }
        op.callData = abi.encodeWithSignature(
            "execute(bytes32,bytes)", bytes32(uint256(0x01) << 248), abi.encode(calls)
        );
        op.accountGasLimits = bytes32(uint256(2_000_000) << 128 | uint256(1_000_000));
        op.preVerificationGas = 100_000;
        op.gasFees = bytes32(uint256(1 gwei) << 128 | uint256(10 gwei));
        op.paymasterAndData = abi.encodePacked(paymaster, uint128(300_000), uint128(100_000), address(brz));

        bytes32 hash = entryPoint.getUserOpHash(op);
        (bytes32 r, bytes32 s) = vm.signP256(key, sha256(abi.encodePacked(hash)));
        if (uint256(s) > N / 2) s = bytes32(N - uint256(s));
        bytes[] memory signers = new bytes[](1);
        bytes[] memory signatures = new bytes[](1);
        signers[0] = signer;
        signatures[0] = abi.encodePacked(r, s);
        op.signature = abi.encode(signers, signatures);

        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        vm.startBroadcast(deployerKey);
        entryPoint.handleOps(ops, payable(deployer));
        vm.stopBroadcast();
    }
}
