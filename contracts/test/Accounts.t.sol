// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Test} from "forge-std/Test.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/IERC4337.sol";
import {PackedUserOperation} from "account-abstraction/interfaces/PackedUserOperation.sol";
import {IERC1271} from "@openzeppelin/contracts/interfaces/IERC1271.sol";
import {Execution} from "@openzeppelin/contracts/interfaces/draft-IERC7579.sol";
import {EntryPoint} from "account-abstraction/core/EntryPoint.sol";
import {IEntryPoint as IEntryPointErrors} from "account-abstraction/interfaces/IEntryPoint.sol";
import {ProtocolDeployer} from "../script/ProtocolDeployer.sol";
import {KlippelGoAccount} from "../src/account/KlippelGoAccount.sol";
import {AccountFactory} from "../src/account/AccountFactory.sol";
import {DeviceKeyVerifier} from "../src/account/DeviceKeyVerifier.sol";
import {BrlPaymaster} from "../src/account/BrlPaymaster.sol";
import {Governed} from "../src/Governed.sol";
import {MockBRL} from "../src/mocks/MockBRL.sol";

/// @notice People's accounts end to end through a real EntryPoint v0.9: a phone's P-256 key signs, the first
/// operation creates the account, and gas is paid in reais through the paymaster.
contract AccountsTest is Test, ProtocolDeployer {
    uint256 constant N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551;

    EntryPoint entryPoint;
    MockBRL brla;
    MockBRL brz;
    Protocol p;
    DeviceKeyVerifier verifier;
    AccountFactory factory;
    BrlPaymaster paymaster;

    uint256 constant PHONE_KEY = 0xA11CE;
    uint256 constant NEW_PHONE_KEY = 0xB0B;
    address bundler = makeAddr("bundler");

    function setUp() public {
        entryPoint = new EntryPoint();
        brla = new MockBRL("BRLA", "BRLA", 18);
        brz = new MockBRL("BRZ", "BRZ", 4);
        TokenInit[] memory tokens = new TokenInit[](2);
        tokens[0] = TokenInit(IERC20(address(brla)), 18);
        tokens[1] = TokenInit(IERC20(address(brz)), 4);
        p = _deployProtocol(
            Config({
                stakeToken: IERC20(address(brla)),
                stakeDecimals: 18,
                tokens: tokens,
                guardian: address(this),
                feeBeneficiary: makeAddr("instituto"),
                attesters: new address[](0),
                unbondingPeriod: 1 days,
                completionTimeout: 30 minutes,
                commitPeriod: 10 minutes,
                revealPeriod: 10 minutes,
                driverMinStakeBrl: 100,
                validatorMinStakeBrl: 500,
                driverMinScore: 450,
                mapRebuildQuorum: 3,
                mapGracePeriod: 30 days,
                taxBps: 0,
                taxCollector: address(0),
                nativeCoinCentavos: 250
            })
        );
        verifier = new DeviceKeyVerifier();
        paymaster = new BrlPaymaster(p.governor, IEntryPoint(address(entryPoint)), p.escrow, p.fares);
        paymaster.deposit{value: 10 ether}();
        IERC20[] memory coins = new IERC20[](2);
        coins[0] = IERC20(address(brla));
        coins[1] = IERC20(address(brz));
        factory = new AccountFactory(
            new KlippelGoAccount(IEntryPoint(address(entryPoint))), address(paymaster), coins
        );
    }

    // ---------------------------------------------------------------- what a phone does

    function _signer(uint256 key) internal view returns (bytes memory) {
        (uint256 qx, uint256 qy) = vm.publicKeyP256(key);
        return abi.encodePacked(address(verifier), qx, qy);
    }

    /// @dev What the phone's chip does: P-256 over sha256 of the hash, with s in the lower half.
    function _deviceSign(uint256 key, bytes32 hash) internal pure returns (bytes memory) {
        (bytes32 r, bytes32 s) = vm.signP256(key, sha256(abi.encodePacked(hash)));
        if (uint256(s) > N / 2) s = bytes32(N - uint256(s));
        return abi.encodePacked(r, s);
    }

    function _accountSignature(uint256 key, bytes32 hash) internal view returns (bytes memory) {
        bytes[] memory signers = new bytes[](1);
        bytes[] memory signatures = new bytes[](1);
        signers[0] = _signer(key);
        signatures[0] = _deviceSign(key, hash);
        return abi.encode(signers, signatures);
    }

    function _execute(address target, bytes memory data) internal pure returns (bytes memory) {
        Execution[] memory batch = new Execution[](1);
        batch[0] = Execution(target, 0, data);
        return
            abi.encodeWithSignature(
                "execute(bytes32,bytes)", bytes32(uint256(0x01) << 248), abi.encode(batch)
            );
    }

    function _op(address sender, bytes memory initCode, bytes memory callData, IERC20 coin)
        internal
        view
        returns (PackedUserOperation memory op)
    {
        op.sender = sender;
        op.nonce = entryPoint.getNonce(sender, 0);
        op.initCode = initCode;
        op.callData = callData;
        op.accountGasLimits = bytes32(uint256(2_000_000) << 128 | uint256(500_000));
        op.preVerificationGas = 60_000;
        op.gasFees = bytes32(uint256(1 gwei) << 128 | uint256(1 gwei));
        op.paymasterAndData =
            abi.encodePacked(address(paymaster), uint128(300_000), uint128(100_000), address(coin));
    }

    function _send(PackedUserOperation memory op, uint256 key) internal {
        op.signature = _accountSignature(key, entryPoint.getUserOpHash(op));
        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        vm.prank(bundler, bundler);
        entryPoint.handleOps(ops, payable(bundler));
    }

    function _newAccount(uint256 key) internal returns (address account) {
        bytes memory signer = _signer(key);
        account = factory.accountOf(signer);
        brz.mint(account, 50_0000); // R$ 50: the Pix that arrives before the account exists
        bytes memory initCode =
            abi.encodePacked(address(factory), abi.encodeCall(factory.createAccount, (signer)));
        _send(
            _op(
                account,
                initCode,
                _execute(address(p.registry), abi.encodeCall(p.registry.register, (1))),
                brz
            ),
            key
        );
    }

    // ---------------------------------------------------------------- tests

    function test_TheFirstOperationCreatesTheAccountAndPaysInReais() public {
        bytes memory signer = _signer(PHONE_KEY);
        address predicted = factory.accountOf(signer);
        assertEq(predicted.code.length, 0);
        uint256 depositBefore = entryPoint.balanceOf(address(paymaster));

        address account = _newAccount(PHONE_KEY);

        assertEq(account, predicted);
        assertGt(account.code.length, 0);
        assertTrue(KlippelGoAccount(payable(account)).isSigner(signer));
        // It registered as a rider, from its own address.
        assertEq(p.identities.identity(account).roles, 1);
        // The paymaster paid the network in the native coin and took the same in BRZ, at R$ 2,50 a coin.
        uint256 spent = depositBefore - entryPoint.balanceOf(address(paymaster));
        uint256 charged = 50_0000 - brz.balanceOf(account);
        assertGt(spent, 0);
        assertEq(brz.balanceOf(address(paymaster)), charged);
        uint256 fair = spent * 250 * 1e4 / 100 / 1e18;
        assertGe(charged, fair);
        assertLe(charged, fair + fair / 2 + 2); // the post-op estimate is a little above the real cost
    }

    function test_AnOperationSignedByAnotherKeyIsRefused() public {
        address account = _newAccount(PHONE_KEY);
        PackedUserOperation memory op =
            _op(account, "", _execute(address(p.registry), abi.encodeCall(p.registry.register, (2))), brz);
        // A key that is not the account's, with a signer entry naming it.
        op.signature = _accountSignature(NEW_PHONE_KEY, entryPoint.getUserOpHash(op));
        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        vm.prank(bundler, bundler);
        vm.expectRevert(
            abi.encodeWithSelector(IEntryPointErrors.FailedOp.selector, 0, "AA24 signature error")
        );
        entryPoint.handleOps(ops, payable(bundler));
        // And the right key, but over another operation's hash.
        op.signature = _accountSignature(PHONE_KEY, keccak256("another operation"));
        ops[0] = op;
        vm.prank(bundler, bundler);
        vm.expectRevert(
            abi.encodeWithSelector(IEntryPointErrors.FailedOp.selector, 0, "AA24 signature error")
        );
        entryPoint.handleOps(ops, payable(bundler));
    }

    function test_ANewPhoneIsAddedByTheOldOneAndTheOldOneRemoved() public {
        address account = _newAccount(PHONE_KEY);
        bytes[] memory added = new bytes[](1);
        added[0] = _signer(NEW_PHONE_KEY);
        _send(
            _op(account, "", _execute(account, abi.encodeCall(KlippelGoAccount.addSigners, (added))), brz),
            PHONE_KEY
        );
        assertTrue(KlippelGoAccount(payable(account)).isSigner(added[0]));

        // The new phone acts alone, and takes the old one off.
        bytes[] memory removed = new bytes[](1);
        removed[0] = _signer(PHONE_KEY);
        _send(
            _op(
                account, "", _execute(account, abi.encodeCall(KlippelGoAccount.removeSigners, (removed))), brz
            ),
            NEW_PHONE_KEY
        );
        assertFalse(KlippelGoAccount(payable(account)).isSigner(removed[0]));

        // The old one is refused from then on.
        PackedUserOperation memory op =
            _op(account, "", _execute(address(p.registry), abi.encodeCall(p.registry.register, (2))), brz);
        op.signature = _accountSignature(PHONE_KEY, entryPoint.getUserOpHash(op));
        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        vm.prank(bundler, bundler);
        vm.expectRevert(
            abi.encodeWithSelector(IEntryPointErrors.FailedOp.selector, 0, "AA24 signature error")
        );
        entryPoint.handleOps(ops, payable(bundler));

        // Nobody else changes the keys: not the paymaster, not a stranger.
        vm.expectRevert();
        KlippelGoAccount(payable(account)).addSigners(added);
    }

    function test_TheAccountSaysWhatItsKeySigned() public {
        address account = _newAccount(PHONE_KEY);
        bytes32 certificate = keccak256("KlippelGo session v1: a gossip key for a day");
        assertEq(
            KlippelGoAccount(payable(account))
                .isValidSignature(certificate, _accountSignature(PHONE_KEY, certificate)),
            IERC1271.isValidSignature.selector
        );
        assertEq(
            KlippelGoAccount(payable(account))
                .isValidSignature(certificate, _accountSignature(NEW_PHONE_KEY, certificate)),
            bytes4(0xffffffff)
        );
    }

    function test_ThePaymasterTakesOnlyCoinsOnTheAllowListAndNeedsAPrice() public {
        // A coin the escrow does not take: the operation is refused, nothing is charged.
        MockBRL other = new MockBRL("Other", "OTH", 4);
        bytes memory signer = _signer(PHONE_KEY);
        address account = factory.accountOf(signer);
        other.mint(account, 50_0000);
        bytes memory initCode =
            abi.encodePacked(address(factory), abi.encodeCall(factory.createAccount, (signer)));
        PackedUserOperation memory op = _op(account, initCode, "", IERC20(address(other)));
        op.signature = _accountSignature(PHONE_KEY, entryPoint.getUserOpHash(op));
        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        vm.prank(bundler, bundler);
        vm.expectRevert(
            abi.encodeWithSelector(IEntryPointErrors.FailedOp.selector, 0, "AA34 signature error")
        );
        entryPoint.handleOps(ops, payable(bundler));

        // No price for the native coin: gas is not given away either.
        address[] memory targets = new address[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = address(p.fares);
        calls[0] = abi.encodeCall(p.fares.setNativeCoinPrice, (0));
        p.governor.execute(targets, calls);
        brz.mint(account, 50_0000);
        op = _op(account, initCode, "", brz);
        op.signature = _accountSignature(PHONE_KEY, entryPoint.getUserOpHash(op));
        ops[0] = op;
        vm.prank(bundler, bundler);
        vm.expectRevert(
            abi.encodeWithSelector(IEntryPointErrors.FailedOp.selector, 0, "AA34 signature error")
        );
        entryPoint.handleOps(ops, payable(bundler));
    }

    function test_OnlyTheGuardianTakesWhatThePaymasterHolds() public {
        _newAccount(PHONE_KEY);
        uint256 collected = brz.balanceOf(address(paymaster));
        assertGt(collected, 0);
        vm.prank(makeAddr("stranger"));
        vm.expectRevert(Governed.NotGovernor.selector);
        paymaster.withdrawTokens(brz, makeAddr("stranger"), collected);
        vm.prank(makeAddr("stranger"));
        vm.expectRevert(Governed.NotGovernor.selector);
        paymaster.withdraw(payable(makeAddr("stranger")), 1 ether);

        address[] memory targets = new address[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = address(paymaster);
        calls[0] = abi.encodeCall(paymaster.withdrawTokens, (brz, makeAddr("instituto"), collected));
        p.governor.execute(targets, calls);
        assertEq(brz.balanceOf(makeAddr("instituto")), collected);
    }

    function test_TheImplementationCannotBeTakenOver() public {
        KlippelGoAccount impl = factory.implementation();
        bytes[] memory signers = new bytes[](1);
        signers[0] = _signer(PHONE_KEY);
        vm.expectRevert();
        impl.initialize(signers, address(this), new IERC20[](0));
        // And an account cannot be initialized twice.
        address account = _newAccount(PHONE_KEY);
        vm.expectRevert();
        KlippelGoAccount(payable(account)).initialize(signers, address(this), new IERC20[](0));
    }

    function test_TheLastKeyCannotBeRemoved() public {
        address account = _newAccount(PHONE_KEY);
        bytes[] memory removed = new bytes[](1);
        removed[0] = _signer(PHONE_KEY);
        PackedUserOperation memory op = _op(
            account, "", _execute(account, abi.encodeCall(KlippelGoAccount.removeSigners, (removed))), brz
        );
        op.signature = _accountSignature(PHONE_KEY, entryPoint.getUserOpHash(op));
        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        // The operation runs, but its call reverts: the key is still there.
        vm.prank(bundler, bundler);
        entryPoint.handleOps(ops, payable(bundler));
        assertTrue(KlippelGoAccount(payable(account)).isSigner(removed[0]));
    }

    function test_AHighSSignatureIsRefused() public {
        address account = _newAccount(PHONE_KEY);
        PackedUserOperation memory op =
            _op(account, "", _execute(address(p.registry), abi.encodeCall(p.registry.register, (2))), brz);
        bytes32 hash = entryPoint.getUserOpHash(op);
        (bytes32 r, bytes32 s) = vm.signP256(PHONE_KEY, sha256(abi.encodePacked(hash)));
        if (uint256(s) <= N / 2) s = bytes32(N - uint256(s)); // the other, equally valid, form
        bytes[] memory signers = new bytes[](1);
        bytes[] memory signatures = new bytes[](1);
        signers[0] = _signer(PHONE_KEY);
        signatures[0] = abi.encodePacked(r, s);
        op.signature = abi.encode(signers, signatures);
        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        vm.prank(bundler, bundler);
        vm.expectRevert(
            abi.encodeWithSelector(IEntryPointErrors.FailedOp.selector, 0, "AA24 signature error")
        );
        entryPoint.handleOps(ops, payable(bundler));
    }

    function test_NobodyButTheEntryPointOrTheAccountActsForIt() public {
        address account = _newAccount(PHONE_KEY);
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        bytes[] memory keys = new bytes[](1);
        keys[0] = _signer(NEW_PHONE_KEY);
        vm.startPrank(makeAddr("stranger"));
        vm.expectRevert();
        a.addSigners(keys);
        vm.expectRevert();
        a.removeSigners(keys);
        vm.expectRevert();
        a.approvePaymaster(brz, makeAddr("stranger"), type(uint256).max);
        (bool ok,) =
            account.call(_execute(address(brz), abi.encodeCall(IERC20.transfer, (makeAddr("stranger"), 1))));
        assertFalse(ok, "a stranger ran the account's execute");
        vm.stopPrank();
    }

    function test_CreatingTheSameAccountTwiceGivesTheSameOne() public {
        address account = _newAccount(PHONE_KEY);
        assertEq(address(factory.createAccount(_signer(PHONE_KEY))), account);
    }
}
