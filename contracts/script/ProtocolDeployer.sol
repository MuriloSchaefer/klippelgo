// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {PBACGovernor} from "../src/PBACGovernor.sol";
import {IdentityRegistry} from "../src/IdentityRegistry.sol";
import {Reputation} from "../src/Reputation.sol";
import {FeeVault} from "../src/FeeVault.sol";
import {RideEscrow} from "../src/RideEscrow.sol";
import {DisputeCourt} from "../src/DisputeCourt.sol";

/// @notice Deploys and wires the six protocol contracts, sets the first policies and the BRL allow-list, then
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
    }

    struct Protocol {
        PBACGovernor governor;
        IdentityRegistry registry;
        Reputation reputation;
        FeeVault feeVault;
        RideEscrow escrow;
        DisputeCourt court;
    }

    function _deployProtocol(Config memory c) internal returns (Protocol memory p) {
        p.governor = new PBACGovernor(c.params);
        p.registry = new IdentityRegistry(p.governor, c.stakeToken, c.unbondingPeriod);
        p.reputation = new Reputation(p.governor);
        p.feeVault = new FeeVault(p.governor, c.feeBeneficiary);
        p.escrow = new RideEscrow(p.governor, c.completionTimeout);
        p.court = new DisputeCourt(p.governor, p.escrow, c.commitPeriod, c.revealPeriod);

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
            address(g),
            PBACGovernor.propose.selector,
            _p(anyRole, 0, c.proposerMinAccountAge, 0, bytes32(0))
        );

        g.finishBootstrap();
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
