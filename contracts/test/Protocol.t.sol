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
import {Reputation} from "../src/Reputation.sol";
import {DataContract} from "../src/DataContract.sol";
import {IdentityStore} from "../src/IdentityStore.sol";
import {IdentityRegistry} from "../src/IdentityRegistry.sol";
import {StakeVault} from "../src/StakeVault.sol";
import {ReleaseRegistry} from "../src/ReleaseRegistry.sol";
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
    address taxman = makeAddr("taxCollector");

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
                guardian: institute,
                feeBeneficiary: institute,
                attesters: attesters,
                unbondingPeriod: 1 days,
                completionTimeout: 30 minutes,
                commitPeriod: 10 minutes,
                revealPeriod: 10 minutes,
                driverMinStakeBrl: 100,
                validatorMinStakeBrl: 500,
                driverMinScore: 450,
                mapRebuildQuorum: 3,
                mapGracePeriod: 30 days,
                // No tax to begin with: the tests that are about it switch it on.
                taxBps: 0,
                taxCollector: address(0),
                nativeCoinCentavos: 250
            })
        );

        _fund(rider);
        _fund(driver);
        vm.prank(rider);
        p.registry.register(1);

        vm.startPrank(driver);
        p.registry.register(2);
        brla.approve(address(p.stakes), 100e18);
        p.stakes.addStake(100e18);
        vm.stopPrank();
        vm.prank(attester);
        p.identities.attest(driver, CREDENTIAL_CNH, keccak256("cnh"), 0);

        for (uint256 i; i < 3; ++i) {
            _fund(jurors[i]);
            vm.startPrank(jurors[i]);
            p.registry.register(4);
            brla.approve(address(p.stakes), 500e18);
            p.stakes.addStake(500e18);
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

    function test_TaxIsAShareOfTheFareTakenFromTheDriver() public {
        vm.prank(address(p.governor));
        p.escrow.setTax(500, taxman); // 5%
        uint256 riderBefore = brz.balanceOf(rider);
        uint256 driverBefore = brz.balanceOf(driver);
        uint256 id = _openAndStart();
        assertEq(p.escrow.ride(id).tax, 1_1700); // 5% of R$ 23,40 = R$ 1,17
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);

        // The rider pays the fare and nothing on top. The driver nets 23,40 - 1,00 - 1,17 = R$ 21,23.
        assertEq(brz.balanceOf(rider), riderBefore - FARE);
        assertEq(brz.balanceOf(driver), driverBefore + 21_2300);
        assertEq(brz.balanceOf(taxman), 1_1700);
        assertEq(brz.balanceOf(address(p.feeVault)), 1_0000);
        assertEq(brz.balanceOf(address(p.escrow)), 0);
    }

    function test_ARideKeepsTheTaxItOpenedWith() public {
        vm.prank(address(p.governor));
        p.escrow.setTax(500, taxman);
        uint256 id = _openAndStart();
        // Governance doubles the tax and names another collector while the ride is under way.
        vm.prank(address(p.governor));
        p.escrow.setTax(1_000, makeAddr("anotherCollector"));
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);

        // The amount and the collector are the ones the ride opened with.
        assertEq(brz.balanceOf(taxman), 1_1700);
        assertEq(brz.balanceOf(makeAddr("anotherCollector")), 0);
        assertEq(brz.balanceOf(address(p.escrow)), 0);
    }

    function test_SwitchingTheTaxOffDoesNotLockRidesUnderWay() public {
        vm.prank(address(p.governor));
        p.escrow.setTax(500, taxman);
        uint256 id = _openAndStart();
        vm.prank(address(p.governor));
        p.escrow.setTax(0, address(0));
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);

        assertEq(brz.balanceOf(taxman), 1_1700);
        assertEq(brz.balanceOf(address(p.escrow)), 0);
        // A ride opened after that carries no tax.
        uint256 next = _openAndStart();
        assertEq(p.escrow.ride(next).tax, 0);
    }

    function test_AFareOfExactlyFeePlusTaxIsRefusedAndOneUnitMoreIsNot() public {
        vm.prank(address(p.governor));
        p.escrow.setTax(5_000, taxman); // 50%
        vm.startPrank(rider);
        brz.approve(address(p.escrow), type(uint256).max);
        // R$ 2,00: tax 1,00 and fee 1,00 take all of it.
        vm.expectRevert(RideEscrow.FareTooLow.selector);
        p.escrow.open(driver, brz, 2_0000, keccak256("quote"));
        p.escrow.open(driver, brz, 2_0002, keccak256("quote"));
        vm.stopPrank();
    }

    /// @dev Whatever the court decides and whatever the tax, a dispute pays out exactly what was locked: the
    /// rider's share whole, tax only on what the driver keeps, the fee only when the rest covers it.
    function testFuzz_ADisputePaysOutExactlyWhatWasLocked(uint16 taxBps, uint256 fare, uint16 riderBps)
        public
    {
        taxBps = uint16(bound(taxBps, 0, 9_999));
        riderBps = uint16(bound(riderBps, 0, 10_000));
        fare = bound(fare, 1, 500_0000);
        vm.prank(address(p.governor));
        p.escrow.setTax(taxBps, taxman);
        uint256 tax = fare * taxBps / 10_000;
        vm.assume(fare > 1_0000 + tax);

        vm.prank(rider);
        brz.approve(address(p.escrow), type(uint256).max);
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, brz, fare, keccak256("quote"));
        vm.startPrank(driver);
        brz.approve(address(p.escrow), type(uint256).max);
        p.escrow.accept(id);
        vm.stopPrank();
        uint256 riderBefore = brz.balanceOf(rider);
        uint256 driverBefore = brz.balanceOf(driver);
        uint256 vaultBefore = brz.balanceOf(address(p.feeVault));

        vm.prank(address(p.court));
        p.escrow.freeze(id);
        vm.prank(address(p.court));
        p.escrow.resolve(id, riderBps);

        uint256 riderPart = fare * riderBps / 10_000;
        uint256 driverPart = fare - riderPart;
        uint256 taxPaid = tax * driverPart / fare;
        uint256 feePaid = driverPart - taxPaid >= 1_0000 ? 1_0000 : 0;
        assertEq(brz.balanceOf(address(p.escrow)), 0);
        assertEq(brz.balanceOf(rider), riderBefore + riderPart + 2_0000);
        assertEq(brz.balanceOf(taxman), taxPaid);
        assertEq(brz.balanceOf(address(p.feeVault)), vaultBefore + feePaid);
        assertEq(brz.balanceOf(driver), driverBefore + driverPart - taxPaid - feePaid + 5_0000);
        // With nothing to the rider, a dispute pays what an undisputed ride would have.
        if (riderBps == 0) assertEq(taxPaid, tax);
        // With everything to the rider, nobody else is paid.
        if (riderBps == 10_000) assertEq(taxPaid + feePaid, 0);
    }

    function test_OnlyGovernanceSetsTheTaxAndItIsBounded() public {
        vm.expectRevert(Governed.NotGovernor.selector);
        p.escrow.setTax(500, taxman);

        vm.startPrank(address(p.governor));
        vm.expectRevert(RideEscrow.InvalidBps.selector);
        p.escrow.setTax(10_000, taxman);
        vm.expectRevert(RideEscrow.NoTaxCollector.selector);
        p.escrow.setTax(500, address(0));
        p.escrow.setTax(0, address(0)); // off needs no collector
        vm.stopPrank();
        _govern(address(p.escrow), abi.encodeCall(p.escrow.setTax, (500, taxman)));
        assertEq(p.escrow.taxBps(), 500);
        assertEq(p.escrow.taxCollector(), taxman);
    }

    function test_AFareThatFeeAndTaxWouldEatIsRefused() public {
        vm.prank(address(p.governor));
        p.escrow.setTax(9_000, taxman); // 90%
        vm.startPrank(rider);
        brz.approve(address(p.escrow), type(uint256).max);
        // R$ 9,00: tax 8,10 and fee 1,00 leave nothing.
        vm.expectRevert(RideEscrow.FareTooLow.selector);
        p.escrow.open(driver, brz, 9_0000, keccak256("quote"));
        vm.stopPrank();
    }

    function test_DisputeTaxesOnlyWhatTheDriverKeeps() public {
        vm.prank(address(p.governor));
        p.escrow.setTax(500, taxman);
        uint256 id = _openAndStart();
        uint256 riderBefore = brz.balanceOf(rider);
        uint256 driverBefore = brz.balanceOf(driver);
        vm.prank(rider);
        p.court.raise(id, keccak256("evidence"));
        // One juror, half each.
        vm.prank(jurors[0]);
        p.court.commit(id, keccak256(abi.encode(id, jurors[0], uint256(5_000), bytes32(0))));
        skip(10 minutes);
        vm.prank(jurors[0]);
        p.court.reveal(id, 5_000, bytes32(0));
        skip(10 minutes);
        p.court.settle(id);

        // Half of R$ 23,40 each. The rider's half comes back whole, with the bond. The driver's R$ 11,70 pays
        // 5% tax (R$ 0,585) and the R$ 1,00 fee, and their R$ 5,00 bond comes back.
        assertEq(brz.balanceOf(rider), riderBefore + 11_7000 + 2_0000);
        assertEq(brz.balanceOf(taxman), 5850);
        assertEq(brz.balanceOf(driver), driverBefore + 11_7000 - 5850 - 1_0000 + 5_0000);
        assertEq(brz.balanceOf(address(p.escrow)), 0);
    }

    function test_GovernanceSetsWhatANativeCoinIsWorth() public {
        // 683 000 gas at 30 gwei is 0,02049 of a coin; at R$ 2,50 a coin that is 5 centavos, rounded down.
        assertEq(p.fares.infrastructureCost(683_000, 30 gwei), 5);
        vm.expectRevert(Governed.NotGovernor.selector);
        p.fares.setNativeCoinPrice(500);
        _govern(address(p.fares), abi.encodeCall(p.fares.setNativeCoinPrice, (500)));
        assertEq(p.fares.infrastructureCost(683_000, 30 gwei), 10);
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

    function _rider(string memory name) internal returns (address who) {
        who = makeAddr(name);
        _fund(who);
        vm.prank(who);
        p.registry.register(1);
    }

    function _openFor(address who, bytes32 quote) internal returns (uint256 id) {
        vm.prank(who);
        id = p.escrow.open(driver, brz, FARE, quote);
    }

    function test_ADriverTakesTheNextRideOnlyOnceTheFirstRiderIsIn() public {
        address bia = _rider("bia");
        address caio = _rider("caio");
        uint256 a = _openFor(rider, keccak256("a"));
        uint256 b = _openFor(bia, keccak256("b"));
        uint256 c = _openFor(caio, keccak256("c"));

        // Anyone may lock a ride naming the driver; the driver takes one at a time to its pickup.
        vm.startPrank(driver);
        p.escrow.accept(a);
        vm.expectRevert(abi.encodeWithSelector(RideEscrow.DriverBusy.selector, a));
        p.escrow.accept(b);

        // Ana is in the car: Bia's ride is the next one.
        p.escrow.start(a, keccak256("pickup a"));
        p.escrow.accept(b);
        assertEq(p.escrow.onTripOf(driver), a);
        assertEq(p.escrow.pickupOf(driver), b);

        // Two in hand: no third, and Bia is picked up only once Ana is out.
        vm.expectRevert(abi.encodeWithSelector(RideEscrow.DriverBusy.selector, b));
        p.escrow.accept(c);
        vm.expectRevert(abi.encodeWithSelector(RideEscrow.DriverBusy.selector, a));
        p.escrow.start(b, keccak256("pickup b"));
        vm.stopPrank();

        vm.prank(rider);
        p.escrow.complete(a, RECEIPT);
        assertEq(p.escrow.onTripOf(driver), 0);
        vm.startPrank(driver);
        p.escrow.start(b, keccak256("pickup b"));
        p.escrow.accept(c);
        vm.stopPrank();
        assertEq(uint8(p.escrow.ride(c).status), uint8(RideEscrow.Status.Accepted));
    }

    function test_ARideThatLeavesTheDriversHandsFreesItsPlace() public {
        address bia = _rider("bia");

        // The rider waiting for pickup withdraws.
        uint256 a = _openFor(rider, keccak256("a"));
        uint256 b = _openFor(bia, keccak256("b"));
        vm.prank(driver);
        p.escrow.accept(a);
        vm.prank(rider);
        p.escrow.cancel(a);
        assertEq(p.escrow.pickupOf(driver), 0);

        // The driver gives up the one they were on the way to.
        vm.startPrank(driver);
        p.escrow.accept(b);
        p.escrow.cancel(b);
        vm.stopPrank();
        assertEq(p.escrow.pickupOf(driver), 0);

        // A ride in dispute is frozen, whether waiting for pickup or under way, and takes no place.
        uint256 c = _openFor(rider, keccak256("c"));
        vm.prank(driver);
        p.escrow.accept(c);
        vm.prank(rider);
        p.court.raise(c, keccak256("evidence"));
        assertEq(p.escrow.pickupOf(driver), 0);
        uint256 d = _openAndStart();
        vm.prank(rider);
        p.court.raise(d, keccak256("evidence"));
        assertEq(p.escrow.onTripOf(driver), 0);

        // A driver's receipt that stood for the timeout ends the ride under way too.
        uint256 e = _openAndStart();
        vm.prank(driver);
        p.escrow.submitReceipt(e, RECEIPT);
        skip(30 minutes);
        p.escrow.timeout(e);
        assertEq(p.escrow.onTripOf(driver), 0);
        _openAndStart();
    }

    function test_RidesNamingABusyDriverWaitAndAreGivenBackWhole() public {
        uint256 a = _openAndStart();
        uint256 b = _openFor(rider, keccak256("b"));
        vm.prank(driver);
        p.escrow.accept(b);

        // More riders lock this driver while both places are taken: nothing stops them, and nothing is lost.
        uint256 before = brz.balanceOf(rider);
        uint256 c = _openFor(rider, keccak256("c"));
        vm.prank(driver);
        vm.expectRevert(abi.encodeWithSelector(RideEscrow.DriverBusy.selector, b));
        p.escrow.accept(c);
        vm.prank(driver);
        p.escrow.cancel(c);
        assertEq(brz.balanceOf(rider), before);
        assertEq(p.escrow.onTripOf(driver), a);
        assertEq(p.escrow.pickupOf(driver), b);
    }

    function test_ADeclineThatArrivesAfterTheAcceptChangesNothing() public {
        uint256 riderBefore = brz.balanceOf(rider);
        uint256 id = _openFor(rider, keccak256("a"));
        vm.prank(jurors[0]);
        vm.expectRevert(RideEscrow.NotDriver.selector);
        p.escrow.decline(id);

        // The phone's decline was sent, then the driver tapped accept, and the accept landed first.
        vm.startPrank(driver);
        p.escrow.accept(id);
        vm.expectRevert(abi.encodeWithSelector(RideEscrow.WrongStatus.selector, RideEscrow.Status.Accepted));
        p.escrow.decline(id);
        vm.stopPrank();
        assertEq(uint8(p.escrow.ride(id).status), uint8(RideEscrow.Status.Accepted));
        assertEq(p.escrow.pickupOf(driver), id);

        // Declined in time, the rider has it all back.
        uint256 other = _openFor(rider, keccak256("b"));
        vm.prank(driver);
        p.escrow.decline(other);
        assertEq(uint8(p.escrow.ride(other).status), uint8(RideEscrow.Status.Cancelled));
        assertEq(brz.balanceOf(rider), riderBefore - FARE - 2_0000);
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
        p.reputation.rate(address(p.escrow), id, 4);
        vm.prank(rider);
        vm.expectRevert();
        p.reputation.rate(address(p.escrow), id, 5);
        vm.prank(jurors[0]);
        vm.expectRevert();
        p.reputation.rate(address(p.escrow), id, 1);
        assertEq(p.reputation.scoreOf(driver), 400);
    }

    // ---------------------------------------------------------------- PBAC

    function test_DriverNeedsCredentialToAccept() public {
        vm.prank(attester);
        p.identities.revoke(driver, CREDENTIAL_CNH);
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, brz, FARE, keccak256("quote"));
        vm.prank(driver);
        vm.expectRevert(
            abi.encodeWithSelector(Governed.NotPermitted.selector, driver, RideEscrow.accept.selector)
        );
        p.escrow.accept(id);
    }

    function test_AnAttesterCannotTakeOverAnothersValidCredential() public {
        address second = makeAddr("secondAttester");
        vm.prank(address(p.governor));
        p.identities.setAttester(second, true);

        vm.prank(second);
        vm.expectRevert(IdentityStore.NotIssuer.selector);
        p.identities.attest(driver, CREDENTIAL_CNH, keccak256("other"), 0);
        vm.prank(second);
        vm.expectRevert(IdentityStore.NotIssuer.selector);
        p.identities.revoke(driver, CREDENTIAL_CNH);

        // The issuer renews its own, and once it is revoked anyone trusted may issue a new one.
        vm.startPrank(attester);
        p.identities.attest(driver, CREDENTIAL_CNH, keccak256("renewed"), 0);
        p.identities.revoke(driver, CREDENTIAL_CNH);
        vm.stopPrank();
        vm.prank(second);
        p.identities.attest(driver, CREDENTIAL_CNH, keccak256("other"), 0);
        assertTrue(p.identities.hasCredential(driver, CREDENTIAL_CNH));
    }

    function test_DriverNeedsStakeToAccept() public {
        vm.prank(driver);
        p.stakes.requestUnstake(1e18);
        assertFalse(p.governor.hasPermission(driver, address(p.escrow), RideEscrow.accept.selector));
    }

    function test_LowScoreDriverIsBlocked() public {
        uint256 id = _openAndStart();
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);
        vm.prank(rider);
        p.reputation.rate(address(p.escrow), id, 3);
        assertFalse(p.governor.hasPermission(driver, address(p.escrow), RideEscrow.accept.selector));
    }

    function test_ABlockedAccountPassesNoPolicy() public {
        vm.expectRevert(Governed.NotGovernor.selector);
        p.identities.setBlocked(rider, true);

        _govern(address(p.identities), abi.encodeCall(p.identities.setBlocked, (rider, true)));
        assertTrue(p.identities.blocked(rider));
        vm.prank(rider);
        vm.expectRevert(
            abi.encodeWithSelector(Governed.NotPermitted.selector, rider, RideEscrow.open.selector)
        );
        p.escrow.open(driver, brz, FARE, keccak256("quote"));

        // A blocked driver with stake, score and CNH in order cannot accept.
        _govern(address(p.identities), abi.encodeCall(p.identities.setBlocked, (driver, true)));
        assertFalse(p.governor.hasPermission(driver, address(p.escrow), RideEscrow.accept.selector));

        _govern(address(p.identities), abi.encodeCall(p.identities.setBlocked, (rider, false)));
        vm.prank(rider);
        p.escrow.open(driver, brz, FARE, keccak256("quote"));
    }

    function test_BlockingLeavesRidesUnderWayAndStakeAlone() public {
        uint256 id = _openAndStart();
        // The next ride, accepted while the first rider is in the car.
        uint256 second = _openFor(rider, keccak256("second"));
        vm.prank(driver);
        p.escrow.accept(second);
        vm.prank(rider);
        uint256 third = p.escrow.open(driver, brz, FARE, keccak256("quote"));
        _govern(address(p.identities), abi.encodeCall(p.identities.setBlocked, (rider, true)));
        _govern(address(p.identities), abi.encodeCall(p.identities.setBlocked, (driver, true)));

        // Both blocked, and the ride still settles: the driver is paid the fare less the fee, and the bond.
        uint256 driverBefore = brz.balanceOf(driver);
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);
        assertEq(brz.balanceOf(driver), driverBefore + 22_4000 + 5_0000);

        // The chosen driver, who can no longer accept, declines, and the rider is refunded.
        vm.prank(driver);
        p.escrow.cancel(third);
        assertEq(uint8(p.escrow.ride(third).status), uint8(RideEscrow.Status.Cancelled));

        // The next ride is still theirs to drive, and a receipt and a dispute still theirs to file.
        vm.startPrank(driver);
        p.escrow.start(second, keccak256("pickup"));
        p.escrow.submitReceipt(second, RECEIPT);
        vm.stopPrank();
        vm.prank(rider);
        p.court.raise(second, keccak256("evidence"));
        assertEq(uint8(p.escrow.ride(second).status), uint8(RideEscrow.Status.Disputed));

        // The stake leaves the usual way.
        uint256 brlaBefore = brla.balanceOf(driver);
        vm.startPrank(driver);
        p.stakes.requestUnstake(100e18);
        skip(1 days);
        p.stakes.withdrawStake();
        vm.stopPrank();
        assertEq(brla.balanceOf(driver), brlaBefore + 100e18);
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

    function test_OnlyTheGuardianGoverns() public {
        address newBeneficiary = makeAddr("newBeneficiary");
        address[] memory targets = new address[](2);
        bytes[] memory calls = new bytes[](2);
        targets[0] = address(p.feeVault);
        calls[0] = abi.encodeCall(p.feeVault.setBeneficiary, (newBeneficiary));
        targets[1] = address(p.escrow);
        calls[1] = abi.encodeCall(p.escrow.setCompletionTimeout, (45 minutes));

        // Not the deployer, not a validator with stake, not a contract of the protocol.
        vm.expectRevert(PBACGovernor.NotGuardian.selector);
        p.governor.execute(targets, calls);
        vm.prank(jurors[0]);
        vm.expectRevert(PBACGovernor.NotGuardian.selector);
        p.governor.execute(targets, calls);

        // The guardian, at once, and both calls or neither.
        vm.prank(institute);
        p.governor.execute(targets, calls);
        assertEq(p.feeVault.beneficiary(), newBeneficiary);
        assertEq(p.escrow.completionTimeout(), 45 minutes);

        calls[1] = abi.encodeCall(p.escrow.setTax, (10_000, taxman)); // refused by the escrow
        calls[0] = abi.encodeCall(p.feeVault.setBeneficiary, (institute));
        vm.prank(institute);
        vm.expectRevert();
        p.governor.execute(targets, calls);
        assertEq(p.feeVault.beneficiary(), newBeneficiary);

        vm.prank(institute);
        vm.expectRevert(PBACGovernor.InvalidCalls.selector);
        p.governor.execute(new address[](0), new bytes[](0));
    }

    function test_TheGuardianIsHandedOverOnlyByTheGuardianAndOnlyOnceAccepted() public {
        address next = makeAddr("nextGuardian");
        vm.prank(jurors[0]);
        vm.expectRevert(PBACGovernor.NotGuardian.selector);
        p.governor.proposeGuardian(jurors[0]);

        vm.prank(institute);
        p.governor.proposeGuardian(next);
        // Naming a successor changes nothing yet, and nobody else can accept in their place.
        assertEq(p.governor.guardian(), institute);
        vm.prank(jurors[0]);
        vm.expectRevert(PBACGovernor.NotPendingGuardian.selector);
        p.governor.acceptGuardian();

        vm.prank(next);
        p.governor.acceptGuardian();
        assertEq(p.governor.guardian(), next);
        assertEq(p.governor.pendingGuardian(), address(0));

        // The old guardian governs nothing, and the offer cannot be accepted twice.
        address[] memory targets = new address[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = address(p.identities);
        calls[0] = abi.encodeCall(p.identities.setBlocked, (rider, true));
        vm.prank(institute);
        vm.expectRevert(PBACGovernor.NotGuardian.selector);
        p.governor.execute(targets, calls);
        vm.prank(next);
        vm.expectRevert(PBACGovernor.NotPendingGuardian.selector);
        p.governor.acceptGuardian();
    }

    function test_AGovernorNeedsAGuardian() public {
        vm.expectRevert(PBACGovernor.NoGuardian.selector);
        new PBACGovernor(address(0));
    }

    function test_TheGovernorCannotBeItsOwnGuardian() public {
        // It could accept through execute, and then nobody could execute again.
        vm.prank(institute);
        vm.expectRevert(PBACGovernor.NoGuardian.selector);
        p.governor.proposeGuardian(address(p.governor));
    }

    // ---------------------------------------------------------------- patches

    /// @dev What a hotfix of the escrow is: a new escrow and its court, wired in by the guardian in one go,
    /// with new rides stopped on the old one.
    function _patchEscrow() internal returns (RideEscrow next, DisputeCourt nextCourt) {
        next = new RideEscrow(p.governor, 30 minutes);
        nextCourt = new DisputeCourt(p.governor, next, 10 minutes, 10 minutes);
        PBACGovernor g = p.governor;
        address[] memory t = new address[](9);
        bytes[] memory c = new bytes[](9);
        t[0] = address(next);
        c[0] = abi.encodeCall(next.setModules, (p.reputation, address(p.feeVault), address(nextCourt)));
        t[1] = address(next);
        c[1] = abi.encodeCall(next.setToken, (IERC20(address(brz)), 1_0000, 2_0000, 5_0000));
        t[2] = address(p.reputation);
        c[2] = abi.encodeCall(p.reputation.setWriter, (address(next), true));
        t[3] = address(g);
        c[3] = abi.encodeCall(
            g.setPolicy,
            (address(next), RideEscrow.open.selector, g.policy(address(p.escrow), RideEscrow.open.selector))
        );
        t[4] = address(g);
        c[4] = abi.encodeCall(
            g.setPolicy,
            (
                address(next),
                RideEscrow.accept.selector,
                g.policy(address(p.escrow), RideEscrow.accept.selector)
            )
        );
        t[5] = address(g);
        c[5] = abi.encodeCall(
            g.setPolicy,
            (
                address(nextCourt),
                DisputeCourt.commit.selector,
                g.policy(address(p.court), DisputeCourt.commit.selector)
            )
        );
        // The old escrow takes no new ride. What it holds is untouched.
        PBACGovernor.Policy memory closed;
        t[6] = address(g);
        c[6] = abi.encodeCall(g.setPolicy, (address(p.escrow), RideEscrow.open.selector, closed));
        t[7] = address(p.escrow);
        c[7] = abi.encodeCall(p.escrow.setToken, (IERC20(address(brz)), 0, 0, 0));
        t[8] = address(p.escrow);
        c[8] = abi.encodeCall(p.escrow.setToken, (IERC20(address(brla)), 0, 0, 0));
        vm.prank(institute);
        g.execute(t, c);
    }

    function test_APatchedEscrowKeepsStakeLicencesAndReputationAndTheOldOneSettles() public {
        // A ride completed and rated before the patch, and one under way across it.
        uint256 before = _openAndStart();
        vm.prank(rider);
        p.escrow.complete(before, RECEIPT);
        vm.prank(rider);
        p.reputation.rate(address(p.escrow), before, 5);
        uint256 across = _openAndStart();

        (RideEscrow next,) = _patchEscrow();

        // Nobody opens on the old escrow any more.
        vm.prank(rider);
        vm.expectRevert();
        p.escrow.open(driver, brz, FARE, keccak256("quote"));

        // The driver's stake, licence and score are where they were, and let them accept on the new one.
        assertEq(p.stakes.stakeOf(driver), 100e18);
        assertTrue(p.identities.hasCredential(driver, CREDENTIAL_CNH));
        assertEq(p.reputation.scoreOf(driver), 500);
        vm.startPrank(rider);
        brz.approve(address(next), type(uint256).max);
        uint256 id = next.open(driver, brz, FARE, keccak256("quote"));
        vm.stopPrank();
        assertEq(id, 1); // the new escrow counts from one, as the old one did
        vm.startPrank(driver);
        brz.approve(address(next), type(uint256).max);
        next.accept(id);
        next.start(id, keccak256("pickup"));
        vm.stopPrank();
        vm.prank(rider);
        next.complete(id, RECEIPT);

        // The ride under way on the old escrow settles there, and counts.
        uint256 driverBefore = brz.balanceOf(driver);
        vm.prank(rider);
        p.escrow.complete(across, RECEIPT);
        assertEq(brz.balanceOf(driver), driverBefore + 22_4000 + 5_0000);
        assertEq(p.reputation.completedRides(driver), 3);

        // Ride 1 of each escrow is its own ride: rating one leaves the other to be rated.
        vm.prank(rider);
        vm.expectRevert(Reputation.AlreadyRated.selector);
        p.reputation.rate(address(p.escrow), 1, 4);
        vm.prank(rider);
        p.reputation.rate(address(next), 1, 4);
        assertEq(p.reputation.scoreOf(driver), 450);
    }

    function test_AnEscrowTheLedgerNoLongerListensToStillPaysEveryone() public {
        uint256 id = _openAndStart();
        _govern(address(p.reputation), abi.encodeCall(p.reputation.setWriter, (address(p.escrow), false)));
        uint256 driverBefore = brz.balanceOf(driver);
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);
        assertEq(brz.balanceOf(driver), driverBefore + 22_4000 + 5_0000);
        assertEq(uint8(p.escrow.ride(id).status), uint8(RideEscrow.Status.Completed));
        // Paid, but it earned no reputation and cannot be rated.
        assertEq(p.reputation.completedRides(driver), 0);
        vm.prank(rider);
        vm.expectRevert(Reputation.NotParty.selector);
        p.reputation.rate(address(p.escrow), id, 5);
    }

    function test_OnlyAListedEscrowRecordsRides() public {
        vm.expectRevert(DataContract.NotWriter.selector);
        p.reputation.recordCompletion(1, rider, driver);
        vm.expectRevert(Governed.NotGovernor.selector);
        p.reputation.setWriter(address(this), true);
    }

    function test_ARideKeepsTheCourtAndTimeoutItOpenedWith() public {
        uint256 before = _openAndStart();

        // The escrow is given another court and a zero timeout while the ride is under way.
        DisputeCourt nextCourt = new DisputeCourt(p.governor, p.escrow, 10 minutes, 10 minutes);
        _govern(
            address(p.escrow),
            abi.encodeCall(p.escrow.setModules, (p.reputation, address(p.feeVault), address(nextCourt)))
        );
        _govern(address(p.escrow), abi.encodeCall(p.escrow.setCompletionTimeout, (0)));

        // The new court cannot reach it, and the driver cannot settle it before its own 30 minutes.
        vm.prank(address(nextCourt));
        vm.expectRevert(RideEscrow.NotCourt.selector);
        p.escrow.freeze(before);
        vm.prank(driver);
        p.escrow.submitReceipt(before, RECEIPT);
        vm.expectRevert(RideEscrow.TooEarly.selector);
        p.escrow.timeout(before);

        // Its own court still hears it, and settles it.
        vm.prank(rider);
        p.court.raise(before, keccak256("evidence"));
        uint256 riderBefore = brz.balanceOf(rider);
        skip(20 minutes);
        p.court.settle(before);
        assertEq(uint8(p.escrow.ride(before).status), uint8(RideEscrow.Status.Resolved));
        assertEq(brz.balanceOf(rider), riderBefore + FARE / 2 + 2_0000);

        // A ride opened afterwards goes to the new court only.
        uint256 later = _openAndStart();
        assertEq(p.escrow.ride(later).court, address(nextCourt));
        assertEq(p.escrow.ride(later).completionTimeout, 0);
        vm.prank(rider);
        vm.expectRevert(RideEscrow.NotCourt.selector);
        p.court.raise(later, keccak256("evidence"));
        vm.prank(rider);
        nextCourt.raise(later, keccak256("evidence"));
    }

    // ---------------------------------------------------------------- the coins' issuers

    function _issuerCoin() internal returns (IssuerBRL coin) {
        coin = new IssuerBRL();
        _govern(address(p.escrow), abi.encodeCall(p.escrow.setToken, (IERC20(address(coin)), 1_0000, 0, 0)));
        for (uint256 i; i < 2; ++i) {
            address who = i == 0 ? rider : driver;
            coin.mint(who, 1_000e4);
            vm.prank(who);
            coin.approve(address(p.escrow), type(uint256).max);
        }
    }

    function test_AFrozenDriverHoldsUpOnlyTheirOwnPay() public {
        IssuerBRL coin = _issuerCoin();
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, coin, FARE, keccak256("quote"));
        vm.startPrank(driver);
        p.escrow.accept(id);
        p.escrow.start(id, keccak256("pickup"));
        vm.stopPrank();

        coin.freeze(driver, true);
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);

        // The ride settles: the fee is paid, and the driver's share waits for them in the escrow.
        assertEq(uint8(p.escrow.ride(id).status), uint8(RideEscrow.Status.Completed));
        assertEq(coin.balanceOf(address(p.feeVault)), 1_0000);
        assertEq(p.escrow.owed(coin, driver), 22_4000);
        assertEq(p.escrow.held(coin), 22_4000);

        vm.prank(driver);
        vm.expectRevert();
        p.escrow.withdraw(coin, driver);
        coin.freeze(driver, false);
        uint256 driverBefore = coin.balanceOf(driver);
        vm.prank(driver);
        p.escrow.withdraw(coin, driver);
        assertEq(coin.balanceOf(driver), driverBefore + 22_4000);
        assertEq(p.escrow.held(coin), 0);
        vm.prank(driver);
        vm.expectRevert(RideEscrow.NothingOwed.selector);
        p.escrow.withdraw(coin, driver);
    }

    function test_FeesTheVaultCouldNotReceiveReachItOnceItCan() public {
        IssuerBRL coin = _issuerCoin();
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, coin, FARE, keccak256("quote"));
        vm.startPrank(driver);
        p.escrow.accept(id);
        p.escrow.start(id, keccak256("pickup"));
        vm.stopPrank();

        coin.freeze(address(p.feeVault), true);
        vm.prank(rider);
        p.escrow.complete(id, RECEIPT);
        assertEq(p.escrow.owed(coin, address(p.feeVault)), 1_0000);

        // The vault cannot call the escrow itself: anyone sends it what it is owed, and only to it.
        coin.freeze(address(p.feeVault), false);
        vm.prank(makeAddr("anyone"));
        p.escrow.withdraw(coin, address(p.feeVault));
        assertEq(coin.balanceOf(address(p.feeVault)), 1_0000);
        assertEq(p.escrow.owed(coin, address(p.feeVault)), 0);
        assertEq(p.escrow.held(coin), 0);
    }

    function test_ACoinThatChargesOnTransferIsRefused() public {
        IssuerBRL coin = _issuerCoin();
        coin.setTransferFeeBps(100); // the issuer upgrades the coin into one that keeps 1%
        vm.prank(rider);
        vm.expectRevert(RideEscrow.NotPlainToken.selector);
        p.escrow.open(driver, coin, FARE, keccak256("quote"));
    }

    function test_TheEscrowHoldsNoMoreOfACoinThanItsCap() public {
        _govern(address(p.escrow), abi.encodeCall(p.escrow.setCap, (IERC20(address(brz)), 30_0000)));
        vm.prank(rider);
        uint256 id = p.escrow.open(driver, brz, FARE, keccak256("quote")); // R$ 25,40 held
        assertEq(p.escrow.held(brz), 25_4000);

        // The driver's R$ 5,00 bond would take it to R$ 30,40.
        vm.prank(driver);
        vm.expectRevert(RideEscrow.CapReached.selector);
        p.escrow.accept(id);

        // Once the ride is out of the escrow there is room again.
        vm.prank(rider);
        p.escrow.cancel(id);
        assertEq(p.escrow.held(brz), 0);
        _govern(address(p.escrow), abi.encodeCall(p.escrow.setCap, (IERC20(address(brz)), 30_4000)));
        _openAndStart(); // exactly the cap
        assertEq(p.escrow.held(brz), FARE + 2_0000 + 5_0000);

        vm.expectRevert(Governed.NotGovernor.selector);
        p.escrow.setCap(brz, 0);
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

    /// @dev Runs one call the way Instituto Klippel does: as guardian, through the governor.
    function _govern(address target, bytes memory call) internal {
        address[] memory targets = new address[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = target;
        calls[0] = call;
        vm.prank(institute);
        p.governor.execute(targets, calls);
    }

    function test_PackActivatesOnceAQuorumRebuiltTheSameRoot() public {
        for (uint256 i; i < 2; ++i) {
            vm.prank(jurors[i]);
            p.maps.attest(MANIFEST, PROOF_ROOT);
        }
        // Two rebuilds agree, but the quorum is three: not even the guardian can activate the pack.
        bytes memory activation = abi.encodeCall(p.maps.activate, (MANIFEST, _pack(PROOF_ROOT)));
        address[] memory targets = new address[](1);
        bytes[] memory calls = new bytes[](1);
        targets[0] = address(p.maps);
        calls[0] = activation;
        vm.prank(institute);
        vm.expectRevert();
        p.governor.execute(targets, calls);
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

    // ---------------------------------------------------------------- data that outlives releases

    function test_OnlyAListedRegistryAddsRolesAndRolesOnlyGrow() public {
        vm.expectRevert(DataContract.NotWriter.selector);
        p.identities.addRoles(rider, 2);

        uint64 since = p.identities.registeredAt(rider);
        skip(1 days);
        vm.prank(rider);
        p.registry.register(2);
        assertEq(p.identities.rolesOf(rider), 3); // rider and driver: nothing was taken away
        assertEq(p.identities.registeredAt(rider), since); // and account age did not reset

        vm.prank(rider);
        vm.expectRevert(IdentityStore.InvalidRoles.selector);
        p.registry.register(8);
    }

    /// @dev A release replaces the registry: the people, their roles, credentials and stake stay.
    function test_ANewReleasesRegistryWritesTheSameIdentities() public {
        IdentityRegistry next = new IdentityRegistry(p.governor, p.identities);
        address someone = makeAddr("someone");
        vm.prank(someone);
        vm.expectRevert(DataContract.NotWriter.selector);
        next.register(1);

        _govern(address(p.identities), abi.encodeCall(p.identities.setWriter, (address(next), true)));
        _govern(address(p.identities), abi.encodeCall(p.identities.setWriter, (address(p.registry), false)));
        vm.prank(someone);
        next.register(1);
        assertEq(p.identities.rolesOf(someone), 1);
        vm.prank(someone);
        vm.expectRevert(DataContract.NotWriter.selector);
        p.registry.register(2);

        // The driver who registered on the old one still drives.
        assertTrue(p.governor.hasPermission(driver, address(p.escrow), RideEscrow.accept.selector));
    }

    function test_TheStakeVaultIsOutOfGovernancesReach() public {
        // No writer list: nothing can be wired in to move stake.
        (bool ok,) =
            address(p.stakes).call(abi.encodeWithSignature("setWriter(address,bool)", address(this), true));
        assertFalse(ok);
        // The unbonding period is the guardian's, but never long enough to hold stake hostage.
        vm.prank(institute);
        address[] memory t = new address[](1);
        bytes[] memory c = new bytes[](1);
        t[0] = address(p.stakes);
        c[0] = abi.encodeCall(p.stakes.setUnbondingPeriod, (31 days));
        vm.expectRevert();
        p.governor.execute(t, c);
        _govern(address(p.stakes), abi.encodeCall(p.stakes.setUnbondingPeriod, (30 days)));
        assertEq(p.stakes.unbondingPeriod(), 30 days);
    }

    function test_OnlyARegisteredAccountStakes() public {
        address stranger = makeAddr("stranger");
        _fund(stranger);
        vm.startPrank(stranger);
        brla.approve(address(p.stakes), 1e18);
        vm.expectRevert(StakeVault.NotRegistered.selector);
        p.stakes.addStake(1e18);
        vm.stopPrank();
    }

    function test_AnEscrowCannotRecordARideTwice() public {
        _govern(address(p.reputation), abi.encodeCall(p.reputation.setWriter, (address(this), true)));
        p.reputation.recordCompletion(77, rider, driver);
        vm.expectRevert(Reputation.AlreadyRecorded.selector);
        p.reputation.recordCompletion(77, rider, driver);
        assertEq(p.reputation.completedRides(driver), 1);
    }

    // ---------------------------------------------------------------- releases

    uint48 constant V1 = (1 << 32); // 1.0.0

    function _publish(uint48 v, bytes32 code, bytes32[] memory apks) internal {
        _govern(address(p.releases), abi.encodeCall(p.releases.publish, (v, code, apks)));
    }

    function test_AReleaseIsPublishedOnceWithItsApks() public {
        assertEq(p.releases.versionOf(1, 0, 0), V1);
        bytes32[] memory apks = new bytes32[](2);
        apks[0] = sha256("arm64");
        apks[1] = sha256("universal");
        bytes32 code = p.releases
            .contractsHash(
                address(p.escrow),
                address(p.court),
                address(p.feeVault),
                address(p.registry),
                address(1),
                address(2)
            );
        assertEq(
            code,
            keccak256(
                abi.encode(
                    address(p.escrow),
                    address(p.court),
                    address(p.feeVault),
                    address(p.registry),
                    address(1),
                    address(2)
                )
            )
        );

        vm.expectRevert(Governed.NotGovernor.selector);
        p.releases.publish(V1, code, apks);

        _publish(V1, code, apks);
        ReleaseRegistry.Release memory r = p.releases.release(V1);
        assertEq(r.contracts, code);
        assertEq(uint8(r.status), uint8(ReleaseRegistry.Status.Active));
        assertTrue(p.releases.isApk(V1, sha256("arm64")));
        assertFalse(p.releases.isApk(V1, sha256("modified")));
        assertEq(p.releases.latest(), V1);

        // A version names one build forever: a fix is a new version.
        vm.prank(institute);
        address[] memory t = new address[](1);
        bytes[] memory c = new bytes[](1);
        t[0] = address(p.releases);
        c[0] = abi.encodeCall(p.releases.publish, (V1, keccak256("other"), apks));
        vm.expectRevert();
        p.governor.execute(t, c);
    }

    function test_AReleaseMovesFromActiveToDeprecatedToRevokedAndBack() public {
        _publish(V1, keccak256("code"), new bytes32[](0));
        _govern(address(p.releases), abi.encodeCall(p.releases.deprecate, (V1)));
        assertEq(uint8(p.releases.statusOf(V1)), uint8(ReleaseRegistry.Status.Deprecated));
        _govern(address(p.releases), abi.encodeCall(p.releases.revoke, (V1)));
        assertEq(uint8(p.releases.statusOf(V1)), uint8(ReleaseRegistry.Status.Revoked));
        _govern(address(p.releases), abi.encodeCall(p.releases.reinstate, (V1)));
        assertEq(uint8(p.releases.statusOf(V1)), uint8(ReleaseRegistry.Status.Active));

        // An unpublished release has no status to move.
        vm.prank(institute);
        address[] memory t = new address[](1);
        bytes[] memory c = new bytes[](1);
        t[0] = address(p.releases);
        c[0] = abi.encodeCall(p.releases.revoke, (V1 + 1));
        vm.expectRevert();
        p.governor.execute(t, c);
    }

    /// @dev Runs one call as the guardian and expects the governor to fail with the target's `reason`.
    function _governFails(address target, bytes memory call, bytes memory reason) internal {
        address[] memory t = new address[](1);
        bytes[] memory c = new bytes[](1);
        t[0] = target;
        c[0] = call;
        vm.prank(institute);
        vm.expectRevert(abi.encodeWithSelector(PBACGovernor.CallFailed.selector, 0, reason));
        p.governor.execute(t, c);
    }

    function test_AReleaseOnlyMovesAlongItsStatuses() public {
        _publish(V1, keccak256("code"), new bytes32[](0));
        bytes memory active =
            abi.encodeWithSelector(ReleaseRegistry.WrongStatus.selector, ReleaseRegistry.Status.Active);
        // An active release is not put back: there is nothing to put back.
        _governFails(address(p.releases), abi.encodeCall(p.releases.reinstate, (V1)), active);

        _govern(address(p.releases), abi.encodeCall(p.releases.revoke, (V1)));
        bytes memory revoked =
            abi.encodeWithSelector(ReleaseRegistry.WrongStatus.selector, ReleaseRegistry.Status.Revoked);
        // A revoked release comes back only by reinstate: deprecating it would make it work again.
        _governFails(address(p.releases), abi.encodeCall(p.releases.deprecate, (V1)), revoked);
        _governFails(address(p.releases), abi.encodeCall(p.releases.revoke, (V1)), revoked);

        _govern(address(p.releases), abi.encodeCall(p.releases.reinstate, (V1)));
        _govern(address(p.releases), abi.encodeCall(p.releases.deprecate, (V1)));
        bytes memory deprecated =
            abi.encodeWithSelector(ReleaseRegistry.WrongStatus.selector, ReleaseRegistry.Status.Deprecated);
        _governFails(address(p.releases), abi.encodeCall(p.releases.deprecate, (V1)), deprecated);
    }

    function test_AReleaseNeedsAVersionAHashAndRealApks() public {
        bytes memory invalid = abi.encodeWithSelector(ReleaseRegistry.InvalidRelease.selector);
        bytes32[] memory none = new bytes32[](0);
        _governFails(
            address(p.releases), abi.encodeCall(p.releases.publish, (0, keccak256("code"), none)), invalid
        );
        _governFails(address(p.releases), abi.encodeCall(p.releases.publish, (V1, bytes32(0), none)), invalid);
        bytes32[] memory zero = new bytes32[](1);
        _governFails(
            address(p.releases), abi.encodeCall(p.releases.publish, (V1, keccak256("code"), zero)), invalid
        );
    }

    function test_TheLatestReleaseIsTheHighestPublished() public {
        uint48 v2 = p.releases.versionOf(2, 0, 0);
        _publish(v2, keccak256("two"), new bytes32[](0));
        // A backport of the 1.x line does not become the update apps on 2.0.0 are offered.
        _publish(p.releases.versionOf(1, 9, 1), keccak256("backport"), new bytes32[](0));
        assertEq(p.releases.latest(), v2);
    }

    function test_AStakeVaultIsNeverMadeWithALongUnbonding() public {
        vm.expectRevert(StakeVault.InvalidPeriod.selector);
        new StakeVault(p.governor, p.identities, IERC20(address(brla)), 31 days);
    }

    function test_AReleaseNamesAFewApksAtMost() public {
        bytes32[] memory apks = new bytes32[](9);
        for (uint256 i; i < apks.length; ++i) {
            apks[i] = bytes32(i + 1);
        }
        vm.prank(institute);
        address[] memory t = new address[](1);
        bytes[] memory c = new bytes[](1);
        t[0] = address(p.releases);
        c[0] = abi.encodeCall(p.releases.publish, (V1, keccak256("code"), apks));
        vm.expectRevert();
        p.governor.execute(t, c);
    }
}

/// @dev A BRL coin as its issuer controls it: it can freeze an address, and be upgraded into one that keeps a
/// share of each transfer.
contract IssuerBRL is MockBRL {
    mapping(address => bool) public frozen;
    uint256 public transferFeeBps;

    error Frozen(address account);

    constructor() MockBRL("Issuer BRL", "IBRL", 4) {}

    function freeze(address account, bool frozen_) external {
        frozen[account] = frozen_;
    }

    function setTransferFeeBps(uint256 bps) external {
        transferFeeBps = bps;
    }

    function _update(address from, address to, uint256 value) internal override {
        if (frozen[from]) revert Frozen(from);
        if (frozen[to]) revert Frozen(to);
        uint256 fee = from == address(0) ? 0 : value * transferFeeBps / 10_000;
        if (fee > 0) super._update(from, address(0), fee);
        super._update(from, to, value - fee);
    }
}
