// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {PBACGovernor} from "./PBACGovernor.sol";

/// @notice Instituto Klippel's team, in the guardian's seat. Members hold roles; a role holds permissions; a
/// permission names one action, how many of the role's members must approve it and how long it waits.
///
/// A member requests an action, which counts as their approval. Others approve. Once enough have and the delay
/// is over, anyone executes it with the exact calls that were requested. Until then a member of the role, or of
/// a role that may cancel anything, can cancel it. A suspended member's requests and approvals stop counting at
/// once, including those given to actions still pending.
///
/// Members, roles and permissions are changed the same way: they are this contract's own functions, reached
/// only through an action, under a permission like any other. See docs/governance.md.
contract TeamCouncil {
    /// @notice A permission for one function (`Call`), or for one list of calls written in advance (`Playbook`).
    enum Kind {
        Call,
        Playbook
    }

    enum Status {
        None,
        Pending,
        Executed,
        Cancelled
    }

    struct Permission {
        uint8 role;
        Kind kind;
        bool viaGovernor; // the calls run as the governor (`onlyGovernor` setters), not as this contract
        bool active;
        uint8 approvals;
        uint32 delay; // seconds
        address target; // Call: the contract
        bytes4 selector; // Call: the function
        uint16 argWord; // Call: when non-zero, the n-th argument word must equal `argValue`
        bytes32 argValue; // Call: that word. Playbook: the hash of the calls
    }

    struct Role {
        string name;
        uint8 flags;
        uint16 members;
    }

    struct Call {
        address target;
        bytes data;
    }

    /// @notice A list of calls written in advance, that a role runs as it is. Its calls are emitted when it is
    /// granted, so anyone can read back what running it does.
    struct Playbook {
        string name;
        uint8 role;
        uint8 approvals;
        uint32 delay;
        bool viaGovernor;
        Call[] calls;
    }

    struct Action {
        uint32 permission;
        Status status;
        uint64 readyAt;
        address requester;
        bytes32 callsHash;
        address[] approvers;
    }

    /// @notice Members of a role with this flag may cancel the pending actions of every role but a protected one.
    uint8 public constant CANCELS_ANY = 1;
    /// @notice Only actions of this role may change, suspend or cancel for its members, and only its members may
    /// cancel its actions. For a recovery role that the board must not be able to switch off.
    uint8 public constant PROTECTED = 2;
    /// @notice The role whose permissions over this contract can never be revoked: the board.
    uint8 public constant BOARD = 0;

    PBACGovernor public immutable governor;
    /// @notice The shortest delay the board's permissions over this contract may have.
    uint32 public immutable minBoardDelay;

    Role[] internal _roles;
    Permission[] internal _permissions;
    Action[] internal _actions;
    mapping(address => uint256) public rolesOf; // bit n set: holds role n
    mapping(address => bool) public suspended;
    mapping(uint8 role => uint32[]) internal _permissionsOf;
    /// @dev Playbooks with a call that changes who decides: their delay keeps the floor however it is changed.
    mapping(uint32 permission => bool) internal _playbookDecides;

    /// @notice At most this many roles, so a role's bit fits in `rolesOf` and loops over roles stay short.
    uint8 public constant MAX_ROLES = 64;
    /// @notice No permission waits longer, so a slip of units cannot leave the team unable to act for years.
    uint32 public constant MAX_DELAY = 30 days;
    /// @notice An action not executed within this long after it became ready can no longer run, so an approval
    /// given months ago, for a change since made another way, cannot be used against a later decision.
    uint32 public constant EXPIRY = 14 days;

    /// @dev One more than the role of the action being executed, so the functions below know who is acting.
    /// Zero outside an execution.
    uint16 transient _acting;

    event RoleCreated(uint8 indexed role, string name, uint8 flags);
    event MemberRoleSet(address indexed member, uint8 indexed role, bool holds);
    event MemberSuspended(address indexed member, bool suspended);
    event PermissionGranted(uint32 indexed permission, Permission p);
    event PermissionRevoked(uint32 indexed permission);
    event PermissionChanged(uint32 indexed permission, uint8 approvals, uint32 delay);
    event PlaybookGranted(uint32 indexed permission, string name, bytes calls);
    event ActionRequested(
        uint256 indexed id, uint32 indexed permission, address indexed requester, uint64 readyAt, bytes calls
    );
    event ActionApproved(uint256 indexed id, address indexed member);
    event ActionCancelled(uint256 indexed id, address indexed member);
    event ActionExecuted(uint256 indexed id, address indexed by);

    error NotSelf();
    error NotMember();
    error NotInRole();
    error NoSuchRole();
    error NoSuchPermission();
    error NoSuchAction();
    error WrongStatus(Status status);
    error CallsDoNotMatch();
    error AlreadyApproved();
    error AboutThemselves();
    error NotEnoughApprovals(uint256 have, uint256 need);
    error TooEarly(uint64 readyAt);
    error InvalidPermission();
    error WouldLockOut();
    error Protected();
    error CallFailed(uint256 index, bytes reason);
    error Expired(uint64 at);

    struct MemberInit {
        address member;
        uint256 roles;
    }

    /// @notice What the council starts with. Role 0 is the board. A permission over the council itself names
    /// it as address zero, since its address is not known before it exists. `minBoardDelay` is the floor on the
    /// board's delay over who decides: zero only on a local testnet.
    struct Init {
        Role[] roles;
        MemberInit[] members;
        Permission[] permissions;
        Playbook[] playbooks;
        uint32 minBoardDelay;
    }

    /// @param governor_ The governor whose guardian this council becomes, by `acceptGuardianSeat`.
    constructor(PBACGovernor governor_, Init memory init) {
        governor = governor_;
        minBoardDelay = init.minBoardDelay;
        for (uint256 i; i < init.roles.length; ++i) {
            _createRole(init.roles[i].name, init.roles[i].flags);
        }
        for (uint256 i; i < init.members.length; ++i) {
            for (uint256 r; r < _roles.length; ++r) {
                if (init.members[i].roles & (1 << r) != 0) {
                    _setMemberRole(init.members[i].member, uint8(r), true);
                }
            }
        }
        for (uint256 i; i < init.permissions.length; ++i) {
            Permission memory p = init.permissions[i];
            if (p.kind == Kind.Call && p.target == address(0)) p.target = address(this);
            _grant(p);
        }
        for (uint256 i; i < init.playbooks.length; ++i) {
            _grantPlaybook(init.playbooks[i]);
        }
        _checkBoardKeepsControl();
    }

    /// @notice Takes the guardian's seat once the guardian has named this council. Anyone may call it: it does
    /// nothing unless the council is the one named.
    function acceptGuardianSeat() external {
        governor.acceptGuardian();
    }

    // ---------------------------------------------------------------- actions

    /// @notice Ask for an action under one permission. Counts as the requester's approval. An action that needs
    /// one approval and no delay runs at once.
    function request(uint32 permissionId, Call[] calldata calls) external returns (uint256 id) {
        Permission storage p = _permission(permissionId);
        if (!p.active) revert NoSuchPermission();
        _checkMember(msg.sender, p.role);
        _checkCalls(p, calls);
        _checkNotAboutThemselves(msg.sender, calls);

        id = _actions.length;
        Action storage a = _actions.push();
        a.permission = permissionId;
        a.status = Status.Pending;
        a.readyAt = uint64(block.timestamp) + p.delay;
        a.requester = msg.sender;
        a.callsHash = keccak256(abi.encode(calls));
        a.approvers.push(msg.sender);
        emit ActionRequested(id, permissionId, msg.sender, a.readyAt, abi.encode(calls));

        if (p.approvals <= 1 && p.delay == 0) _execute(id, a, p, calls);
    }

    /// @notice Approve a pending action. The approver passes the calls they read in its `ActionRequested`
    /// event: they must be the ones requested, and the council checks that none of them is about the approver.
    /// The approval that makes an action ready runs it in the same transaction, so no single member can cancel
    /// it in the gap between the last approval and its execution.
    function approve(uint256 id, Call[] calldata calls) external {
        Action storage a = _action(id);
        if (a.status != Status.Pending) revert WrongStatus(a.status);
        _checkNotExpired(a);
        if (keccak256(abi.encode(calls)) != a.callsHash) revert CallsDoNotMatch();
        Permission storage p = _permissions[a.permission];
        _checkMember(msg.sender, p.role);
        _checkNotAboutThemselves(msg.sender, calls);
        for (uint256 i; i < a.approvers.length; ++i) {
            if (a.approvers[i] == msg.sender) revert AlreadyApproved();
        }
        a.approvers.push(msg.sender);
        emit ActionApproved(id, msg.sender);
        if (block.timestamp >= a.readyAt && p.active && validApprovals(id) >= p.approvals) {
            _execute(id, a, p, calls);
        }
    }

    function cancel(uint256 id) external {
        Action storage a = _action(id);
        if (a.status != Status.Pending) revert WrongStatus(a.status);
        uint8 r = _permissions[a.permission].role;
        if (suspended[msg.sender]) revert NotMember();
        bool own = rolesOf[msg.sender] & (1 << r) != 0;
        if (!own && (_roles[r].flags & PROTECTED != 0 || !_cancelsAny(msg.sender))) revert NotInRole();
        a.status = Status.Cancelled;
        emit ActionCancelled(id, msg.sender);
    }

    /// @notice Anyone may execute an action that has its approvals and whose delay is over.
    function execute(uint256 id, Call[] calldata calls) external {
        Action storage a = _action(id);
        if (a.status != Status.Pending) revert WrongStatus(a.status);
        if (keccak256(abi.encode(calls)) != a.callsHash) revert CallsDoNotMatch();
        if (block.timestamp < a.readyAt) revert TooEarly(a.readyAt);
        _checkNotExpired(a);
        Permission storage p = _permissions[a.permission];
        if (!p.active) revert NoSuchPermission();
        _execute(id, a, p, calls);
    }

    // ---------------------------------------------------------------- members, roles, permissions
    // Reached only through `execute`, under a permission on this contract.

    function createRole(string calldata name, uint8 flags) external onlySelf {
        _createRole(name, flags);
    }

    /// @notice Add a member to a role, or take them out of it. A member with no role left is no longer one.
    function setMemberRole(address member, uint8 role_, bool holds) external onlySelf {
        _checkMayManage(member, role_);
        _setMemberRole(member, role_, holds);
        if (!holds) _checkRoleCanStillAct(role_);
        _checkBoardKeepsControl();
    }

    /// @notice Suspend a member at once: nothing they requested or approved counts while they are suspended.
    function setSuspended(address member, bool suspended_) external onlySelf {
        uint256 held = rolesOf[member];
        for (uint8 r; r < _roles.length; ++r) {
            if (held & (1 << r) != 0) _checkMayManage(member, r);
        }
        suspended[member] = suspended_;
        emit MemberSuspended(member, suspended_);
    }

    function grant(Permission calldata p) external onlySelf returns (uint32) {
        _checkMayManage(address(0), p.role);
        uint32 id = _grant(p);
        _checkBoardKeepsControl();
        return id;
    }

    function grantPlaybook(Playbook calldata pb) external onlySelf returns (uint32) {
        _checkMayManage(address(0), pb.role);
        return _grantPlaybook(pb);
    }

    function revokePermission(uint32 permissionId) external onlySelf {
        Permission storage p = _permission(permissionId);
        _checkMayManage(address(0), p.role);
        p.active = false;
        emit PermissionRevoked(permissionId);
        _checkBoardKeepsControl();
    }

    function setApprovalsAndDelay(uint32 permissionId, uint8 approvals, uint32 delay) external onlySelf {
        Permission storage p = _permission(permissionId);
        _checkMayManage(address(0), p.role);
        if (approvals == 0 || approvals > _roles[p.role].members) revert InvalidPermission();
        bool decides = _decides(p) || _playbookDecides[permissionId];
        if (delay > MAX_DELAY || (decides && delay < minBoardDelay)) revert InvalidPermission();
        p.approvals = approvals;
        p.delay = delay;
        emit PermissionChanged(permissionId, approvals, delay);
    }

    // ---------------------------------------------------------------- views

    function roleCount() external view returns (uint256) {
        return _roles.length;
    }

    function role(uint8 id) external view returns (Role memory) {
        return _roles[id];
    }

    function permissionCount() external view returns (uint256) {
        return _permissions.length;
    }

    function permission(uint32 id) external view returns (Permission memory) {
        return _permissions[id];
    }

    function permissionsOf(uint8 role_) external view returns (uint32[] memory) {
        return _permissionsOf[role_];
    }

    function actionCount() external view returns (uint256) {
        return _actions.length;
    }

    function action(uint256 id) external view returns (Action memory) {
        return _actions[id];
    }

    /// @notice How many of an action's approvals count now: members not suspended and still in its role.
    function validApprovals(uint256 id) public view returns (uint256 n) {
        Action storage a = _actions[id];
        uint8 r = _permissions[a.permission].role;
        for (uint256 i; i < a.approvers.length; ++i) {
            address m = a.approvers[i];
            if (!suspended[m] && rolesOf[m] & (1 << r) != 0) ++n;
        }
    }

    function hasRole(address member, uint8 role_) external view returns (bool) {
        return rolesOf[member] & (1 << role_) != 0;
    }

    // ---------------------------------------------------------------- internal

    modifier onlySelf() {
        if (msg.sender != address(this)) revert NotSelf();
        _;
    }

    function _execute(uint256 id, Action storage a, Permission storage p, Call[] calldata calls) internal {
        uint256 have = validApprovals(id);
        if (have < p.approvals) revert NotEnoughApprovals(have, p.approvals);
        a.status = Status.Executed;
        _acting = uint16(p.role) + 1;
        if (p.viaGovernor) {
            address[] memory targets = new address[](calls.length);
            bytes[] memory data = new bytes[](calls.length);
            for (uint256 i; i < calls.length; ++i) {
                targets[i] = calls[i].target;
                data[i] = calls[i].data;
            }
            governor.execute(targets, data);
        } else {
            for (uint256 i; i < calls.length; ++i) {
                (bool ok, bytes memory reason) = calls[i].target.call(calls[i].data);
                if (!ok) revert CallFailed(i, reason);
            }
        }
        _acting = 0;
        emit ActionExecuted(id, msg.sender);
    }

    function _checkCalls(Permission storage p, Call[] calldata calls) internal view {
        if (calls.length == 0) revert CallsDoNotMatch();
        if (p.kind == Kind.Playbook) {
            if (keccak256(abi.encode(calls)) != p.argValue) revert CallsDoNotMatch();
            return;
        }
        for (uint256 i; i < calls.length; ++i) {
            bytes calldata data = calls[i].data;
            if (calls[i].target != p.target || data.length < 4 || bytes4(data[:4]) != p.selector) {
                revert CallsDoNotMatch();
            }
            if (p.argWord != 0) {
                uint256 at = 4 + 32 * (uint256(p.argWord) - 1);
                if (data.length < at + 32 || bytes32(data[at:at + 32]) != p.argValue) {
                    revert CallsDoNotMatch();
                }
            }
        }
    }

    /// @dev Nobody approves their own entry into a role, suspension or removal.
    function _checkNotAboutThemselves(address member, Call[] calldata calls) internal view {
        for (uint256 i; i < calls.length; ++i) {
            bytes calldata data = calls[i].data;
            if (calls[i].target != address(this) || data.length < 36) continue;
            bytes4 sel = bytes4(data[:4]);
            if (sel != this.setMemberRole.selector && sel != this.setSuspended.selector) continue;
            if (address(uint160(uint256(bytes32(data[4:36])))) == member) revert AboutThemselves();
        }
    }

    function _checkMember(address member, uint8 role_) internal view {
        if (suspended[member]) revert NotMember();
        if (rolesOf[member] & (1 << role_) == 0) revert NotInRole();
    }

    /// @dev A protected role's members and permissions change only by actions of that role.
    function _checkMayManage(address member, uint8 role_) internal view {
        if (role_ >= _roles.length) revert NoSuchRole();
        bool isProtected = _roles[role_].flags & PROTECTED != 0;
        if (isProtected && !_actingAs(role_)) revert Protected();
        if (member == address(0)) return;
        uint256 held = rolesOf[member];
        for (uint8 r; r < _roles.length; ++r) {
            if (held & (1 << r) != 0 && _roles[r].flags & PROTECTED != 0 && !_actingAs(r)) {
                revert Protected();
            }
        }
    }

    function _actingAs(uint8 role_) internal view returns (bool) {
        return _acting == uint16(role_) + 1;
    }

    function _cancelsAny(address member) internal view returns (bool) {
        uint256 held = rolesOf[member];
        for (uint8 r; r < _roles.length; ++r) {
            if (held & (1 << r) != 0 && _roles[r].flags & CANCELS_ANY != 0) return true;
        }
        return false;
    }

    function _createRole(string memory name, uint8 flags) internal {
        if (_roles.length == MAX_ROLES) revert InvalidPermission();
        uint8 id = uint8(_roles.length);
        _roles.push(Role(name, flags, 0));
        emit RoleCreated(id, name, flags);
    }

    function _setMemberRole(address member, uint8 role_, bool holds) internal {
        if (member == address(0) || member == address(this)) revert NotMember();
        uint256 bit = 1 << role_;
        bool had = rolesOf[member] & bit != 0;
        if (had == holds) return;
        if (holds) {
            rolesOf[member] |= bit;
            _roles[role_].members++;
        } else {
            rolesOf[member] &= ~bit;
            _roles[role_].members--;
        }
        emit MemberRoleSet(member, role_, holds);
    }

    function _grant(Permission memory p) internal returns (uint32 id) {
        if (p.role >= _roles.length) revert NoSuchRole();
        if (p.approvals == 0 || p.approvals > _roles[p.role].members) revert InvalidPermission();
        if (p.kind == Kind.Call && (p.target == address(0) || p.selector == bytes4(0))) {
            revert InvalidPermission();
        }
        if (p.kind == Kind.Playbook && p.argValue == bytes32(0)) revert InvalidPermission();
        if (p.delay > MAX_DELAY || (_decides(p) && p.delay < minBoardDelay)) revert InvalidPermission();
        p.active = true;
        id = uint32(_permissions.length);
        _permissions.push(p);
        _permissionsOf[p.role].push(id);
        emit PermissionGranted(id, p);
    }

    function _grantPlaybook(Playbook memory pb) internal returns (uint32 id) {
        bool decides;
        for (uint256 i; i < pb.calls.length; ++i) {
            bytes memory data = pb.calls[i].data;
            if (data.length < 4) continue;
            bytes4 sel;
            assembly {
                sel := mload(add(data, 32))
            }
            if (_decidesCall(pb.calls[i].target, sel)) decides = true;
        }
        if (decides && pb.delay < minBoardDelay) revert InvalidPermission();
        id = _grant(
            Permission({
                role: pb.role,
                kind: Kind.Playbook,
                viaGovernor: pb.viaGovernor,
                active: true,
                approvals: pb.approvals,
                delay: pb.delay,
                target: address(0),
                selector: bytes4(0),
                argWord: 0,
                argValue: keccak256(abi.encode(pb.calls))
            })
        );
        if (decides) _playbookDecides[id] = true;
        emit PlaybookGranted(id, pb.name, abi.encode(pb.calls));
    }

    /// @dev A role must keep enough members for the approvals each of its permissions asks.
    function _checkRoleCanStillAct(uint8 role_) internal view {
        uint32[] storage ids = _permissionsOf[role_];
        uint16 members = _roles[role_].members;
        for (uint256 i; i < ids.length; ++i) {
            Permission storage p = _permissions[ids[i]];
            if (p.active && p.approvals > members) revert WouldLockOut();
        }
    }

    /// @dev The board must always be able to grant permissions and set members, or the team could lock itself
    /// out for good.
    function _checkBoardKeepsControl() internal view {
        if (_roles.length == 0) revert WouldLockOut();
        bool grants;
        bool sets;
        uint32[] storage ids = _permissionsOf[BOARD];
        for (uint256 i; i < ids.length; ++i) {
            Permission storage p = _permissions[ids[i]];
            if (!p.active || p.kind != Kind.Call || p.target != address(this) || p.argWord != 0) continue;
            if (p.selector == this.grant.selector) grants = true;
            if (p.selector == this.setMemberRole.selector) sets = true;
        }
        if (!grants || !sets) revert WouldLockOut();
    }

    /// @dev Whether a permission changes who decides: the council's members and permissions, or the guardian's
    /// seat. Whatever role holds one, it waits at least `minBoardDelay`, so nobody can be given a faster way
    /// round the board's delay. Suspending is not among them: it must be fast.
    function _decides(Permission memory p) internal view returns (bool) {
        return p.kind == Kind.Call && _decidesCall(p.target, p.selector);
    }

    function _decidesCall(address target, bytes4 s) internal view returns (bool) {
        if (target == address(governor)) return s == PBACGovernor.proposeGuardian.selector;
        if (target != address(this) && target != address(0)) return false;
        return s == this.grant.selector || s == this.grantPlaybook.selector
            || s == this.revokePermission.selector || s == this.setApprovalsAndDelay.selector
            || s == this.createRole.selector || s == this.setMemberRole.selector;
    }

    function _checkNotExpired(Action storage a) internal view {
        uint64 until = a.readyAt + EXPIRY;
        if (block.timestamp > until) revert Expired(until);
    }

    function _permission(uint32 id) internal view returns (Permission storage) {
        if (id >= _permissions.length) revert NoSuchPermission();
        return _permissions[id];
    }

    function _action(uint256 id) internal view returns (Action storage) {
        if (id >= _actions.length) revert NoSuchAction();
        return _actions[id];
    }
}
