// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Account} from "@openzeppelin/contracts/account/Account.sol";
import {ERC7821} from "@openzeppelin/contracts/account/extensions/draft-ERC7821.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/IERC4337.sol";
import {IERC1271} from "@openzeppelin/contracts/interfaces/IERC1271.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {Initializable} from "@openzeppelin/contracts/proxy/utils/Initializable.sol";
import {SignatureChecker} from "@openzeppelin/contracts/utils/cryptography/SignatureChecker.sol";
import {MultiSignerERC7913} from "@openzeppelin/contracts/utils/cryptography/signers/MultiSignerERC7913.sol";

/// @notice A person's account: an ERC-4337 smart account owned by keys the person's devices keep in hardware. Each
/// signer is a verifier's address followed by a key: a phone's P-256 key through `DeviceKeyVerifier`, or a
/// passkey through a WebAuthn verifier. Any one of them signs the account's operations alone (threshold 1).
///
/// The keys themselves change only by a majority of them: one device taken from its owner can spend, but it can
/// neither lock the others out nor add keys of its own, and the others can take it off. Each key approves a change
/// on chain with a signature of its own over the change (`proposeKeyChange`, `approveKeyChange`), so the devices
/// need no channel between them. A guardian the keys chose (another person's account) can replace every key with
/// one new key, after `RECOVERY_DELAY`, unless a majority of the keys changes anything in the meantime.
///
/// Clones of one implementation, made by `AccountFactory`, so an account's address is known from its first key
/// before anything is sent. It runs batches of calls (`execute`, ERC-7821) for the EntryPoint, and answers
/// `isValidSignature` (ERC-1271) so that peers can check what the account signed off chain: the certificate
/// that lets a phone's short-lived gossip key speak for it.
contract KlippelGoAccount is Account, MultiSignerERC7913, ERC7821, Initializable, IERC1271 {
    using SafeERC20 for IERC20;

    /// @notice How long the keys have to cancel a guardian's recovery before it can be finished.
    uint64 public constant RECOVERY_DELAY = 2 days;

    /// @notice The most keys one change adds or removes: bounds the loops a change runs.
    uint256 public constant MAX_KEYS_PER_CHANGE = 16;

    /// @dev The domain tag of the hash a key signs to approve a change of keys.
    bytes32 private constant KEY_CHANGE_TAG = keccak256("KlippelGo key change v1");

    /// @dev A change of keys or guardian, waiting for the approval of a majority of the keys.
    struct KeyChange {
        bytes[] add;
        bytes[] remove;
        bool setGuardian;
        address guardian;
        uint64 epoch;
        uint32 approvals;
    }

    /// @dev A guardian's recovery: the one key the account is to have once it is ready.
    struct Recovery {
        bytes signer;
        uint64 readyAt;
        uint64 epoch;
    }

    IEntryPoint private immutable _entryPoint;

    /// @notice Raised by every change of keys or guardian, and by a finished recovery. A change, an approval or a
    /// recovery made before it no longer counts.
    uint64 public keysEpoch;

    /// @notice Who may start a recovery: another person's account, or nobody (zero).
    address public guardian;

    mapping(bytes32 id => KeyChange) private _changes;
    mapping(bytes32 id => mapping(bytes32 signerId => bool)) private _approved;
    /// @dev The change each key last proposed, by epoch: at most one per key, so the pending ones can be listed.
    mapping(uint64 epoch => mapping(bytes32 signerId => bytes32 id)) private _proposed;
    Recovery private _recovery;

    event KeyChangeProposed(bytes32 indexed id, bytes32 indexed by, uint64 epoch);
    event KeyChangeApproved(bytes32 indexed id, bytes32 indexed by, uint32 approvals);
    event KeysChanged(bytes32 indexed id, uint64 epoch);
    event GuardianSet(address indexed guardian);
    event RecoveryStarted(bytes indexed signer, uint64 readyAt);
    event RecoveryFinished(bytes indexed signer, uint64 epoch);

    error NotASigner();
    error BadApproval();
    error AlreadyApproved();
    error UnknownKeyChange();
    error TooManyKeys();
    error SelfAsKey();
    error NotGuardian();
    error NoRecovery();
    error RecoveryNotReady();

    /// @dev The implementation gets a signer no key matches, so that it can never be used itself; clones start
    /// with none and get theirs in `initialize`.
    constructor(IEntryPoint entryPoint_) MultiSignerERC7913(_unusable(), 1) {
        _entryPoint = entryPoint_;
        _disableInitializers();
    }

    /// @notice Sets the account's first signers, and lets the paymaster take gas in each BRL coin it will be
    /// charged in, since the very first operation must already pay.
    function initialize(bytes[] calldata signers, address paymaster, IERC20[] calldata coins)
        external
        initializer
    {
        _addSigners(signers);
        _setThreshold(1);
        for (uint256 i; i < coins.length; ++i) {
            coins[i].forceApprove(paymaster, type(uint256).max);
        }
    }

    function entryPoint() public view override returns (IEntryPoint) {
        return _entryPoint;
    }

    // ---------------------------------------------------------------- keys, changed by a majority of them

    /// @notice How many keys must approve a change of keys: more than half of them.
    function majority() public view returns (uint256) {
        return getSignerCount() / 2 + 1;
    }

    /// @notice The id of a change at the current epoch. Phones compute it themselves, never asking this.
    function keyChangeId(bytes[] calldata add, bytes[] calldata remove, bool setGuardian, address newGuardian)
        public
        view
        returns (bytes32)
    {
        return keccak256(abi.encode(keysEpoch, add, remove, setGuardian, newGuardian));
    }

    /// @notice The hash a key signs to approve the change `id`: its own domain, the chain and this account.
    function approvalHash(bytes32 id) public view returns (bytes32) {
        return keccak256(abi.encode(KEY_CHANGE_TAG, block.chainid, address(this), id));
    }

    /// @notice Proposes adding keys, removing keys and, with `setGuardian`, naming the guardian (zero for none),
    /// approved by `signer` with `signature` over `approvalHash`. Keeping the guardian names none, so a phone needs
    /// no one's word for who it is. With nothing to add or remove and no guardian set, it changes nothing but the
    /// epoch: that is how the keys cancel a recovery. Done at once when `signer` alone is a majority.
    ///
    /// Anyone may send it: the approval is the key's own signature, bound to this account, the chain and the change,
    /// so whoever relays it adds nothing. That lets a friend, the guardian or another account of the person's pay
    /// for it when this account cannot, as when a taken key revoked the paymaster's allowance or spent the coins.
    function proposeKeyChange(
        bytes[] calldata add,
        bytes[] calldata remove,
        bool setGuardian,
        address newGuardian,
        bytes calldata signer,
        bytes calldata signature
    ) external returns (bytes32 id) {
        require(add.length <= MAX_KEYS_PER_CHANGE && remove.length <= MAX_KEYS_PER_CHANGE, TooManyKeys());
        require(newGuardian != address(this), SelfAsKey());
        // One way to write each change, so the same change always has the same id.
        require(setGuardian || newGuardian == address(0), UnknownKeyChange());
        id = keyChangeId(add, remove, setGuardian, newGuardian);
        KeyChange storage c = _changes[id];
        // The id carries the epoch, so a change is new when nobody approved it yet. The same change proposed
        // again keeps its approvals.
        if (c.approvals == 0) {
            for (uint256 i; i < add.length; ++i) {
                c.add.push(add[i]);
            }
            for (uint256 i; i < remove.length; ++i) {
                c.remove.push(remove[i]);
            }
            c.setGuardian = setGuardian;
            c.guardian = newGuardian;
            c.epoch = keysEpoch;
        }
        bytes32 by = keccak256(signer);
        _proposed[keysEpoch][by] = id;
        emit KeyChangeProposed(id, by, keysEpoch);
        _approve(id, c, signer, signature);
    }

    /// @notice Approves the pending change `id`, as `signer`, with `signature` over `approvalHash(id)`. The
    /// approval that makes a majority applies it. Anyone may send it, as `proposeKeyChange`.
    function approveKeyChange(bytes32 id, bytes calldata signer, bytes calldata signature) external {
        KeyChange storage c = _changes[id];
        require(c.epoch == keysEpoch && c.approvals > 0, UnknownKeyChange());
        _approve(id, c, signer, signature);
    }

    /// @notice A pending change: what it adds and removes, whether it sets the guardian and to whom, and how many
    /// keys approved it. `live` is false once the keys changed since it was proposed, which voids it.
    function keyChange(bytes32 id)
        external
        view
        returns (
            bytes[] memory add,
            bytes[] memory remove,
            bool setGuardian,
            address newGuardian,
            uint32 approvals,
            bool live
        )
    {
        KeyChange storage c = _changes[id];
        return
            (c.add, c.remove, c.setGuardian, c.guardian, c.approvals, c.epoch == keysEpoch && c.approvals > 0);
    }

    /// @notice The change `signer` last proposed at the current epoch, or zero.
    function proposedBy(bytes calldata signer) external view returns (bytes32) {
        return _proposed[keysEpoch][keccak256(signer)];
    }

    function approvedBy(bytes32 id, bytes calldata signer) external view returns (bool) {
        return _approved[id][keccak256(signer)];
    }

    // ---------------------------------------------------------------- recovery by the guardian

    /// @notice The guardian asks for the account's keys to be replaced by `signer` alone, a new phone's key. It
    /// can be finished `RECOVERY_DELAY` later, unless the keys change anything first. A new request replaces one
    /// still waiting.
    function startRecovery(bytes calldata signer) external {
        require(guardian != address(0) && msg.sender == guardian, NotGuardian());
        _checkNewKey(signer);
        uint64 readyAt = uint64(block.timestamp) + RECOVERY_DELAY;
        _recovery = Recovery(signer, readyAt, keysEpoch);
        emit RecoveryStarted(signer, readyAt);
    }

    /// @notice Replaces every key with the recovery's, once it is ready. Anyone may call it: what it does was
    /// decided by the guardian and left standing by the keys.
    function finishRecovery() external {
        Recovery memory r = _recovery;
        require(r.readyAt != 0 && r.epoch == keysEpoch, NoRecovery());
        require(block.timestamp >= r.readyAt, RecoveryNotReady());
        delete _recovery;
        if (!isSigner(r.signer)) {
            bytes[] memory one = new bytes[](1);
            one[0] = r.signer;
            _addSigners(one);
        }
        bytes[] memory all = getSigners(0, type(uint64).max);
        bytes[] memory others = new bytes[](all.length - 1);
        uint256 n;
        for (uint256 i; i < all.length; ++i) {
            if (keccak256(all[i]) != keccak256(r.signer)) others[n++] = all[i];
        }
        _removeSigners(others);
        ++keysEpoch;
        emit RecoveryFinished(r.signer, keysEpoch);
    }

    /// @notice The recovery waiting, if any: the key it installs and when it can be finished. `live` is false when
    /// there is none, or the keys changed since it started, which cancels it.
    function recovery() external view returns (bytes memory signer, uint64 readyAt, bool live) {
        Recovery storage r = _recovery;
        return (r.signer, r.readyAt, r.readyAt != 0 && r.epoch == keysEpoch);
    }

    // ---------------------------------------------------------------- the paymaster

    /// @notice Lets the paymaster take gas in another coin, or stops it (an amount of zero).
    function approvePaymaster(IERC20 coin, address paymaster, uint256 amount) external {
        _checkEntryPointOrSelf();
        coin.forceApprove(paymaster, amount);
    }

    // ---------------------------------------------------------------- ERC-1271

    /// @notice Whether one of the account's keys signed `hash`. The hashes KlippelGo asks this of are its own,
    /// with a domain tag, the chain and the account in them (a session certificate), so the same signature
    /// cannot be shown for another account or another purpose.
    function isValidSignature(bytes32 hash, bytes calldata signature) external view returns (bytes4) {
        return
            _rawSignatureValidation(hash, signature) ? IERC1271.isValidSignature.selector : bytes4(0xffffffff);
    }

    // ---------------------------------------------------------------- internal

    /// @dev Counts `signer`'s approval of `id`, which must be signed by that key over `approvalHash(id)`; the one
    /// that makes a majority applies the change.
    function _approve(bytes32 id, KeyChange storage c, bytes calldata signer, bytes calldata signature)
        private
    {
        require(isSigner(signer), NotASigner());
        require(SignatureChecker.isValidSignatureNow(signer, approvalHash(id), signature), BadApproval());
        bytes32 by = keccak256(signer);
        require(!_approved[id][by], AlreadyApproved());
        _approved[id][by] = true;
        uint32 approvals = ++c.approvals;
        emit KeyChangeApproved(id, by, approvals);
        if (approvals >= majority()) _apply(id, c);
    }

    /// @dev Adds before removing, so a change that swaps every key never leaves the account with none.
    function _apply(bytes32 id, KeyChange storage c) private {
        bytes[] memory add = c.add;
        bytes[] memory remove = c.remove;
        for (uint256 i; i < add.length; ++i) {
            _checkNewKey(add[i]);
        }
        _addSigners(add);
        _removeSigners(remove);
        if (c.setGuardian && c.guardian != guardian) {
            guardian = c.guardian;
            emit GuardianSet(c.guardian);
        }
        // Every other change, approval and recovery under the old keys is void from here.
        ++keysEpoch;
        delete _recovery;
        emit KeysChanged(id, keysEpoch);
    }

    /// @dev A key must be a key: at least an address. The account itself is none, as it would let one key
    /// approve in the name of all of them.
    function _checkNewKey(bytes memory signer) private view {
        require(signer.length >= 20, MultiSignerERC7913InvalidSigner(signer));
        require(signer.length != 20 || address(bytes20(signer)) != address(this), SelfAsKey());
    }

    function _erc7821AuthorizedExecutor(address caller, bytes32 mode, bytes calldata executionData)
        internal
        view
        override
        returns (bool)
    {
        return caller == address(entryPoint())
            || super._erc7821AuthorizedExecutor(caller, mode, executionData);
    }

    function _unusable() private pure returns (bytes[] memory s) {
        s = new bytes[](1);
        // An address as a signer is an ECDSA signer; no key recovers to this one.
        s[0] = abi.encodePacked(address(0xdead));
    }
}
