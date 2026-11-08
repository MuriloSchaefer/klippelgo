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

    // ---------------------------------------------------------------- keys: changed by a majority of them

    uint256 constant THIRD_PHONE_KEY = 0xC0FFEE;
    uint256 constant THIEF_KEY = 0xBAD;

    function _one(uint256 key) internal view returns (bytes[] memory s) {
        s = new bytes[](1);
        s[0] = _signer(key);
    }

    function _none() internal pure returns (bytes[] memory) {
        return new bytes[](0);
    }

    /// @dev What a phone does to propose a change: its own approval, signed over the change's approval hash.
    /// @dev Keeps the guardian.
    function _propose(address account, uint256 key, bytes[] memory add, bytes[] memory remove)
        internal
        returns (bytes32)
    {
        return _propose(account, key, add, remove, false, address(0));
    }

    function _propose(
        address account,
        uint256 key,
        bytes[] memory add,
        bytes[] memory remove,
        bool setGuardian,
        address g
    ) internal returns (bytes32 id) {
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        id = keccak256(abi.encode(a.keysEpoch(), add, remove, setGuardian, g));
        bytes memory approval = _deviceSign(key, a.approvalHash(id));
        // Inside an operation the account signed: the account calls itself.
        vm.prank(account);
        assertEq(a.proposeKeyChange(add, remove, setGuardian, g, _signer(key), approval), id);
    }

    function _approve(address account, uint256 key, bytes32 id) internal {
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        bytes memory approval = _deviceSign(key, a.approvalHash(id));
        vm.prank(account);
        a.approveKeyChange(id, _signer(key), approval);
    }

    /// @dev An account with three phones, as the app recommends.
    function _threePhones() internal returns (address account) {
        account = _newAccount(PHONE_KEY);
        _propose(account, PHONE_KEY, _one(NEW_PHONE_KEY), _none());
        bytes32 id = _propose(account, PHONE_KEY, _one(THIRD_PHONE_KEY), _none());
        _approve(account, NEW_PHONE_KEY, id);
        assertEq(KlippelGoAccount(payable(account)).getSignerCount(), 3);
    }

    function test_ANewPhoneIsAddedByTheOldOneAndTheOldOneRemoved() public {
        address account = _newAccount(PHONE_KEY);
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        bytes[] memory added = _one(NEW_PHONE_KEY);
        // Through the EntryPoint, as the phone does: one key is a majority of one, so it is done at once.
        bytes32 id = keccak256(abi.encode(a.keysEpoch(), added, _none(), false, address(0)));
        bytes memory approval = _deviceSign(PHONE_KEY, a.approvalHash(id));
        PackedUserOperation memory change = _op(
            account,
            "",
            _execute(
                account,
                abi.encodeCall(
                    KlippelGoAccount.proposeKeyChange,
                    (added, _none(), false, address(0), _signer(PHONE_KEY), approval)
                )
            ),
            brz
        );
        // Checking the approval's P-256 signature in Solidity, as forge has no precompile for it, takes more
        // than the half million of gas `_op` gives a call.
        change.accountGasLimits = bytes32(uint256(2_000_000) << 128 | uint256(2_000_000));
        _send(change, PHONE_KEY);
        assertTrue(a.isSigner(added[0]));
        assertEq(a.keysEpoch(), 1);

        // Two keys: taking the old one off needs both. The new one alone only proposes.
        bytes[] memory removed = _one(PHONE_KEY);
        id = _propose(account, NEW_PHONE_KEY, _none(), removed);
        assertTrue(a.isSigner(removed[0]));
        (,,,, uint32 approvals, bool live) = a.keyChange(id);
        assertEq(approvals, 1);
        assertTrue(live);
        _approve(account, PHONE_KEY, id);
        assertFalse(a.isSigner(removed[0]));

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
    }

    function test_OneSeizedPhoneOfThreeCannotLockTheOthersOutAndTwoTakeItOff() public {
        address account = _threePhones();
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        // The thief holds the third phone: alone it proposes taking the other two off and adding a key of its own.
        bytes[] memory others = new bytes[](2);
        others[0] = _signer(PHONE_KEY);
        others[1] = _signer(NEW_PHONE_KEY);
        bytes32 grab = _propose(account, THIRD_PHONE_KEY, _one(THIEF_KEY), others);
        assertTrue(a.isSigner(others[0]) && a.isSigner(others[1]));
        assertFalse(a.isSigner(_signer(THIEF_KEY)));
        // Its approval counts once, whatever it signs.
        bytes memory again = _deviceSign(THIRD_PHONE_KEY, a.approvalHash(grab));
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.AlreadyApproved.selector);
        a.approveKeyChange(grab, _signer(THIRD_PHONE_KEY), again);
        // Nor can it approve in another key's name: the signature must be that key's.
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.BadApproval.selector);
        a.approveKeyChange(grab, _signer(PHONE_KEY), again);
        // And a key the account does not have approves nothing.
        bytes memory stranger = _deviceSign(THIEF_KEY, a.approvalHash(grab));
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.NotASigner.selector);
        a.approveKeyChange(grab, _signer(THIEF_KEY), stranger);

        // The owner's two phones take the third off.
        bytes32 id = _propose(account, PHONE_KEY, _none(), _one(THIRD_PHONE_KEY));
        _approve(account, NEW_PHONE_KEY, id);
        assertFalse(a.isSigner(_signer(THIRD_PHONE_KEY)));
        assertEq(a.getSignerCount(), 2);
        // The thief's proposal died with the keys it was made under.
        (,,,,, bool live) = a.keyChange(grab);
        assertFalse(live);
        bytes memory late = _deviceSign(PHONE_KEY, a.approvalHash(grab));
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.UnknownKeyChange.selector);
        a.approveKeyChange(grab, _signer(PHONE_KEY), late);
    }

    function test_AnApprovalIsForOneChangeOnOneAccount() public {
        address account = _threePhones();
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        bytes32 id = _propose(account, PHONE_KEY, _none(), _one(THIRD_PHONE_KEY));
        bytes32 other = _propose(account, NEW_PHONE_KEY, _none(), _one(PHONE_KEY));
        // The second phone's approval of its own change is not one of the first's.
        bytes memory signed = _deviceSign(NEW_PHONE_KEY, a.approvalHash(other));
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.BadApproval.selector);
        a.approveKeyChange(id, _signer(NEW_PHONE_KEY), signed);
        // Each key's latest proposal is what the phones list.
        assertEq(a.proposedBy(_signer(PHONE_KEY)), id);
        assertEq(a.proposedBy(_signer(NEW_PHONE_KEY)), other);
        assertTrue(a.approvedBy(id, _signer(PHONE_KEY)));
        assertFalse(a.approvedBy(id, _signer(NEW_PHONE_KEY)));
    }

    function test_TheLastKeyCannotBeRemoved() public {
        address account = _newAccount(PHONE_KEY);
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        bytes32 id = keccak256(abi.encode(a.keysEpoch(), _none(), _one(PHONE_KEY), false, address(0)));
        bytes memory approval = _deviceSign(PHONE_KEY, a.approvalHash(id));
        vm.prank(account);
        vm.expectRevert();
        a.proposeKeyChange(_none(), _one(PHONE_KEY), false, address(0), _signer(PHONE_KEY), approval);
        assertTrue(a.isSigner(_signer(PHONE_KEY)));
    }

    function test_TheAccountIsNeverItsOwnKeyNorItsOwnGuardian() public {
        address account = _newAccount(PHONE_KEY);
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        bytes[] memory itself = new bytes[](1);
        itself[0] = abi.encodePacked(account);
        bytes32 id = keccak256(abi.encode(a.keysEpoch(), itself, _none(), false, address(0)));
        bytes memory approval = _deviceSign(PHONE_KEY, a.approvalHash(id));
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.SelfAsKey.selector);
        a.proposeKeyChange(itself, _none(), false, address(0), _signer(PHONE_KEY), approval);
        id = keccak256(abi.encode(a.keysEpoch(), _none(), _none(), true, account));
        approval = _deviceSign(PHONE_KEY, a.approvalHash(id));
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.SelfAsKey.selector);
        a.proposeKeyChange(_none(), _none(), true, account, _signer(PHONE_KEY), approval);
    }

    // ---------------------------------------------------------------- recovery by a guardian

    function test_TheGuardianReplacesTheKeysAfterTheDelay() public {
        address account = _threePhones();
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        address friend = makeAddr("friend");
        // Naming a guardian is a change of keys: two of the three phones.
        bytes32 id = _propose(account, PHONE_KEY, _none(), _none(), true, friend);
        assertEq(a.guardian(), address(0));
        _approve(account, NEW_PHONE_KEY, id);
        assertEq(a.guardian(), friend);

        // Every phone is lost. The friend starts a recovery to the person's new phone.
        bytes memory fresh = _signer(THIEF_KEY + 1);
        vm.prank(makeAddr("stranger"));
        vm.expectRevert(KlippelGoAccount.NotGuardian.selector);
        a.startRecovery(fresh);
        vm.prank(friend);
        a.startRecovery(fresh);
        (bytes memory pending, uint64 readyAt, bool live) = a.recovery();
        assertEq(pending, fresh);
        assertEq(readyAt, block.timestamp + a.RECOVERY_DELAY());
        assertTrue(live);

        vm.expectRevert(KlippelGoAccount.RecoveryNotReady.selector);
        a.finishRecovery();
        vm.warp(readyAt);
        // Anyone finishes it: the new phone has no key on the account yet to do it with.
        vm.prank(makeAddr("anyone"));
        a.finishRecovery();
        assertEq(a.getSignerCount(), 1);
        assertTrue(a.isSigner(fresh));
        assertFalse(a.isSigner(_signer(PHONE_KEY)));
        assertEq(a.guardian(), friend);
        (,, live) = a.recovery();
        assertFalse(live);
        vm.expectRevert(KlippelGoAccount.NoRecovery.selector);
        a.finishRecovery();
    }

    function test_TheKeysCancelARecoveryTheyDidNotWant() public {
        address account = _threePhones();
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        address friend = makeAddr("friend");
        _approve(account, NEW_PHONE_KEY, _propose(account, PHONE_KEY, _none(), _none(), true, friend));

        // The guardian turns, or its own account was taken: it starts a recovery to a key of its own.
        vm.prank(friend);
        a.startRecovery(_signer(THIEF_KEY));
        // One phone alone cannot cancel it: a thief holding one of three cannot hold a real recovery off.
        bytes32 cancel = _propose(account, THIRD_PHONE_KEY, _none(), _none());
        (,, bool live) = a.recovery();
        assertTrue(live);
        // A second phone's approval of the same empty change cancels it. The guardian stays, as the change keeps it.
        _approve(account, PHONE_KEY, cancel);
        (,, live) = a.recovery();
        assertFalse(live);
        vm.warp(block.timestamp + a.RECOVERY_DELAY());
        vm.expectRevert(KlippelGoAccount.NoRecovery.selector);
        a.finishRecovery();
        assertFalse(a.isSigner(_signer(THIEF_KEY)));
        assertEq(a.getSignerCount(), 3);
        assertEq(a.guardian(), friend);
    }

    function test_AChangeThatKeepsTheGuardianNamesNone() public {
        address account = _newAccount(PHONE_KEY);
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        address friend = makeAddr("friend");
        _propose(account, PHONE_KEY, _none(), _none(), true, friend);
        // Keeping it is written one way only: no guardian named.
        bytes32 id = keccak256(abi.encode(a.keysEpoch(), _one(NEW_PHONE_KEY), _none(), false, friend));
        bytes memory approval = _deviceSign(PHONE_KEY, a.approvalHash(id));
        vm.prank(account);
        vm.expectRevert(KlippelGoAccount.UnknownKeyChange.selector);
        a.proposeKeyChange(_one(NEW_PHONE_KEY), _none(), false, friend, _signer(PHONE_KEY), approval);
        _propose(account, PHONE_KEY, _one(NEW_PHONE_KEY), _none());
        assertEq(a.guardian(), friend);
        // Setting it to none takes it away.
        _approve(account, NEW_PHONE_KEY, _propose(account, PHONE_KEY, _none(), _none(), true, address(0)));
        assertEq(a.guardian(), address(0));
    }

    function test_AnAccountWithOnePhoneCancelsARecoveryAlone() public {
        address account = _newAccount(PHONE_KEY);
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        address friend = makeAddr("friend");
        _propose(account, PHONE_KEY, _none(), _none(), true, friend);
        assertEq(a.guardian(), friend);
        vm.prank(friend);
        a.startRecovery(_signer(THIEF_KEY));
        _propose(account, PHONE_KEY, _none(), _none());
        (,, bool live) = a.recovery();
        assertFalse(live);
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
        bytes[] memory keys = _one(NEW_PHONE_KEY);
        bytes32 id = keccak256(abi.encode(a.keysEpoch(), keys, _none(), false, address(0)));
        vm.startPrank(makeAddr("stranger"));
        // A change with no key's approval, or a stranger key's, goes nowhere.
        bytes memory forged = _deviceSign(NEW_PHONE_KEY, a.approvalHash(id));
        vm.expectRevert(KlippelGoAccount.NotASigner.selector);
        a.proposeKeyChange(keys, _none(), false, address(0), _signer(NEW_PHONE_KEY), forged);
        vm.expectRevert(KlippelGoAccount.BadApproval.selector);
        a.proposeKeyChange(keys, _none(), false, address(0), _signer(PHONE_KEY), forged);
        vm.expectRevert();
        a.approvePaymaster(brz, makeAddr("stranger"), type(uint256).max);
        (bool ok,) =
            account.call(_execute(address(brz), abi.encodeCall(IERC20.transfer, (makeAddr("stranger"), 1))));
        assertFalse(ok, "a stranger ran the account's execute");
        vm.stopPrank();
        assertFalse(a.isSigner(keys[0]));
    }

    function test_AnApprovalIsForThisAccountOnly() public {
        address first = _threePhones();
        // A second account with the same three keys, as a person with two accounts on the same phones has.
        address second = _newAccount(NEW_PHONE_KEY);
        _propose(second, NEW_PHONE_KEY, _one(PHONE_KEY), _none());
        bytes32 id2 = _propose(second, NEW_PHONE_KEY, _one(THIRD_PHONE_KEY), _none());
        _approve(second, PHONE_KEY, id2);
        // The same change at the same epoch has the same id on both; an approval given on one does not count on the other.
        bytes32 id = _propose(first, PHONE_KEY, _none(), _one(THIRD_PHONE_KEY));
        KlippelGoAccount b = KlippelGoAccount(payable(second));
        assertEq(b.keysEpoch(), KlippelGoAccount(payable(first)).keysEpoch());
        bytes memory givenOnFirst = _deviceSign(PHONE_KEY, KlippelGoAccount(payable(first)).approvalHash(id));
        vm.expectRevert(KlippelGoAccount.BadApproval.selector);
        b.proposeKeyChange(
            _none(), _one(THIRD_PHONE_KEY), false, address(0), _signer(PHONE_KEY), givenOnFirst
        );
    }

    function test_ATakenKeyThatStopsThePaymasterIsStillTakenOff() public {
        address account = _threePhones();
        KlippelGoAccount a = KlippelGoAccount(payable(account));
        // The thief's key revokes the paymaster's allowance in every coin: the account can pay for nothing more.
        vm.startPrank(account);
        a.approvePaymaster(brz, address(paymaster), 0);
        a.approvePaymaster(IERC20(address(brla)), address(paymaster), 0);
        vm.stopPrank();
        // The owner's two phones sign their approvals; a friend sends them and pays the gas.
        bytes[] memory remove = _one(THIRD_PHONE_KEY);
        bytes32 id = keccak256(abi.encode(a.keysEpoch(), _none(), remove, false, address(0)));
        bytes memory first = _deviceSign(PHONE_KEY, a.approvalHash(id));
        bytes memory second = _deviceSign(NEW_PHONE_KEY, a.approvalHash(id));
        vm.startPrank(makeAddr("friend"));
        a.proposeKeyChange(_none(), remove, false, address(0), _signer(PHONE_KEY), first);
        a.approveKeyChange(id, _signer(NEW_PHONE_KEY), second);
        vm.stopPrank();
        assertFalse(a.isSigner(_signer(THIRD_PHONE_KEY)));
        assertEq(a.getSignerCount(), 2);
    }

    /// @dev The id the account files a change under, at epoch 0: the same bytes as `apps/android/chain`
    /// `KeyChangeVectorTest`.
    function test_TheKeyChangeIdVector() public {
        KlippelGoAccount a = KlippelGoAccount(payable(_newAccount(PHONE_KEY)));
        bytes[] memory add = new bytes[](1);
        add[0] = hex"0102";
        assertEq(a.keysEpoch(), 0);
        assertEq(
            a.keyChangeId(add, new bytes[](0), true, address(0x1234)),
            0x8048e897beb13fafbd8ccc2e2748997a9403b510688b0bf30189fb97e2b7bc92
        );
    }

    function test_CreatingTheSameAccountTwiceGivesTheSameOne() public {
        address account = _newAccount(PHONE_KEY);
        assertEq(address(factory.createAccount(_signer(PHONE_KEY))), account);
    }
}
