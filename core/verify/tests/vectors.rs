//! Fixed vectors shared with `contracts/test/MapRegistry.t.sol`, which recomputes the same hashes in Solidity.
//! If one of these changes, the encoding changed: every published proof root and signed hash changes with it.

const CHUNK: &str =
    "00020000000000000081030002000000000000050af1f67b19e43452b8000000000000050bf1f67b19e43452b7\
     00000000000000820300020000000000000514f1f67b1ae43452b80000000000000515f1f67b1ae43452b7";

use klippelgo_verify::index::{merkle_proof, ProofIndex, CAR_BACKWARD, CAR_FORWARD};
use klippelgo_verify::{Domain, Point, RouteQuote, Step, TripReceipt, Vehicle, Way, WayNode};

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn index() -> ProofIndex {
    // 130 two-node ways: three chunks, the last holding two ways.
    let ways: Vec<Way> = (1..=130u64)
        .map(|id| Way {
            id,
            flags: CAR_FORWARD | CAR_BACKWARD,
            nodes: (0..2)
                .map(|n| WayNode {
                    id: id * 10 + n,
                    at: Point {
                        lat: -235_505_000 + id as i32,
                        lon: -466_333_000 - n as i32,
                    },
                })
                .collect(),
        })
        .collect();
    ProofIndex::build(&ways).unwrap()
}

#[test]
fn proof_index() {
    let index = index();
    let leaves: Vec<_> = index.directory.entries.iter().map(|e| e.leaf).collect();
    assert_eq!(index.chunks.len(), 3);
    assert_eq!(hex(&index.chunks[2]), CHUNK);
    assert_eq!(
        hex(&leaves[2]),
        "4922296d0186b4af5bc68c4f062582121e4b6f07817e68ae27e3d70246baaacc"
    );
    assert_eq!(
        hex(&index.directory.root()),
        "306751624a21bec0d3a920a740d5b85801ca1b44b0b7bf4ec30355d3961846d1"
    );
    let proof = merkle_proof(&leaves, 2);
    assert_eq!(
        proof.iter().map(|h| hex(h)).collect::<Vec<_>>(),
        ["a2198180739e862e720ce238f1d858d71103716dd30076d330b8d818a1f08516"]
    );
}

#[test]
fn quote_and_receipt_hashes() {
    let domain = Domain {
        chain_id: 31337,
        escrow: [0xe5; 20],
    };
    let mut fare = [0u8; 32];
    fare[29..].copy_from_slice(&[0x03, 0x92, 0x10]);
    let path = vec![
        Step {
            way: 100,
            node: 1000,
        },
        Step {
            way: 100,
            node: 1001,
        },
    ];
    let quote = RouteQuote {
        pack_id: [7; 32],
        pickup: Point {
            lat: -235_505_000,
            lon: -466_333_000,
        },
        dropoff: Point {
            lat: -235_478_000,
            lon: -466_284_000,
        },
        vehicle: Vehicle::Motorcycle,
        path: path.clone(),
        distance_m: 8500,
        duration_s: 1320,
        salt: [5; 32],
        fare,
        token: [0xb2; 20],
        rider: [0x11; 20],
        driver: [0x22; 20],
        expiry: 1_800_000_000,
        previous: [0; 32],
    };
    assert_eq!(
        hex(&quote.route_hash()),
        "738f91eee3804ae3a4f0c62cfb66c57040cf75b92d14990913bf90bc433c87b7"
    );
    let quote_hash = quote.hash(&domain);
    assert_eq!(
        hex(&quote_hash),
        "aa158c5377b98589098ed03a389bf81ab541cc7cdf0abea974e99cfd7dbf90ae"
    );
    let receipt = TripReceipt {
        ride_id: 42,
        quote_hash,
        rider_head: [0xaa; 32],
        driver_head: [0xbb; 32],
        driven_path: path,
        driven_distance_m: 8400,
        started_at: 1_700_000_000,
        ended_at: 1_700_001_300,
        fare,
    };
    assert_eq!(
        hex(&receipt.hash(&domain)),
        "8021e34c7b386caee5882774ccca57ad9dfe1b448883c12d7eefff7da8a37899"
    );
}
