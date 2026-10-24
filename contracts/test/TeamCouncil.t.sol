// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Test} from "forge-std/Test.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {TeamDeployer} from "../script/TeamDeployer.sol";
import {TeamCouncil} from "../src/TeamCouncil.sol";
import {PBACGovernor} from "../src/PBACGovernor.sol";
import {IdentityRegistry} from "../src/IdentityRegistry.sol";
import {RideEscrow} from "../src/RideEscrow.sol";
import {Governed} from "../src/Governed.sol";
import {MockBRL} from "../src/mocks/MockBRL.sol";

/// @notice The team council with the launch numbers of docs/governance.md: three of five directors and 72 hours
/// over who decides, two and a day over prices and rules, one and at once to protect people.
contract TeamCouncilTest is Test, TeamDeployer {
    // Permission ids, in the order TeamDeployer grants them.
    uint32 constant GRANT = 0;
    uint32 constant REVOKE_PERMISSION = 1;
    uint32 constant SET_NUMBERS = 2;
    uint32 constant SET_MEMBER_ROLE = 4;
    uint32 constant SUSPEND = 5;
    uint32 constant UNSUSPEND = 6;
    uint32 constant PROPOSE_GUARDIAN = 7;
    uint32 constant SET_TAX = 14;
    uint32 constant SET_TOKEN = 13;
    uint32 constant ATTEST = 28;
    uint32 constant REVOKE_CREDENTIAL = 29;
    uint32 constant BLOCK = 30;
    uint32 constant UNBLOCK = 31;
    uint32 constant STOP_RIDES = 35;
    uint32 constant RESUME_RIDES = 36;
    uint32 constant RECOVERY_SUSPEND = 32;
    uint32 constant RECOVERY_SET_BOARD = 33;
    uint32 constant GRANT_PLAYBOOK = 34;

    MockBRL brl;
    Protocol p;
    TeamCouncil council;
    TeamCouncil.Call[] stop;
    TeamCouncil.Call[] resume;

    address[] board;
    address[] finance;
    address[] operations;
    address[] credentialing;
    address[] security;
    address recovery = makeAddr("recovery");
    address rider = makeAddr("rider");
    address taxman = makeAddr("taxman");

    function setUp() public {
        brl = new MockBRL("BRZ", "BRZ", 4);
        for (uint256 i; i < 5; ++i) {
            board.push(makeAddr(string.concat("director", vm.toString(i))));
        }
        for (uint256 i; i < 3; ++i) {
            finance.push(makeAddr(string.concat("finance", vm.toString(i))));
            operations.push(makeAddr(string.concat("operations", vm.toString(i))));
            credentialing.push(makeAddr(string.concat("credentialing", vm.toString(i))));
        }
        security.push(makeAddr("security0"));
        security.push(makeAddr("security1"));

        TokenInit[] memory tokens = new TokenInit[](1);
        tokens[0] = TokenInit(IERC20(address(brl)), 4);
        p = _deployProtocol(
            Config({
                stakeToken: IERC20(address(brl)),
                stakeDecimals: 4,
                tokens: tokens,
                guardian: address(this), // until the council takes the seat
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
        council = _deployTeam(
            p,
            Team({
                board: board,
                finance: finance,
                operations: operations,
                credentialing: credentialing,
                security: security,
                recovery: recovery,
                local: false,
                paymaster: address(0)
            })
        );
        TeamCouncil.Call[] memory s = _stopRides(p, false);
        TeamCouncil.Call[] memory r = _stopRides(p, true);
        for (uint256 i; i < s.length; ++i) {
            stop.push(s[i]);
            resume.push(r[i]);
        }

        brl.mint(rider, 1_000e4);
        vm.startPrank(rider);
        p.registry.register(1);
        brl.approve(address(p.escrow), type(uint256).max);
        vm.stopPrank();
    }

    // ---------------------------------------------------------------- helpers

    function _one(address target, bytes memory data) internal pure returns (TeamCouncil.Call[] memory c) {
        c = new TeamCouncil.Call[](1);
        c[0] = TeamCouncil.Call(target, data);
    }

    function _request(address member, uint32 permission, TeamCouncil.Call[] memory calls)
        internal
        returns (uint256 id)
    {
        vm.prank(member);
        id = council.request(permission, calls);
    }

    function _approve(address member, uint256 id, TeamCouncil.Call[] memory calls) internal {
        vm.prank(member);
        council.approve(id, calls);
    }

    function _status(uint256 id) internal view returns (TeamCouncil.Status) {
        return council.action(id).status;
    }

    /// @dev The board, three of five, after its 72 hours.
    function _boardDoes(TeamCouncil.Call[] memory calls, uint32 permission) internal returns (uint256 id) {
        id = _request(board[0], permission, calls);
        _approve(board[1], id, calls);
        _approve(board[2], id, calls);
        skip(72 hours);
        council.execute(id, calls);
    }

    // ---------------------------------------------------------------- the seat

    function test_TheCouncilHoldsTheSeatAndTheDeployerNothing() public {
        assertEq(p.governor.guardian(), address(council));
        assertEq(p.governor.pendingGuardian(), address(0));
        assertTrue(p.registry.trustedAttester(address(council)));

        vm.expectRevert(PBACGovernor.NotGuardian.selector);
        _guardianCall(p, address(p.escrow), abi.encodeCall(p.escrow.setTax, (500, taxman)));
        // Nor can anyone call the council's own functions except through an action.
        vm.expectRevert(TeamCouncil.NotSelf.selector);
        council.setMemberRole(address(this), BOARD, true);
    }

    // ---------------------------------------------------------------- a change of a price

    function test_FinanceChangesTheTaxWithTwoApprovalsAndADay() public {
        TeamCouncil.Call[] memory calls =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (400, taxman)));

        // Not a member of Finanças, not even a director.
        vm.prank(board[0]);
        vm.expectRevert(TeamCouncil.NotInRole.selector);
        council.request(SET_TAX, calls);
        // A permission covers its own function only.
        vm.prank(finance[0]);
        vm.expectRevert(TeamCouncil.CallsDoNotMatch.selector);
        council.request(SET_TOKEN, calls);

        uint256 id = _request(finance[0], SET_TAX, calls);
        _approve(finance[1], id, calls);
        // Approving twice counts once, the calls executed must be the ones requested, and the day must pass.
        vm.prank(finance[1]);
        vm.expectRevert(TeamCouncil.AlreadyApproved.selector);
        council.approve(id, calls);
        TeamCouncil.Call[] memory other =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (9_000, finance[0])));
        vm.expectRevert(TeamCouncil.CallsDoNotMatch.selector);
        council.execute(id, other);
        vm.expectRevert(
            abi.encodeWithSelector(TeamCouncil.TooEarly.selector, uint64(block.timestamp + 24 hours))
        );
        council.execute(id, calls);

        skip(24 hours);
        council.execute(id, calls);
        assertEq(p.escrow.taxBps(), 400);
        assertEq(uint8(_status(id)), uint8(TeamCouncil.Status.Executed));
        vm.expectRevert(abi.encodeWithSelector(TeamCouncil.WrongStatus.selector, TeamCouncil.Status.Executed));
        council.execute(id, calls);

        // One approval is not enough after the day either; the second, once it is ready, runs it at once.
        TeamCouncil.Call[] memory again =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (500, taxman)));
        id = _request(finance[0], SET_TAX, again);
        skip(24 hours);
        vm.expectRevert(abi.encodeWithSelector(TeamCouncil.NotEnoughApprovals.selector, 1, 2));
        council.execute(id, again);
        _approve(finance[1], id, again);
        assertEq(p.escrow.taxBps(), 500);
    }

    function test_AnApprovedActionExpires() public {
        TeamCouncil.Call[] memory calls =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (400, taxman)));
        uint256 id = _request(finance[0], SET_TAX, calls);
        _approve(finance[1], id, calls);
        skip(24 hours + 14 days + 1);
        vm.expectRevert(abi.encodeWithSelector(TeamCouncil.Expired.selector, uint64(block.timestamp - 1)));
        council.execute(id, calls);
    }

    function test_ADayIsADay() public {
        TeamCouncil.Call[] memory calls =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (400, taxman)));
        uint256 id = _request(finance[0], SET_TAX, calls);
        _approve(finance[1], id, calls);
        skip(24 hours - 1);
        vm.expectRevert(abi.encodeWithSelector(TeamCouncil.TooEarly.selector, uint64(block.timestamp + 1)));
        council.execute(id, calls);
    }

    function test_ARoleMemberOrADirectorCancelsAndNobodyElse() public {
        TeamCouncil.Call[] memory calls =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (400, taxman)));
        uint256 id = _request(finance[0], SET_TAX, calls);
        vm.prank(security[0]);
        vm.expectRevert(TeamCouncil.NotInRole.selector);
        council.cancel(id);
        vm.prank(board[4]);
        council.cancel(id);
        assertEq(uint8(_status(id)), uint8(TeamCouncil.Status.Cancelled));

        id = _request(finance[0], SET_TAX, calls);
        vm.prank(finance[2]);
        council.cancel(id);
        _approve_reverts(finance[1], id, calls);
    }

    function _approve_reverts(address member, uint256 id, TeamCouncil.Call[] memory calls) internal {
        vm.prank(member);
        vm.expectRevert(
            abi.encodeWithSelector(TeamCouncil.WrongStatus.selector, TeamCouncil.Status.Cancelled)
        );
        council.approve(id, calls);
    }

    // ---------------------------------------------------------------- protecting people

    function test_OneMemberBlocksAtOnceAndUnblockingTakesTwo() public {
        TeamCouncil.Call[] memory blockIt =
            _one(address(p.registry), abi.encodeCall(p.registry.setBlocked, (rider, true)));
        TeamCouncil.Call[] memory unblock =
            _one(address(p.registry), abi.encodeCall(p.registry.setBlocked, (rider, false)));

        _request(security[0], BLOCK, blockIt);
        assertTrue(p.registry.blocked(rider));

        // The permission to block cannot be used to unblock.
        vm.prank(security[0]);
        vm.expectRevert(TeamCouncil.CallsDoNotMatch.selector);
        council.request(BLOCK, unblock);

        uint256 id = _request(security[0], UNBLOCK, unblock);
        assertTrue(p.registry.blocked(rider));
        _approve(security[1], id, unblock); // the second approval, with no delay, runs it

        assertFalse(p.registry.blocked(rider));
    }

    function test_SecurityStopsNewRidesAtOnceAndTheBoardResumesThem() public {
        _request(security[1], STOP_RIDES, stop);
        vm.prank(rider);
        vm.expectRevert(
            abi.encodeWithSelector(Governed.NotPermitted.selector, rider, RideEscrow.open.selector)
        );
        p.escrow.open(makeAddr("driver"), brl, 20_0000, keccak256("quote"));

        // A playbook is its exact calls: nothing else runs under it.
        TeamCouncil.Call[] memory other = _one(
            address(p.governor),
            abi.encodeCall(
                p.governor.setPolicy,
                (address(p.escrow), RideEscrow.open.selector, p.governor.policy(address(p.escrow), bytes4(0)))
            )
        );
        vm.prank(security[1]);
        vm.expectRevert(TeamCouncil.CallsDoNotMatch.selector);
        council.request(STOP_RIDES, other);

        uint256 id = _request(board[0], RESUME_RIDES, resume);
        _approve(board[1], id, resume);

        vm.prank(rider);
        p.escrow.open(makeAddr("driver"), brl, 20_0000, keccak256("quote"));
    }

    function test_TheCouncilIssuesCredentialsWithTwoAndRevokesWithOne() public {
        address driver = makeAddr("driver");
        vm.prank(driver);
        p.registry.register(2);
        bytes32 cnh = keccak256("klippelgo.credential.CNH");
        TeamCouncil.Call[] memory approveCnh =
            _one(address(p.registry), abi.encodeCall(p.registry.attest, (driver, cnh, keccak256("doc"), 0)));
        uint256 id = _request(credentialing[0], ATTEST, approveCnh);
        assertFalse(p.registry.hasCredential(driver, cnh));
        _approve(credentialing[1], id, approveCnh);

        assertTrue(p.registry.hasCredential(driver, cnh));
        (, address issuer,,) = p.registry.credentials(driver, cnh);
        assertEq(issuer, address(council));

        _request(
            credentialing[2],
            REVOKE_CREDENTIAL,
            _one(address(p.registry), abi.encodeCall(p.registry.revoke, (driver, cnh)))
        );
        assertFalse(p.registry.hasCredential(driver, cnh));
    }

    // ---------------------------------------------------------------- members

    function test_TheBoardAddsAMemberAndNobodyApprovesTheirOwnEntry() public {
        address newcomer = makeAddr("newcomer");
        TeamCouncil.Call[] memory calls =
            _one(address(council), abi.encodeCall(council.setMemberRole, (newcomer, FINANCE, true)));
        uint256 id = _request(board[0], SET_MEMBER_ROLE, calls);
        _approve(board[1], id, calls);

        // A director cannot request or approve their own entry into a role.
        TeamCouncil.Call[] memory own =
            _one(address(council), abi.encodeCall(council.setMemberRole, (board[3], FINANCE, true)));
        vm.prank(board[3]);
        vm.expectRevert(TeamCouncil.AboutThemselves.selector);
        council.request(SET_MEMBER_ROLE, own);
        uint256 other = _request(board[4], SET_MEMBER_ROLE, own);
        vm.prank(board[3]);
        vm.expectRevert(TeamCouncil.AboutThemselves.selector);
        council.approve(other, own);

        _approve(board[2], id, calls);
        skip(72 hours);
        council.execute(id, calls);
        assertTrue(council.hasRole(newcomer, FINANCE));
        assertEq(council.role(FINANCE).members, 4);
    }

    function test_ASuspendedMembersApprovalsStopCountingAtOnce() public {
        TeamCouncil.Call[] memory calls =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (400, taxman)));
        uint256 id = _request(finance[0], SET_TAX, calls);
        _approve(finance[1], id, calls);

        // Two directors suspend the requester at once.
        TeamCouncil.Call[] memory suspend =
            _one(address(council), abi.encodeCall(council.setSuspended, (finance[0], true)));
        uint256 s = _request(board[0], SUSPEND, suspend);
        _approve(board[1], s, suspend);

        assertTrue(council.suspended(finance[0]));

        skip(24 hours);
        assertEq(council.validApprovals(id), 1);
        vm.expectRevert(abi.encodeWithSelector(TeamCouncil.NotEnoughApprovals.selector, 1, 2));
        council.execute(id, calls);
        vm.prank(finance[0]);
        vm.expectRevert(TeamCouncil.NotMember.selector);
        council.request(SET_TAX, calls);

        // Lifting a suspension is as slow as adding someone.
        TeamCouncil.Call[] memory lift =
            _one(address(council), abi.encodeCall(council.setSuspended, (finance[0], false)));
        vm.prank(board[0]);
        vm.expectRevert(TeamCouncil.CallsDoNotMatch.selector);
        council.request(SUSPEND, lift);
        _boardDoes(lift, UNSUSPEND);
        assertFalse(council.suspended(finance[0]));
    }

    // ---------------------------------------------------------------- the team cannot lock itself out

    function test_TheBoardCannotLockItselfOut() public {
        // Below three directors, the board's own permissions could never pass again.
        _boardDoes(
            _one(address(council), abi.encodeCall(council.setMemberRole, (board[4], BOARD, false))),
            SET_MEMBER_ROLE
        );
        _boardDoes(
            _one(address(council), abi.encodeCall(council.setMemberRole, (board[3], BOARD, false))),
            SET_MEMBER_ROLE
        );
        TeamCouncil.Call[] memory third =
            _one(address(council), abi.encodeCall(council.setMemberRole, (board[2], BOARD, false)));
        uint256 id = _request(board[0], SET_MEMBER_ROLE, third);
        _approve(board[1], id, third);
        vm.prank(board[2]);
        vm.expectRevert(TeamCouncil.AboutThemselves.selector);
        council.approve(id, third);

        // Taking away the board's power to grant, or making it faster than a day, is refused.
        TeamCouncil.Call[] memory revokeGrant =
            _one(address(council), abi.encodeCall(council.revokePermission, (GRANT)));
        id = _request(board[0], REVOKE_PERMISSION, revokeGrant);
        _approve(board[1], id, revokeGrant);
        _approve(board[2], id, revokeGrant);
        skip(72 hours);
        vm.expectRevert(
            abi.encodeWithSelector(
                TeamCouncil.CallFailed.selector, 0, abi.encodeWithSelector(TeamCouncil.WouldLockOut.selector)
            )
        );
        council.execute(id, revokeGrant);

        TeamCouncil.Call[] memory faster =
            _one(address(council), abi.encodeCall(council.setApprovalsAndDelay, (GRANT, 3, 1 hours)));
        id = _request(board[0], SET_NUMBERS, faster);
        _approve(board[1], id, faster);
        _approve(board[2], id, faster);
        skip(72 hours);
        vm.expectRevert(
            abi.encodeWithSelector(
                TeamCouncil.CallFailed.selector,
                0,
                abi.encodeWithSelector(TeamCouncil.InvalidPermission.selector)
            )
        );
        council.execute(id, faster);
    }

    /// @dev The board, three of five, after its 72 hours; returns what the council said when it ran.
    function _boardTries(TeamCouncil.Call[] memory calls, uint32 permission) internal returns (bytes memory) {
        uint256 id = _request(board[0], permission, calls);
        _approve(board[1], id, calls);
        _approve(board[2], id, calls);
        skip(72 hours);
        try council.execute(id, calls) {
            return "";
        } catch (bytes memory reason) {
            return reason;
        }
    }

    function test_NothingGoesRoundTheBoardsDelay() public {
        bytes memory refused = abi.encodeWithSelector(
            TeamCouncil.CallFailed.selector, 0, abi.encodeWithSelector(TeamCouncil.InvalidPermission.selector)
        );
        // Giving another role a fast way to change who is in the team, or to hand the seat on, is refused.
        TeamCouncil.Permission memory fast = TeamCouncil.Permission({
            role: SECURITY,
            kind: TeamCouncil.Kind.Call,
            viaGovernor: false,
            active: true,
            approvals: 1,
            delay: 0,
            target: address(council),
            selector: TeamCouncil.setMemberRole.selector,
            argWord: 0,
            argValue: 0
        });
        assertEq(_boardTries(_one(address(council), abi.encodeCall(council.grant, (fast))), GRANT), refused);
        fast.target = address(p.governor);
        fast.selector = PBACGovernor.proposeGuardian.selector;
        assertEq(_boardTries(_one(address(council), abi.encodeCall(council.grant, (fast))), GRANT), refused);
        // Nor making the board's own hand-over of the seat immediate.
        assertEq(
            _boardTries(
                _one(
                    address(council), abi.encodeCall(council.setApprovalsAndDelay, (PROPOSE_GUARDIAN, 3, 0))
                ),
                SET_NUMBERS
            ),
            refused
        );
        // Nor a playbook that adds a director with no delay.
        TeamCouncil.Call[] memory addDirector =
            _one(address(council), abi.encodeCall(council.setMemberRole, (makeAddr("x"), BOARD, true)));
        TeamCouncil.Playbook memory pb = TeamCouncil.Playbook("quick", SECURITY, 1, 0, false, addDirector);
        assertEq(
            _boardTries(_one(address(council), abi.encodeCall(council.grantPlaybook, (pb))), GRANT_PLAYBOOK),
            refused
        );
        // Nor storing it with a day and then making it immediate.
        pb.delay = 1 days;
        assertEq(
            _boardTries(_one(address(council), abi.encodeCall(council.grantPlaybook, (pb))), GRANT_PLAYBOOK),
            ""
        );
        uint32 quick = uint32(council.permissionCount() - 1);
        assertEq(
            _boardTries(
                _one(address(council), abi.encodeCall(council.setApprovalsAndDelay, (quick, 1, 0))),
                SET_NUMBERS
            ),
            refused
        );
        // And no delay is longer than 30 days, so a slip of units cannot lock the team out for years.
        assertEq(
            _boardTries(
                _one(address(council), abi.encodeCall(council.setApprovalsAndDelay, (GRANT, 3, 31 days))),
                SET_NUMBERS
            ),
            refused
        );
        // With a day, another role may hold it.
        fast.target = address(council);
        fast.selector = TeamCouncil.setMemberRole.selector;
        fast.delay = 1 days;
        assertEq(_boardTries(_one(address(council), abi.encodeCall(council.grant, (fast))), GRANT), "");
    }

    // ---------------------------------------------------------------- recovery

    function test_TheBoardCannotTouchRecovery() public {
        TeamCouncil.Call[] memory suspendRecovery =
            _one(address(council), abi.encodeCall(council.setSuspended, (recovery, true)));
        uint256 id = _request(board[0], SUSPEND, suspendRecovery);
        // The second approval makes it ready and runs it: the council refuses it there.
        vm.prank(board[1]);
        vm.expectRevert(
            abi.encodeWithSelector(
                TeamCouncil.CallFailed.selector, 0, abi.encodeWithSelector(TeamCouncil.Protected.selector)
            )
        );
        council.approve(id, suspendRecovery);

        // Recovery's replacement of a director waits a week, and the board cannot cancel it.
        address replacement = makeAddr("replacement");
        TeamCouncil.Call[] memory addDirector =
            _one(address(council), abi.encodeCall(council.setMemberRole, (replacement, BOARD, true)));
        id = _request(recovery, RECOVERY_SET_BOARD, addDirector);
        vm.prank(board[0]);
        vm.expectRevert(TeamCouncil.NotInRole.selector);
        council.cancel(id);
        skip(7 days - 1);
        vm.expectRevert();
        council.execute(id, addDirector);
        skip(1);
        council.execute(id, addDirector);
        assertTrue(council.hasRole(replacement, BOARD));

        // Recovery may name directors only: its permission is fixed to the board's role.
        TeamCouncil.Call[] memory addFinance =
            _one(address(council), abi.encodeCall(council.setMemberRole, (replacement, FINANCE, true)));
        vm.prank(recovery);
        vm.expectRevert(TeamCouncil.CallsDoNotMatch.selector);
        council.request(RECOVERY_SET_BOARD, addFinance);
    }

    function test_RecoverySuspendsAtOnceAndCancelsAnything() public {
        TeamCouncil.Call[] memory calls =
            _one(address(p.escrow), abi.encodeCall(p.escrow.setTax, (9_000, taxman)));
        uint256 id = _request(finance[0], SET_TAX, calls);
        _request(
            recovery,
            RECOVERY_SUSPEND,
            _one(address(council), abi.encodeCall(council.setSuspended, (finance[0], true)))
        );
        assertTrue(council.suspended(finance[0]));
        vm.prank(recovery);
        council.cancel(id);
        assertEq(uint8(_status(id)), uint8(TeamCouncil.Status.Cancelled));
    }

    // ---------------------------------------------------------------- handing the seat on

    function test_TheBoardHandsTheSeatToANewCouncil() public {
        address next = makeAddr("nextCouncil");
        _boardDoes(
            _one(address(p.governor), abi.encodeCall(p.governor.proposeGuardian, (next))), PROPOSE_GUARDIAN
        );
        assertEq(p.governor.pendingGuardian(), next);
        vm.prank(next);
        p.governor.acceptGuardian();
        assertEq(p.governor.guardian(), next);
    }
}
