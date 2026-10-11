// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {Test} from "forge-std/Test.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";

/// @notice Recomputes in Solidity the hashes the verifier crate produces, from the same inputs as
/// `core/verify/tests/vectors.rs`. Nothing on chain needs these today. They show that a contract can check a
/// proof-index chunk with OpenZeppelin's MerkleProof and rebuild a quote or receipt hash from its fields, which
/// is what a zero-knowledge verifier contract will rest on.
contract ProofEncodingTest is Test {
    uint64 constant CHAIN_ID = 31337;
    address constant ESCROW = 0xE5e5E5E5e5e5E5E5E5E5e5E5e5e5e5E5e5e5e5e5;
    uint256 constant FARE = 23_4000;

    function test_ChunkProvesIntoTheProofRootWithOpenZeppelin() public pure {
        // The last chunk of the 130-way index: ways 129 and 130.
        bytes memory chunk =
            hex"00020000000000000081030002000000000000050af1f67b19e43452b8000000000000050bf1f67b19e43452b700000000000000820300020000000000000514f1f67b1ae43452b80000000000000515f1f67b1ae43452b7";
        bytes32 root = 0x306751624a21bec0d3a920a740d5b85801ca1b44b0b7bf4ec30355d3961846d1;
        bytes32[] memory proof = new bytes32[](1);
        proof[0] = 0xa2198180739e862e720ce238f1d858d71103716dd30076d330b8d818a1f08516;

        bytes32 leaf = keccak256(
            bytes.concat(keccak256(abi.encodePacked(uint32(2), uint64(129), uint64(130), keccak256(chunk))))
        );
        assertEq(leaf, 0x4922296d0186b4af5bc68c4f062582121e4b6f07817e68ae27e3d70246baaacc);
        assertTrue(MerkleProof.verify(proof, root, leaf));

        // The same chunk claiming to hold one more way id does not.
        bytes32 wider = keccak256(
            bytes.concat(keccak256(abi.encodePacked(uint32(2), uint64(129), uint64(131), keccak256(chunk))))
        );
        assertFalse(MerkleProof.verify(proof, root, wider));
    }

    function test_QuoteAndReceiptHashesMatchTheVerifier() public pure {
        bytes32 pathHash =
            keccak256(abi.encodePacked(uint32(2), uint64(100), uint64(1000), uint64(100), uint64(1001)));
        // What the rider commits to in the gossip request, salted so it gives no place away.
        bytes32 routeHash = keccak256(
            abi.encodePacked(
                keccak256("KlippelGo route v1"),
                bytes32(uint256(0x0707070707070707070707070707070707070707070707070707070707070707)), // pack id
                int32(-235_505_000),
                int32(-466_333_000),
                int32(-235_478_000),
                int32(-466_284_000),
                uint8(1), // motorcycle
                pathHash,
                uint32(8500),
                uint32(1320),
                bytes32(uint256(0x0505050505050505050505050505050505050505050505050505050505050505)) // salt
            )
        );
        assertEq(routeHash, 0x738f91eee3804ae3a4f0c62cfb66c57040cf75b92d14990913bf90bc433c87b7);

        // What the driver signs when quoting and the rider locks in the escrow.
        bytes32 quoteHash = keccak256(
            abi.encodePacked(
                keccak256("KlippelGo route quote v1"),
                CHAIN_ID,
                ESCROW,
                routeHash,
                FARE,
                address(0xb2b2b2b2b2B2b2B2B2b2b2B2B2b2B2B2b2b2b2b2), // token
                address(0x1111111111111111111111111111111111111111), // rider
                address(0x2222222222222222222222222222222222222222), // driver
                uint64(1_800_000_000),
                bytes32(0) // first quote of the ride
            )
        );
        assertEq(quoteHash, 0xaa158c5377b98589098ed03a389bf81ab541cc7cdf0abea974e99cfd7dbf90ae);

        bytes32 receiptHash = keccak256(
            abi.encodePacked(
                keccak256("KlippelGo trip receipt v1"),
                CHAIN_ID,
                ESCROW,
                uint256(42), // ride id
                quoteHash,
                bytes32(uint256(0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)),
                bytes32(uint256(0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb)),
                pathHash,
                uint32(8400),
                uint64(1_700_000_000),
                uint64(1_700_001_300),
                FARE
            )
        );
        assertEq(receiptHash, 0x8021e34c7b386caee5882774ccca57ad9dfe1b448883c12d7eefff7da8a37899);
    }
}
