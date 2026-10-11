// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Test} from "forge-std/Test.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {ProtocolDeployer} from "../script/ProtocolDeployer.sol";
import {PBACGovernor} from "../src/PBACGovernor.sol";
import {RideEscrow} from "../src/RideEscrow.sol";
import {DisputeCourt} from "../src/DisputeCourt.sol";
import {MapRegistry} from "../src/MapRegistry.sol";
import {FareGuide} from "../src/FareGuide.sol";
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
    bytes32 constant RECEIPT = keccak256("receipt");

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
                proposerMinAccountAge: 0,
                mapRebuildQuorum: 3,
                mapGracePeriod: 30 days
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
        p.escrow.complete(id, RECEIPT);
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

    function test_TimeoutSettlesWithoutRiderOnceDriverFilesReceipt() public {
        uint256 id = _openAndStart();
        skip(2 hours);
        vm.expectRevert(RideEscrow.NoReceipt.selector);
        p.escrow.timeout(id);

        vm.prank(rider);
        vm.expectRevert(RideEscrow.NotDriver.selector);
        p.escrow.submitReceipt(id, RECEIPT);
        vm.prank(driver);
        p.escrow.submitReceipt(id, RECEIPT);
        vm.expectRevert(RideEscrow.TooEarly.selector);
        p.escrow.timeout(id);
        skip(30 minutes);
        p.escrow.timeout(id);

        RideEscrow.Ride memory r = p.escrow.ride(id);
        assertEq(uint8(r.status), uint8(RideEscrow.Status.Completed));
        assertEq(r.receiptHash, RECEIPT);
    }

    function test_RiderCompletesOnlyWithTheReceiptOnRecord() public {
        uint256 id = _openAndStart();
        vm.prank(rider);
        vm.expectRevert(RideEscrow.NoReceipt.selector);
        p.escrow.complete(id, bytes32(0));

        vm.startPrank(driver);
        p.escrow.submitReceipt(id, RECEIPT);
        vm.expectRevert(RideEscrow.ReceiptMismatch.selector);
        p.escrow.submitReceipt(id, keccak256("a longer trip"));
        vm.stopPrank();

        vm.startPrank(rider);
        vm.expectRevert(RideEscrow.ReceiptMismatch.selector);
        p.escrow.complete(id, keccak256("another receipt"));
        p.escrow.complete(id, RECEIPT);
        vm.stopPrank();
        assertEq(p.escrow.ride(id).receiptHash, RECEIPT);
    }

    function test_DisputeStopsTheTimeout() public {
        uint256 id = _openAndStart();
        vm.prank(driver);
        p.escrow.submitReceipt(id, RECEIPT);
        vm.prank(rider);
        p.court.raise(id, keccak256("evidence"));
        skip(30 minutes);
        vm.expectRevert(abi.encodeWithSelector(RideEscrow.WrongStatus.selector, RideEscrow.Status.Disputed));
        p.escrow.timeout(id);
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

    function test_ChosenDriverCanDeclineBeforeAccepting() public {
        uint256 riderBefore = brz.balanceOf(rider);
        uint256 driverBefore = brz.balanceOf(driver);
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, brz, FARE, keccak256("quote"));

        vm.prank(jurors[0]);
        vm.expectRevert(RideEscrow.NotParty.selector);
        p.escrow.cancel(id);
        vm.prank(driver);
        p.escrow.cancel(id);

        // The rider has the fare and bond back; declining cost the driver nothing.
        assertEq(brz.balanceOf(rider), riderBefore);
        assertEq(brz.balanceOf(driver), driverBefore);
        assertEq(uint8(p.escrow.ride(id).status), uint8(RideEscrow.Status.Cancelled));
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
        p.escrow.complete(id, RECEIPT);
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
        p.escrow.complete(id, RECEIPT);
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

    // ---------------------------------------------------------------- map packs

    bytes32 constant MANIFEST = keccak256("manifest.json of br-pr-cascavel 2026.11");
    bytes32 constant PROOF_ROOT = keccak256("proof root");

    function _pack(bytes32 proofRoot) internal pure returns (MapRegistry.Pack memory) {
        return MapRegistry.Pack({
            region: "br-pr-cascavel",
            version: 202611,
            osmSequence: 4567,
            osmSha256: keccak256("pbf"),
            proofRoot: proofRoot,
            cid: "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
            activeFrom: 0,
            retiredAfter: 0
        });
    }

    /// @dev Runs one call through a proposal, the vote of the three validators and the timelock.
    function _govern(address target, bytes memory call) internal {
        address[] memory targets = new address[](1);
        uint256[] memory values = new uint256[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = target;
        calls[0] = call;
        vm.prank(jurors[0]);
        uint256 id = p.governor.propose(targets, values, calls, "Map pack");
        for (uint256 i; i < 3; ++i) {
            vm.prank(jurors[i]);
            p.governor.vote(id, true);
        }
        skip(2 minutes);
        p.governor.queue(id);
        skip(1 minutes);
        p.governor.execute(id, targets, values, calls);
    }

    function test_PackActivatesOnceAQuorumRebuiltTheSameRoot() public {
        for (uint256 i; i < 2; ++i) {
            vm.prank(jurors[i]);
            p.maps.attest(MANIFEST, PROOF_ROOT);
        }
        // Two rebuilds agree and governance votes yes, but the quorum is three: the proposal cannot execute.
        bytes memory activation = abi.encodeCall(p.maps.activate, (MANIFEST, _pack(PROOF_ROOT)));
        address[] memory targets = new address[](1);
        uint256[] memory values = new uint256[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = address(p.maps);
        calls[0] = activation;
        vm.prank(jurors[0]);
        uint256 id = p.governor.propose(targets, values, calls, "Too early");
        for (uint256 i; i < 3; ++i) {
            vm.prank(jurors[i]);
            p.governor.vote(id, true);
        }
        skip(2 minutes);
        p.governor.queue(id);
        skip(1 minutes);
        vm.expectRevert();
        p.governor.execute(id, targets, values, calls);
        assertEq(p.maps.proofRootOf(MANIFEST), bytes32(0));

        vm.prank(jurors[2]);
        p.maps.attest(MANIFEST, PROOF_ROOT);
        _govern(address(p.maps), activation);

        assertEq(p.maps.proofRootOf(MANIFEST), PROOF_ROOT);
        assertEq(p.maps.latest(keccak256("br-pr-cascavel")), MANIFEST);
        assertTrue(p.maps.isActive(MANIFEST, uint64(block.timestamp)));
        assertEq(p.maps.pack(MANIFEST).version, 202611);
    }

    function test_DisagreeingRebuildsDoNotReachQuorum() public {
        vm.prank(jurors[0]);
        p.maps.attest(MANIFEST, PROOF_ROOT);
        vm.prank(jurors[1]);
        p.maps.attest(MANIFEST, PROOF_ROOT);
        vm.prank(jurors[2]);
        p.maps.attest(MANIFEST, keccak256("a different root"));
        assertEq(p.maps.attestations(MANIFEST, PROOF_ROOT), 2);

        vm.prank(jurors[2]);
        vm.expectRevert(MapRegistry.AlreadyAttested.selector);
        p.maps.attest(MANIFEST, PROOF_ROOT);
        vm.prank(address(p.governor));
        vm.expectRevert(abi.encodeWithSelector(MapRegistry.QuorumNotReached.selector, 2, 3));
        p.maps.activate(MANIFEST, _pack(PROOF_ROOT));
    }

    function test_OnlyStakedValidatorsAttestAndOnlyGovernanceActivates() public {
        vm.prank(driver);
        vm.expectRevert(
            abi.encodeWithSelector(Governed.NotPermitted.selector, driver, MapRegistry.attest.selector)
        );
        p.maps.attest(MANIFEST, PROOF_ROOT);
        vm.expectRevert(Governed.NotGovernor.selector);
        p.maps.activate(MANIFEST, _pack(PROOF_ROOT));
        vm.expectRevert(Governed.NotGovernor.selector);
        p.maps.setRebuildQuorum(1);
    }

    function test_NewPackRetiresTheOldOneAfterTheGracePeriod() public {
        bytes32 next = keccak256("manifest.json of br-pr-cascavel 2026.12");
        for (uint256 i; i < 3; ++i) {
            vm.startPrank(jurors[i]);
            p.maps.attest(MANIFEST, PROOF_ROOT);
            p.maps.attest(next, keccak256("next root"));
            vm.stopPrank();
        }
        vm.startPrank(address(p.governor));
        p.maps.activate(MANIFEST, _pack(PROOF_ROOT));
        vm.expectRevert(MapRegistry.AlreadyActivated.selector);
        p.maps.activate(MANIFEST, _pack(PROOF_ROOT));

        skip(31 days);
        MapRegistry.Pack memory newer = _pack(keccak256("next root"));
        newer.version = 202612;
        p.maps.activate(next, newer);
        vm.stopPrank();

        uint64 nowTs = uint64(block.timestamp);
        assertEq(p.maps.latest(keccak256("br-pr-cascavel")), next);
        assertTrue(p.maps.isActive(MANIFEST, nowTs + 30 days));
        assertFalse(p.maps.isActive(MANIFEST, nowTs + 30 days + 1));
        assertTrue(p.maps.isActive(next, nowTs + 365 days));
        assertFalse(p.maps.isActive(keccak256("never activated"), nowTs));
    }

    function test_GovernanceSetsVerifierTolerances() public {
        (uint32 endpoint, uint32 bps,,,,,,) = p.maps.verifierParams();
        assertEq(endpoint, 50);
        assertEq(bps, 200);

        MapRegistry.VerifierParams memory looser = _launchVerifierParams();
        looser.distanceToleranceBps = 300;
        _govern(address(p.maps), abi.encodeCall(p.maps.setVerifierParams, (looser)));
        (, bps,,,,,,) = p.maps.verifierParams();
        assertEq(bps, 300);
    }

    // ---------------------------------------------------------------- fare guide

    function test_RecommendsAPriceFromDistanceAndTime() public view {
        // 8,5 km and 22 min: R$ 3,00 + R$ 17,00 + R$ 5,50.
        assertEq(p.fares.recommended(8_500, 1_320), 25_50);
        // Rounded to the nearest R$ 0,50: 1,5 km and 2 min is R$ 6,50, under the R$ 8,00 minimum.
        assertEq(p.fares.recommended(1_500, 120), 8_00);
        assertEq(p.fares.recommended(4_000, 300), 12_50); // 3,00 + 8,00 + 1,25 = 12,25
        (uint256 lowest, uint256 highest) = p.fares.riderRange(8_500, 1_320);
        assertEq(lowest, 22_95);
        assertEq(highest, 28_05);
        assertEq(p.fares.driverFloor(25_00), 20_00);
        assertEq(p.fares.driverCeiling(8_500, 1_320), 28_05);
    }

    function test_GovernanceSetsTheTariff() public {
        FareGuide.Tariff memory dearer = _launchTariff();
        dearer.perKmCentavos = 250;
        vm.expectRevert(Governed.NotGovernor.selector);
        p.fares.setTariff(dearer);
        _govern(address(p.fares), abi.encodeCall(p.fares.setTariff, (dearer)));
        assertEq(p.fares.recommended(8_500, 1_320), 30_00); // 3,00 + 21,25 + 5,50 = 29,75

        dearer.riderRangeBps = 10_000;
        vm.prank(address(p.governor));
        vm.expectRevert(FareGuide.InvalidTariff.selector);
        p.fares.setTariff(dearer);
    }
}
