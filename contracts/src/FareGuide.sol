// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";

/// @notice The price the apps recommend for a trip, and how far riders and drivers may move away from it.
///
/// Fares stay a market: the rider names a price and drivers quote around it. The guide keeps that market inside
/// a band the community sets, so a rider is not asked to guess a fair price blind and a driver is not pushed
/// into a race to the bottom. The escrow never learns a trip's length, so the band is kept by the phones: a
/// driver's phone ignores a request priced outside the rider's range, and a rider's phone ignores a quote below
/// the driver's floor. Both read the numbers here.
contract FareGuide is Governed {
    struct Tariff {
        uint32 baseCentavos; // charged on every trip
        uint32 perKmCentavos;
        uint32 perMinuteCentavos;
        uint32 minimumCentavos; // no trip is recommended below this
        uint16 riderRangeBps; // how far above or below the recommendation a rider may ask or a driver quote
        uint16 driverDiscountBps; // how far below the rider's price a driver may quote
    }

    uint256 public constant BPS = 10_000;
    /// @notice Recommendations are whole multiples of this, so every phone shows the same round price.
    uint256 public constant STEP_CENTAVOS = 50;

    Tariff public tariff;

    /// @notice What one whole native coin of this chain (10^18 wei) is worth, in centavos. The phones use it to
    /// show what a ride's transactions cost the network, the infrastructure cost, in reais. It prices nothing
    /// on chain: gas is paid to the network in the native coin, outside the escrow. Zero means not set.
    uint256 public nativeCoinCentavos;

    event TariffSet(Tariff tariff);
    event NativeCoinPriceSet(uint256 centavos);

    error InvalidTariff();

    constructor(IPBACGovernor governor_, Tariff memory tariff_) Governed(governor_) {
        _set(tariff_);
    }

    function setTariff(Tariff calldata tariff_) external onlyGovernor {
        _set(tariff_);
    }

    function setNativeCoinPrice(uint256 centavos) external onlyGovernor {
        nativeCoinCentavos = centavos;
        emit NativeCoinPriceSet(centavos);
    }

    /// @notice What `gasUsed` units of gas at `gasPriceWei` cost, in centavos, at the rate governance set.
    function infrastructureCost(uint256 gasUsed, uint256 gasPriceWei) external view returns (uint256) {
        return gasUsed * gasPriceWei * nativeCoinCentavos / 1e18;
    }

    /// @notice The recommended price in centavos for a trip of `distanceM` metres and `durationS` seconds, as
    /// the request states them, rounded to the nearest R$ 0,50.
    function recommended(uint32 distanceM, uint32 durationS) public view returns (uint256) {
        Tariff memory t = tariff;
        uint256 price = uint256(t.baseCentavos) + uint256(t.perKmCentavos) * distanceM / 1000
            + uint256(t.perMinuteCentavos) * durationS / 60;
        if (price < t.minimumCentavos) price = t.minimumCentavos;
        return (price + STEP_CENTAVOS / 2) / STEP_CENTAVOS * STEP_CENTAVOS;
    }

    /// @notice The lowest and highest price a rider may ask for the trip.
    function riderRange(uint32 distanceM, uint32 durationS)
        external
        view
        returns (uint256 lowest, uint256 highest)
    {
        uint256 price = recommended(distanceM, durationS);
        uint256 range = price * tariff.riderRangeBps / BPS;
        return (price - range, price + range);
    }

    /// @notice The highest quote a driver may make for the trip, whatever the rider asked: the top of the
    /// rider's own range. A driver may ask more than the rider offered, and the rider decides whether to take it.
    function driverCeiling(uint32 distanceM, uint32 durationS) external view returns (uint256) {
        uint256 price = recommended(distanceM, durationS);
        return price + price * tariff.riderRangeBps / BPS;
    }

    /// @notice The lowest quote a driver may make against a rider's price.
    function driverFloor(uint256 riderPrice) external view returns (uint256) {
        return riderPrice - riderPrice * tariff.driverDiscountBps / BPS;
    }

    function _set(Tariff memory t) internal {
        if (t.riderRangeBps >= BPS || t.driverDiscountBps >= BPS) revert InvalidTariff();
        tariff = t;
        emit TariffSet(t);
    }
}
