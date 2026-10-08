// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Test} from "forge-std/Test.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {ProtocolDeployer} from "../script/ProtocolDeployer.sol";
import {PBACGovernor} from "../src/PBACGovernor.sol";
import {RideEscrow} from "../src/RideEscrow.sol";
import {DisputeCourt} from "../src/DisputeCourt.sol";
import {Governed} from "../src/Governed.sol";
import {MockBRL} from "../src/mocks/MockBRL.sol";

contract ProtocolTest is Test, ProtocolDeployer {
    MockBRL brla;
    MockBRL brz;
    Protocol p;

    address institute = makeAddr("institutoKlippel");
    address attester = makeAddr("attester");
    address rider = makeAddr("rider");
    address driver = makeAddr("driver");
    address[3] jurors = [makeAddr("juror0"), makeAddr("juror1"), makeAddr("juror2")];

    uint256 constant FARE = 23_4000; // R$ 23,40 in BRZ (4 decimals)

    function setUp() public {
        brla = new MockBRL("BRLA", "BRLA", 18);
        brz = new MockBRL("BRZ", "BRZ", 4);

        TokenInit[] memory tokens = new TokenInit[](2);
        tokens[0] = TokenInit(IERC20(address(brla)), 18);
        tokens[1] = TokenInit(IERC20(address(brz)), 4);
        address[] memory attesters = new address[](1);
        attesters[0] = attester;

        p = _deployProtocol(
            Config({
                stakeToken: IERC20(address(brla)),
                stakeDecimals: 18,
                tokens: tokens,
                feeBeneficiary: institute,
                attesters: attesters,
                unbondingPeriod: 1 days,
                completionTimeout: 30 minutes,
                commitPeriod: 10 minutes,
                revealPeriod: 10 minutes,
                params: PBACGovernor.Params({
                    votingPeriod: 2 minutes,
                    timelockDelay: 1 minutes,
                    quorum: 3,
                    rideWeight: 1,
                    stakeUnit: 100e18
                }),
                driverMinStakeBrl: 100,
                validatorMinStakeBrl: 500,
                driverMinScore: 450,
                proposerMinAccountAge: 0
            })
        );

        _fund(rider);
        _fund(driver);
        vm.prank(rider);
        p.registry.register(1);

        vm.startPrank(driver);
        p.registry.register(2);
        brla.approve(address(p.registry), 100e18);
        p.registry.addStake(100e18);
        vm.stopPrank();
        vm.prank(attester);
        p.registry.attest(driver, CREDENTIAL_CNH, keccak256("cnh"), 0);

        for (uint256 i; i < 3; ++i) {
            _fund(jurors[i]);
            vm.startPrank(jurors[i]);
            p.registry.register(4);
            brla.approve(address(p.registry), 500e18);
            p.registry.addStake(500e18);
            vm.stopPrank();
        }
    }

    function _fund(address who) internal {
        brla.mint(who, 1_000e18);
        brz.mint(who, 1_000e4);
        vm.startPrank(who);
        brla.approve(address(p.escrow), type(uint256).max);
        brz.approve(address(p.escrow), type(uint256).max);
        vm.stopPrank();
    }

    function _openAndStart() internal returns (uint256 id) {
        vm.prank(rider);
        id = p.escrow.open(driver, brz, FARE, keccak256("quote"));
        vm.startPrank(driver);
        p.escrow.accept(id);
        p.escrow.start(id, keccak256("pickup"));
        vm.stopPrank();
    }

    // ---------------------------------------------------------------- rides

    function test_RidePaysDriverMinusOneRealToInstitute() public {
        uint256 riderBefore = brz.balanceOf(rider);
        uint256 driverBefore = brz.balanceOf(driver);
        uint256 id = _openAndStart();
        vm.prank(rider);
        p.escrow.complete(id);
        p.feeVault.sweep(brz);

        assertEq(brz.balanceOf(rider), riderBefore - FARE);
        assertEq(brz.balanceOf(driver), driverBefore + 22_4000); // R$ 22,40
        assertEq(brz.balanceOf(institute), 1_0000); // R$ 1,00
        assertEq(p.reputation.completedRides(driver), 1);
    }

    function test_FeeIsOneRealInEachTokensDecimals() public view {
        (uint256 brlaFee,,) = p.escrow.tokenConfig(brla);
        (uint256 brzFee,,) = p.escrow.tokenConfig(brz);
        assertEq(brlaFee, 1e18);
        assertEq(brzFee, 1e4);
    }

    function test_TimeoutSettlesWithoutRider() public {
        uint256 id = _openAndStart();
        vm.expectRevert(RideEscrow.TooEarly.selector);
        p.escrow.timeout(id);
        skip(30 minutes);
        p.escrow.timeout(id);
        assertEq(uint8(p.escrow.ride(id).status), uint8(RideEscrow.Status.Completed));
    }

    function test_DriverCancelAfterAcceptPaysRiderTheBond() public {
        uint256 before = brz.balanceOf(rider);
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, brz, FARE, keccak256("quote"));
        vm.startPrank(driver);
        p.escrow.accept(id);
        p.escrow.cancel(id);
        vm.stopPrank();
        assertEq(brz.balanceOf(rider), before + 5_0000); // driver bond R$ 5,00
    }

    function test_RejectsTokenNotOnAllowList() public {
        MockBRL other = new MockBRL("X", "X", 18);
        vm.prank(rider);
        vm.expectRevert(RideEscrow.TokenNotAllowed.selector);
        p.escrow.open(driver, other, 10e18, bytes32(0));
    }

    function test_RatingOnceByPartiesOnly() public {
        uint256 id = _openAndStart();
        vm.prank(rider);
        p.escrow.complete(id);
        vm.prank(rider);
        p.reputation.rate(id, 4);
        vm.prank(rider);
        vm.expectRevert();
        p.reputation.rate(id, 5);
        vm.prank(jurors[0]);
        vm.expectRevert();
        p.reputation.rate(id, 1);
        assertEq(p.reputation.scoreOf(driver), 400);
    }

    // ---------------------------------------------------------------- PBAC

    function test_DriverNeedsCredentialToAccept() public {
        vm.prank(attester);
        p.registry.revoke(driver, CREDENTIAL_CNH);
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, brz, FARE, keccak256("quote"));
        vm.prank(driver);
        vm.expectRevert(
            abi.encodeWithSelector(Governed.NotPermitted.selector, driver, RideEscrow.accept.selector)
        );
        p.escrow.accept(id);
    }

    function test_DriverNeedsStakeToAccept() public {
        vm.prank(driver);
        p.registry.requestUnstake(1e18);
        assertFalse(p.governor.hasPermission(driver, address(p.escrow), RideEscrow.accept.selector));
    }

    function test_LowScoreDriverIsBlocked() public {
        uint256 id = _openAndStart();
        vm.prank(rider);
        p.escrow.complete(id);
        vm.prank(rider);
        p.reputation.rate(id, 3);
        assertFalse(p.governor.hasPermission(driver, address(p.escrow), RideEscrow.accept.selector));
    }

    function test_NonRiderCannotOpen() public {
        vm.prank(jurors[0]);
        vm.expectRevert(
            abi.encodeWithSelector(Governed.NotPermitted.selector, jurors[0], RideEscrow.open.selector)
        );
        p.escrow.open(driver, brz, FARE, bytes32(0));
    }

    function test_BootstrapIsClosed() public {
        assertEq(p.governor.bootstrapper(), address(0));
        vm.expectRevert(PBACGovernor.NotBootstrapper.selector);
        p.governor
            .bootstrapCall(address(p.feeVault), abi.encodeCall(p.feeVault.setBeneficiary, (address(this))));
        vm.expectRevert(Governed.NotGovernor.selector);
        p.feeVault.setBeneficiary(address(this));
    }

    // ---------------------------------------------------------------- governance

    function test_GovernanceChangesFeeBeneficiary() public {
        address newBeneficiary = makeAddr("newBeneficiary");
        address[] memory targets = new address[](1);
        uint256[] memory values = new uint256[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = address(p.feeVault);
        calls[0] = abi.encodeCall(p.feeVault.setBeneficiary, (newBeneficiary));

        vm.prank(jurors[0]);
        uint256 id = p.governor.propose(targets, values, calls, "Move fees");
        for (uint256 i; i < 3; ++i) {
            vm.prank(jurors[i]);
            p.governor.vote(id, true); // 5 votes each from R$ 500 stake
        }
        vm.expectRevert(PBACGovernor.VotingOpen.selector);
        p.governor.queue(id);
        skip(2 minutes);
        p.governor.queue(id);
        vm.expectRevert(PBACGovernor.TimelockActive.selector);
        p.governor.execute(id, targets, values, calls);
        skip(1 minutes);
        p.governor.execute(id, targets, values, calls);
        assertEq(p.feeVault.beneficiary(), newBeneficiary);
    }

    function test_ProposalWithoutQuorumIsDefeated() public {
        address[] memory targets = new address[](1);
        uint256[] memory values = new uint256[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = address(p.feeVault);
        calls[0] = abi.encodeCall(p.feeVault.setBeneficiary, (rider));

        vm.prank(rider);
        uint256 id = p.governor.propose(targets, values, calls, "Pay me");
        vm.prank(driver);
        p.governor.vote(id, true); // 1 vote from R$ 100 stake
        skip(2 minutes);
        vm.expectRevert(PBACGovernor.Defeated.selector);
        p.governor.queue(id);
    }

    // ---------------------------------------------------------------- disputes

    function test_DisputeSettlesOnAverageJurorShare() public {
        uint256 id = _openAndStart();
        uint256 riderBefore = brz.balanceOf(rider);
        vm.prank(rider);
        p.court.raise(id, keccak256("evidence"));

        uint256[3] memory votes = [uint256(10_000), 10_000, 4_000]; // average 8_000 bps to rider
        for (uint256 i; i < 3; ++i) {
            vm.prank(jurors[i]);
            p.court.commit(id, keccak256(abi.encode(id, jurors[i], votes[i], bytes32(i))));
        }
        skip(10 minutes);
        for (uint256 i; i < 3; ++i) {
            vm.prank(jurors[i]);
            p.court.reveal(id, votes[i], bytes32(i));
        }
        skip(10 minutes);
        p.court.settle(id);

        // Rider gets 80% of R$ 23,40 = R$ 18,72 back plus the R$ 2,00 rider bond.
        assertEq(brz.balanceOf(rider), riderBefore + 18_7200 + 2_0000);
        assertEq(brz.balanceOf(address(p.feeVault)), 1_0000);
    }

    function test_PartyCannotJudgeOwnRide() public {
        uint256 id = _openAndStart();
        vm.prank(rider);
        p.court.raise(id, keccak256("evidence"));
        // The driver is not a validator, so the policy blocks them first.
        vm.prank(driver);
        vm.expectRevert(
            abi.encodeWithSelector(Governed.NotPermitted.selector, driver, DisputeCourt.commit.selector)
        );
        p.court.commit(id, bytes32(uint256(1)));
    }
}
