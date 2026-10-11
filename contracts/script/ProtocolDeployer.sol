// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {PBACGovernor} from "../src/PBACGovernor.sol";
import {IdentityRegistry} from "../src/IdentityRegistry.sol";
import {Reputation} from "../src/Reputation.sol";
import {FeeVault} from "../src/FeeVault.sol";
import {RideEscrow} from "../src/RideEscrow.sol";
import {DisputeCourt} from "../src/DisputeCourt.sol";
import {MapRegistry} from "../src/MapRegistry.sol";
import {FareGuide} from "../src/FareGuide.sol";

/// @notice Deploys and wires the eight protocol contracts, sets the first policies and the BRL allow-list, then
/// ends the bootstrap so only governance can change anything. Shared by the deploy script and the tests.
abstract contract ProtocolDeployer {
    bytes32 public constant CREDENTIAL_CNH = keccak256("klippelgo.credential.CNH");
    bytes32 public constant CREDENTIAL_KYC = keccak256("klippelgo.credential.KYC");

    struct TokenInit {
        IERC20 token;
        uint8 decimals;
    }

    struct Config {
        IERC20 stakeToken; // stake is held in this BRL token
        uint8 stakeDecimals;
        TokenInit[] tokens; // BRL allow-list
        address feeBeneficiary; // Instituto Klippel
        address[] attesters;
        uint64 unbondingPeriod;
        uint64 completionTimeout;
        uint64 commitPeriod;
        uint64 revealPeriod;
        PBACGovernor.Params params;
        uint256 driverMinStakeBrl; // whole reais
        uint256 validatorMinStakeBrl;
        uint16 driverMinScore; // x100
        uint32 proposerMinAccountAge;
        uint32 mapRebuildQuorum; // validators that must rebuild a map pack to the same proof root
        uint64 mapGracePeriod; // how long a pack outlives its replacement
    }

    struct Protocol {
        PBACGovernor governor;
        IdentityRegistry registry;
        Reputation reputation;
        FeeVault feeVault;
        RideEscrow escrow;
        DisputeCourt court;
        MapRegistry maps;
        FareGuide fares;
    }

    function _deployProtocol(Config memory c) internal returns (Protocol memory p) {
        p.governor = new PBACGovernor(c.params);
        p.registry = new IdentityRegistry(p.governor, c.stakeToken, c.unbondingPeriod);
        p.reputation = new Reputation(p.governor);
        p.feeVault = new FeeVault(p.governor, c.feeBeneficiary);
        p.escrow = new RideEscrow(p.governor, c.completionTimeout);
        p.court = new DisputeCourt(p.governor, p.escrow, c.commitPeriod, c.revealPeriod);
        p.maps = new MapRegistry(p.governor, c.mapRebuildQuorum, c.mapGracePeriod, _launchVerifierParams());
        p.fares = new FareGuide(p.governor, _launchTariff());

        PBACGovernor g = p.governor;
        g.bootstrapCall(address(g), abi.encodeCall(g.setModules, (p.registry, p.reputation)));
        g.bootstrapCall(address(p.reputation), abi.encodeCall(p.reputation.setEscrow, (address(p.escrow))));
        g.bootstrapCall(
            address(p.escrow),
            abi.encodeCall(p.escrow.setModules, (p.reputation, address(p.feeVault), address(p.court)))
        );

        // BRL allow-list: fee is R$ 1,00 in each token's decimals; bonds are R$ 2,00 (rider) and R$ 5,00 (driver).
        for (uint256 i; i < c.tokens.length; ++i) {
            uint256 one = 10 ** c.tokens[i].decimals;
            g.bootstrapCall(
                address(p.escrow),
                abi.encodeCall(p.escrow.setToken, (c.tokens[i].token, one, 2 * one, 5 * one))
            );
        }
        for (uint256 i; i < c.attesters.length; ++i) {
            g.bootstrapCall(
                address(p.registry), abi.encodeCall(p.registry.setAttester, (c.attesters[i], true))
            );
        }

        uint256 stakeOne = 10 ** c.stakeDecimals;
        uint8 anyRole = p.registry.ROLE_RIDER() | p.registry.ROLE_DRIVER() | p.registry.ROLE_VALIDATOR();

        _policy(
            g, address(p.escrow), RideEscrow.open.selector, _p(p.registry.ROLE_RIDER(), 0, 0, 0, bytes32(0))
        );
        _policy(
            g,
            address(p.escrow),
            RideEscrow.accept.selector,
            _p(p.registry.ROLE_DRIVER(), c.driverMinScore, 0, c.driverMinStakeBrl * stakeOne, CREDENTIAL_CNH)
        );
        _policy(
            g,
            address(p.court),
            DisputeCourt.commit.selector,
            _p(p.registry.ROLE_VALIDATOR(), 0, 0, c.validatorMinStakeBrl * stakeOne, bytes32(0))
        );
        _policy(
            g,
            address(p.maps),
            MapRegistry.attest.selector,
            _p(p.registry.ROLE_VALIDATOR(), 0, 0, c.validatorMinStakeBrl * stakeOne, bytes32(0))
        );
        _policy(
            g,
            address(g),
            PBACGovernor.propose.selector,
            _p(anyRole, 0, c.proposerMinAccountAge, 0, bytes32(0))
        );

        g.finishBootstrap();
    }

    /// @dev R$ 3,00 plus R$ 2,00 per km and R$ 0,25 per minute, never under R$ 8,00. Riders may ask up to 10%
    /// either side of that, and drivers may quote up to 20% under the rider's price.
    function _launchTariff() internal pure returns (FareGuide.Tariff memory) {
        return FareGuide.Tariff({
            baseCentavos: 300,
            perKmCentavos: 200,
            perMinuteCentavos: 25,
            minimumCentavos: 800,
            riderRangeBps: 1_000,
            driverDiscountBps: 2_000
        });
    }

    /// @dev The defaults of `Params` in the verifier crate (core/verify/src/lib.rs).
    function _launchVerifierParams() internal pure returns (MapRegistry.VerifierParams memory) {
        return MapRegistry.VerifierParams({
            endpointToleranceM: 50,
            distanceToleranceBps: 200,
            traceMarginM: 25,
            maxAccuracyM: 50,
            detourToleranceBps: 2_500,
            maxSpeedCmS: 6_000,
            maxAccelCmS2: 1_200,
            maxHeadIntervalMs: 60_000
        });
    }

    function _p(uint8 roles, uint16 minScore, uint32 minAge, uint256 minStake, bytes32 credential)
        internal
        pure
        returns (PBACGovernor.Policy memory)
    {
        return PBACGovernor.Policy({
            enabled: true,
            roles: roles,
            minScore: minScore,
            minAccountAge: minAge,
            minStake: minStake,
            credential: credential
        });
    }

    function _policy(PBACGovernor g, address target, bytes4 action, PBACGovernor.Policy memory pol) internal {
        g.bootstrapCall(address(g), abi.encodeCall(g.setPolicy, (target, action, pol)));
    }
}
