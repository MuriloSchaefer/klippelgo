// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Strings} from "@openzeppelin/contracts/utils/Strings.sol";
import {ProtocolDeployer} from "./ProtocolDeployer.sol";
import {TeamCouncil} from "../src/TeamCouncil.sol";
import {PBACGovernor} from "../src/PBACGovernor.sol";
import {IdentityStore} from "../src/IdentityStore.sol";
import {StakeVault} from "../src/StakeVault.sol";
import {ReleaseRegistry} from "../src/ReleaseRegistry.sol";
import {DataContract} from "../src/DataContract.sol";
import {FeeVault} from "../src/FeeVault.sol";
import {RideEscrow} from "../src/RideEscrow.sol";
import {DisputeCourt} from "../src/DisputeCourt.sol";
import {MapRegistry} from "../src/MapRegistry.sol";
import {FareGuide} from "../src/FareGuide.sol";
import {BrlPaymaster} from "../src/account/BrlPaymaster.sol";

/// @notice Builds Instituto Klippel's team council over a deployed protocol and hands it the guardian's seat.
/// The roles, who may do what, and the approvals and delays are those of docs/governance.md.
///
/// The protocol is deployed first with the deployer as its guardian, because the council's permissions name
/// the protocol's addresses. `_deployTeam` then makes the council a trusted attester and hands it the seat, so
/// the deployer holds nothing once it returns.
abstract contract TeamDeployer is ProtocolDeployer {
    uint8 internal constant BOARD = 0;
    uint8 internal constant FINANCE = 1;
    uint8 internal constant OPERATIONS = 2;
    uint8 internal constant CREDENTIALING = 3;
    uint8 internal constant SECURITY = 4;
    uint8 internal constant RECOVERY = 5;

    struct Team {
        address[] board;
        address[] finance;
        address[] operations;
        address[] credentialing;
        address[] security;
        address recovery; // a cold multisig, never used day to day
        bool local; // the local testnet: one approval and no delay for everything, so tests stay fast
        address paymaster; // the gas paymaster in reais, or zero for none; its permissions come last
        uint48 release; // the release published at launch, or zero for none: its revoke-release playbook
    }

    /// @dev Approvals and delays, launch values or local ones.
    struct Numbers {
        uint8 board;
        uint32 boardDelay;
        uint8 two;
        uint32 dayDelay;
        uint32 recoveryDelay;
    }

    /// @dev Calls the guardian makes until the seat is handed over. The deployer is the guardian then.
    function _guardianCall(Protocol memory p, address target, bytes memory data) internal {
        address[] memory targets = new address[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = target;
        calls[0] = data;
        p.governor.execute(targets, calls);
    }

    function _deployTeam(Protocol memory p, Team memory t) internal returns (TeamCouncil council) {
        Numbers memory n = t.local
            ? Numbers({board: 1, boardDelay: 0, two: 1, dayDelay: 0, recoveryDelay: 0})
            : Numbers({board: 3, boardDelay: 72 hours, two: 2, dayDelay: 24 hours, recoveryDelay: 7 days});

        council = new TeamCouncil(
            p.governor,
            TeamCouncil.Init({
                roles: _roles(),
                members: _members(t),
                permissions: _permissions(p, n, address(0), t.paymaster),
                playbooks: _playbooks(p, n, t.release),
                minBoardDelay: t.local ? 0 : 24 hours
            })
        );

        _guardianCall(
            p, address(p.identities), abi.encodeCall(p.identities.setAttester, (address(council), true))
        );
        p.governor.proposeGuardian(address(council));
        council.acceptGuardianSeat();
    }

    function _roles() internal pure returns (TeamCouncil.Role[] memory r) {
        r = new TeamCouncil.Role[](6);
        r[BOARD] = TeamCouncil.Role("Diretoria", 1, 0); // CANCELS_ANY
        r[FINANCE] = TeamCouncil.Role(unicode"Finanças", 0, 0);
        r[OPERATIONS] = TeamCouncil.Role(unicode"Operações", 0, 0);
        r[CREDENTIALING] = TeamCouncil.Role("Credenciamento", 0, 0);
        r[SECURITY] = TeamCouncil.Role(unicode"Segurança", 0, 0);
        r[RECOVERY] = TeamCouncil.Role(unicode"Recuperação", 3, 0); // CANCELS_ANY | PROTECTED
    }

    function _members(Team memory t) internal pure returns (TeamCouncil.MemberInit[] memory m) {
        uint256 total = t.board.length + t.finance.length + t.operations.length + t.credentialing.length
            + t.security.length + 1;
        m = new TeamCouncil.MemberInit[](total);
        uint256 k;
        k = _add(m, k, t.board, BOARD);
        k = _add(m, k, t.finance, FINANCE);
        k = _add(m, k, t.operations, OPERATIONS);
        k = _add(m, k, t.credentialing, CREDENTIALING);
        k = _add(m, k, t.security, SECURITY);
        m[k] = TeamCouncil.MemberInit(t.recovery, 1 << RECOVERY);
    }

    function _add(TeamCouncil.MemberInit[] memory m, uint256 k, address[] memory who, uint8 role)
        internal
        pure
        returns (uint256)
    {
        for (uint256 i; i < who.length; ++i) {
            m[k++] = TeamCouncil.MemberInit(who[i], 1 << role);
        }
        return k;
    }

    /// @dev `self` is address zero: the council reads it as its own address.
    function _permissions(Protocol memory p, Numbers memory n, address self, address paymaster)
        internal
        view
        returns (TeamCouncil.Permission[] memory list)
    {
        list = new TeamCouncil.Permission[](48);
        uint256 k;
        PBACGovernor g = p.governor;

        // The board: who decides, and what the protocol is wired to.
        bytes4[5] memory own = [
            TeamCouncil.grant.selector,
            TeamCouncil.revokePermission.selector,
            TeamCouncil.setApprovalsAndDelay.selector,
            TeamCouncil.createRole.selector,
            TeamCouncil.setMemberRole.selector
        ];
        for (uint256 i; i < own.length; ++i) {
            list[k++] = _direct(BOARD, self, own[i], n.board, n.boardDelay);
        }
        list[k++] = _directArg(BOARD, self, TeamCouncil.setSuspended.selector, 2, 1, n.two, 0);
        list[k++] = _directArg(BOARD, self, TeamCouncil.setSuspended.selector, 2, 0, n.board, n.boardDelay);
        list[k++] = _direct(BOARD, address(g), PBACGovernor.proposeGuardian.selector, n.board, n.boardDelay);
        list[k++] = _viaGov(BOARD, address(g), PBACGovernor.setModules.selector, n.board, n.boardDelay);
        list[k++] = _viaGov(BOARD, address(p.escrow), RideEscrow.setModules.selector, n.board, n.boardDelay);
        list[k++] =
            _viaGov(BOARD, address(p.reputation), DataContract.setWriter.selector, n.board, n.boardDelay);
        list[k++] =
            _viaGov(BOARD, address(p.identities), IdentityStore.setAttester.selector, n.board, n.boardDelay);
        list[k++] =
            _viaGov(BOARD, address(p.feeVault), FeeVault.setBeneficiary.selector, n.board, n.boardDelay);

        // Finanças: prices and money.
        list[k++] = _viaGov(FINANCE, address(p.escrow), RideEscrow.setToken.selector, n.two, n.dayDelay);
        list[k++] = _viaGov(FINANCE, address(p.escrow), RideEscrow.setTax.selector, n.two, n.dayDelay);
        list[k++] = _viaGov(FINANCE, address(p.escrow), RideEscrow.setCap.selector, n.two, n.dayDelay);
        list[k++] = _viaGov(FINANCE, address(p.fares), FareGuide.setTariff.selector, n.two, n.dayDelay);
        list[k++] =
            _viaGov(FINANCE, address(p.fares), FareGuide.setNativeCoinPrice.selector, n.two, n.dayDelay);
        list[k++] = _viaGov(FINANCE, address(p.feeVault), FeeVault.payClaim.selector, n.two, n.dayDelay);

        // Operações: the rules of the network and its maps.
        list[k++] = _viaGov(OPERATIONS, address(g), PBACGovernor.setPolicy.selector, n.two, n.dayDelay);
        list[k++] = _viaGov(
            OPERATIONS, address(p.escrow), RideEscrow.setCompletionTimeout.selector, n.two, n.dayDelay
        );
        list[k++] =
            _viaGov(OPERATIONS, address(p.stakes), StakeVault.setUnbondingPeriod.selector, n.two, n.dayDelay);
        list[k++] = _viaGov(OPERATIONS, address(p.court), DisputeCourt.setPeriods.selector, n.two, n.dayDelay);
        bytes4[5] memory maps = [
            MapRegistry.activate.selector,
            MapRegistry.retire.selector,
            MapRegistry.setRebuildQuorum.selector,
            MapRegistry.setGracePeriod.selector,
            MapRegistry.setVerifierParams.selector
        ];
        for (uint256 i; i < maps.length; ++i) {
            list[k++] = _viaGov(OPERATIONS, address(p.maps), maps[i], n.two, n.dayDelay);
        }

        // Credenciamento: the council is the attester, so credentials outlive the member who checked them.
        list[k++] = _direct(CREDENTIALING, address(p.identities), IdentityStore.attest.selector, n.two, 0);
        list[k++] = _direct(CREDENTIALING, address(p.identities), IdentityStore.revoke.selector, 1, 0);

        // Segurança: what protects people, at once. Blocking takes one; unblocking takes two.
        list[k++] = _viaGovArg(SECURITY, address(p.identities), IdentityStore.setBlocked.selector, 2, 1, 1, 0);
        list[k++] =
            _viaGovArg(SECURITY, address(p.identities), IdentityStore.setBlocked.selector, 2, 0, n.two, 0);

        // Recuperação: cancels (by its flag), suspends at once, and replaces the board after a week.
        list[k++] = _directArg(RECOVERY, self, TeamCouncil.setSuspended.selector, 2, 1, 1, 0);
        list[k++] =
            _directArg(RECOVERY, self, TeamCouncil.setMemberRole.selector, 2, BOARD, 1, n.recoveryDelay);

        // The board stores playbooks, such as a new resume-rides after Operações changed a policy (L25).
        list[k++] = _direct(BOARD, self, TeamCouncil.grantPlaybook.selector, n.board, n.boardDelay);

        // Releases (docs/releases-and-data.md). Wiring a code contract into the data is the one action that can
        // change what an account has, so it is the board's, with its delay: everyone's window to see it coming.
        list[k++] =
            _viaGov(BOARD, address(p.identities), DataContract.setWriter.selector, n.board, n.boardDelay);
        // Publishing and deprecating is the board's, and so is putting a release back. Revoking one after a
        // vulnerability is Segurança's, one member, at once, like stop-rides.
        list[k++] =
            _viaGov(BOARD, address(p.releases), ReleaseRegistry.publish.selector, n.board, n.boardDelay);
        list[k++] =
            _viaGov(BOARD, address(p.releases), ReleaseRegistry.deprecate.selector, n.board, n.boardDelay);
        list[k++] = _viaGov(BOARD, address(p.releases), ReleaseRegistry.reinstate.selector, n.two, 0);
        list[k++] = _viaGov(SECURITY, address(p.releases), ReleaseRegistry.revoke.selector, 1, 0);

        // The paymaster: Finanças sends on the reais it collected; the board moves its native coin and stake.
        if (paymaster != address(0)) {
            list[k++] = _viaGov(FINANCE, paymaster, BrlPaymaster.withdrawTokens.selector, n.two, n.dayDelay);
            list[k++] = _viaGov(BOARD, paymaster, BrlPaymaster.withdraw.selector, n.board, n.boardDelay);
            list[k++] = _viaGov(BOARD, paymaster, BrlPaymaster.unlockStake.selector, n.board, n.boardDelay);
            list[k++] = _viaGov(BOARD, paymaster, BrlPaymaster.withdrawStake.selector, n.board, n.boardDelay);
        }

        assembly {
            mstore(list, k)
        }
    }

    /// @dev Granted after the permissions above, so their ids follow them.
    function _playbooks(Protocol memory p, Numbers memory n, uint48 release)
        internal
        view
        returns (TeamCouncil.Playbook[] memory pb)
    {
        pb = new TeamCouncil.Playbook[](release == 0 ? 2 : 3);
        pb[0] = TeamCouncil.Playbook("stop-rides", SECURITY, 1, 0, true, _stopRides(p, false));
        pb[1] = TeamCouncil.Playbook("resume-rides", BOARD, n.two, 0, true, _stopRides(p, true));
        if (release != 0) {
            pb[2] = TeamCouncil.Playbook(
                _revokeReleaseName(release), SECURITY, 1, 0, true, _revokeRelease(p, release)
            );
        }
    }

    /// @notice `revoke-release-<version>`: one per release, so the one Segurança runs names the release it revokes.
    function _revokeReleaseName(uint48 release) internal pure returns (string memory) {
        return string.concat(
            "revoke-release-",
            Strings.toString(release >> 32),
            ".",
            Strings.toString((release >> 16) & 0xffff),
            ".",
            Strings.toString(release & 0xffff)
        );
    }

    /// @notice A release's revoke, with its escrow's door closed in the same action: honest apps stop starting
    /// rides on it, and no app at all can open or accept one there. Rides under way finish on their own terms.
    /// The board grants one of these with each release it publishes, since it names that release's escrow.
    function _revokeRelease(Protocol memory p, uint48 release)
        internal
        view
        returns (TeamCouncil.Call[] memory c)
    {
        TeamCouncil.Call[] memory stop = _stopRides(p, false);
        c = new TeamCouncil.Call[](stop.length + 1);
        c[0] = TeamCouncil.Call(address(p.releases), abi.encodeCall(p.releases.revoke, (release)));
        for (uint256 i; i < stop.length; ++i) {
            c[i + 1] = stop[i];
        }
    }

    /// @notice Publishes the release made of this protocol's code contracts and these account contracts, while
    /// the deployer is still the guardian. A real network publishes through the board instead, once the APKs
    /// are built and their hashes known.
    function _publishRelease(Protocol memory p, uint48 version, address factory, address paymaster) internal {
        bytes32 code = p.releases
            .contractsHash(
                address(p.escrow),
                address(p.court),
                address(p.feeVault),
                address(p.registry),
                factory,
                paymaster
            );
        _guardianCall(
            p, address(p.releases), abi.encodeCall(p.releases.publish, (version, code, new bytes32[](0)))
        );
    }

    /// @notice The playbook that stops new rides (no one may open or accept), or the one that puts the policies
    /// back as they are now. Both are the exact calls, stored by hash: who runs one chooses nothing in it.
    function _stopRides(Protocol memory p, bool resume) internal view returns (TeamCouncil.Call[] memory c) {
        PBACGovernor g = p.governor;
        PBACGovernor.Policy memory openPolicy;
        PBACGovernor.Policy memory acceptPolicy;
        if (resume) {
            openPolicy = g.policy(address(p.escrow), RideEscrow.open.selector);
            acceptPolicy = g.policy(address(p.escrow), RideEscrow.accept.selector);
        }
        c = new TeamCouncil.Call[](2);
        c[0] = TeamCouncil.Call(
            address(g), abi.encodeCall(g.setPolicy, (address(p.escrow), RideEscrow.open.selector, openPolicy))
        );
        c[1] = TeamCouncil.Call(
            address(g),
            abi.encodeCall(g.setPolicy, (address(p.escrow), RideEscrow.accept.selector, acceptPolicy))
        );
    }

    function _direct(uint8 role, address target, bytes4 selector, uint8 approvals, uint32 delay)
        internal
        pure
        returns (TeamCouncil.Permission memory)
    {
        return _perm(role, false, target, selector, 0, 0, approvals, delay);
    }

    function _directArg(
        uint8 role,
        address target,
        bytes4 selector,
        uint16 argWord,
        uint256 argValue,
        uint8 approvals,
        uint32 delay
    ) internal pure returns (TeamCouncil.Permission memory) {
        return _perm(role, false, target, selector, argWord, bytes32(argValue), approvals, delay);
    }

    function _viaGov(uint8 role, address target, bytes4 selector, uint8 approvals, uint32 delay)
        internal
        pure
        returns (TeamCouncil.Permission memory)
    {
        return _perm(role, true, target, selector, 0, 0, approvals, delay);
    }

    function _viaGovArg(
        uint8 role,
        address target,
        bytes4 selector,
        uint16 argWord,
        uint256 argValue,
        uint8 approvals,
        uint32 delay
    ) internal pure returns (TeamCouncil.Permission memory) {
        return _perm(role, true, target, selector, argWord, bytes32(argValue), approvals, delay);
    }

    function _perm(
        uint8 role,
        bool viaGovernor,
        address target,
        bytes4 selector,
        uint16 argWord,
        bytes32 argValue,
        uint8 approvals,
        uint32 delay
    ) internal pure returns (TeamCouncil.Permission memory) {
        return TeamCouncil.Permission({
            role: role,
            kind: TeamCouncil.Kind.Call,
            viaGovernor: viaGovernor,
            active: true,
            approvals: approvals,
            delay: delay,
            target: target,
            selector: selector,
            argWord: argWord,
            argValue: argValue
        });
    }
}
