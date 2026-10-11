//! What the apps do with a map pack, through the same calls the Kotlin and Swift bindings make: search, route,
//! quote, log a trip on two phones, write the receipt and check it. The pack is a few invented streets, and
//! Valhalla's answers are written by hand in its JSON.

use k256::ecdsa::SigningKey as WalletKey;
use klippelgo_core::maps::*;
use klippelgo_proofs::manifest::{self, Manifest, OsmSnapshot};
use klippelgo_proofs::pack::{INDEX_FILE, SEARCH_FILE, WAYS_FILE};
use klippelgo_proofs::search::{self, Feature, Kind, Street};
use klippelgo_proofs::valhalla::encode_polyline6;
use klippelgo_verify::index::ProofIndex;
use klippelgo_verify::{keccak256, Point, Way, WayNode};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::SigningKey as HardwareKey;
use std::path::PathBuf;

// Rua Um runs east from the corner, 100 m a block; Rua Dois runs north from its end. Coordinates end in zero
// so that Valhalla's six decimals carry them exactly.
const LAT: i32 = -235_500_000;
const LON: i32 = -466_300_000;
const BLOCK_LAT: i32 = 9_000;
const BLOCK_LON: i32 = 9_800;

fn um(i: i32) -> Point {
    Point {
        lat: LAT,
        lon: LON + i * BLOCK_LON,
    }
}

fn dois(i: i32) -> Point {
    Point {
        lat: LAT + i * BLOCK_LAT,
        lon: LON + 3 * BLOCK_LON,
    }
}

fn build_pack(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("klippelgo-pack-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("proof-index")).unwrap();

    let node = |id, at| WayNode { id, at };
    let ways = vec![
        Way {
            id: 1,
            flags: 0x0f,
            nodes: (0..4).map(|i| node(10 + i as u64, um(i))).collect(),
        },
        Way {
            id: 2,
            flags: 0x05,
            nodes: vec![node(13, dois(0)), node(21, dois(1)), node(22, dois(2))],
        },
    ];
    let index = ProofIndex::build(&ways).unwrap();
    std::fs::write(dir.join(WAYS_FILE), index.chunks.concat()).unwrap();
    std::fs::write(dir.join(INDEX_FILE), index.directory.encode()).unwrap();
    let streets = ways
        .iter()
        .zip(["Rua Um", "Rua Dois"])
        .map(|(way, name)| Street {
            name: name.into(),
            nodes: way.nodes.iter().map(|n| (n.id, n.at)).collect(),
        })
        .collect();
    let bakery = Feature {
        kind: Kind::Poi,
        name: "Padaria São Bento".into(),
        context: "Rua Dois, 150".into(),
        at: dois(2),
    };
    search::build(&dir.join(SEARCH_FILE), streets, vec![bakery]).unwrap();
    let manifest = Manifest {
        format: manifest::FORMAT,
        region: "test-two-streets".into(),
        version: 202610,
        osm: OsmSnapshot {
            source: "invented".into(),
            sequence: 0,
            sha256: [0; 32],
            bbox: None,
        },
        proof_root: index.directory.root(),
        tools: Default::default(),
        files: Manifest::hash_files(&dir).unwrap(),
    };
    std::fs::write(dir.join(manifest::FILE_NAME), manifest.to_bytes()).unwrap();
    dir
}

/// Valhalla's `route` and `trace_attributes` answers for a trip from mid-block on Rua Um to mid-block on Rua
/// Dois: two edges that meet at the corner.
fn valhalla_answers() -> (String, String) {
    let from = Point {
        lat: LAT,
        lon: LON + BLOCK_LON / 2,
    };
    let to = Point {
        lat: LAT + BLOCK_LAT * 3 / 2,
        lon: LON + 3 * BLOCK_LON,
    };
    let shape = encode_polyline6(&[from, um(1), um(2), um(3), dois(1), to]);
    let route = format!(
        r#"{{"trip":{{"units":"kilometers","legs":[{{"shape":"{shape}","summary":{{"length":0.4,"time":61.2}}}}]}}}}"#
    );
    let attributes = format!(
        r#"{{"shape":"{shape}","edges":[{{"way_id":1,"begin_shape_index":0,"end_shape_index":3}},
            {{"way_id":2,"begin_shape_index":3,"end_shape_index":5}}]}}"#
    );
    (route, attributes)
}

fn wallet(seed: u8) -> (WalletKey, String) {
    let key = WalletKey::from_slice(&[seed; 32]).unwrap();
    let point = key.verifying_key().to_encoded_point(false);
    let address = &keccak256(&[&point.as_bytes()[1..]])[12..];
    (key, klippelgo_proofs::hex::encode(address))
}

fn wallet_sign(key: &WalletKey, digest: &[u8]) -> Vec<u8> {
    let prefixed = keccak256(&[b"\x19Ethereum Signed Message:\n32", digest]);
    let (signature, recovery) = key.sign_prehash_recoverable(&prefixed).unwrap();
    let mut out = signature.to_bytes().to_vec();
    out.push(27 + recovery.to_byte());
    out
}

fn hardware(seed: u8) -> (HardwareKey, Vec<u8>) {
    let key = HardwareKey::from_slice(&[seed; 32]).unwrap();
    let public = key
        .verifying_key()
        .to_encoded_point(true)
        .as_bytes()
        .to_vec();
    (key, public)
}

fn hardware_sign(key: &HardwareKey, digest: &[u8]) -> Vec<u8> {
    let signature: p256::ecdsa::Signature = key.sign(digest);
    signature.to_bytes().to_vec()
}

#[test]
fn opens_a_pack_and_searches_it() {
    let dir = build_pack("search");
    let pack = MapPack::open(dir.to_string_lossy().into()).unwrap();
    assert_eq!(
        (pack.region().as_str(), pack.version()),
        ("test-two-streets", 202610)
    );
    assert_eq!(pack.id().len(), 32);
    pack.check_files().unwrap();

    let hits = pack
        .search("padaria sao".into(), Some(um(0).into()), 5)
        .unwrap();
    assert_eq!(hits[0].name, "Padaria São Bento");
    assert_eq!(hits[0].kind, PlaceKind::PointOfInterest);
    assert!(hits[0].distance_m.unwrap() > 300);
    let here = pack
        .reverse(
            Point {
                lat: LAT + 200,
                lon: LON + BLOCK_LON,
            }
            .into(),
        )
        .unwrap()
        .unwrap();
    assert_eq!(here.name, "Rua Um");

    // A map edited after the manifest was written is caught, both by the file check and when a route reads it.
    let mut ways = std::fs::read(dir.join(WAYS_FILE)).unwrap();
    ways[20] ^= 1; // one street's one-way flags
    std::fs::write(dir.join(WAYS_FILE), ways).unwrap();
    assert!(matches!(pack.check_files(), Err(MapError::Pack(_))));
    let (route, attributes) = valhalla_answers();
    assert!(matches!(
        pack.measure_route(route, attributes, VehicleKind::Car),
        Err(MapError::Pack(_))
    ));
    // Nor does one whose chunk directory does not hash to the manifest's proof root.
    std::fs::write(dir.join(INDEX_FILE), b"KGPI\0\0\0\x01\0\0\0\0").unwrap();
    assert!(matches!(
        MapPack::open(dir.to_string_lossy().into()),
        Err(MapError::Pack(_))
    ));
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_ride_from_route_to_receipt() {
    let dir = build_pack("ride");
    let pack = MapPack::open(dir.to_string_lossy().into()).unwrap();
    let escrow = EscrowRef {
        chain_id: 31337,
        escrow: format!("0x{}", "e5".repeat(20)),
    };
    let (rider, rider_address) = wallet(1);
    let (driver, driver_address) = wallet(2);

    // Rider: route, measured by the verifier. Half a block, two blocks, one and a half blocks north.
    let (route_json, attributes_json) = valhalla_answers();
    let request = route_request(um(0).into(), dois(2).into(), VehicleKind::Car);
    assert!(request.contains(r#""costing":"auto""#) && request.contains("-23.55"));
    assert!(attributes_request(route_json.clone(), VehicleKind::Car)
        .unwrap()
        .contains("edge.way_id"));
    let route = pack
        .measure_route(route_json, attributes_json, VehicleKind::Car)
        .unwrap();
    assert_eq!(
        route.path.iter().map(|s| s.node).collect::<Vec<_>>(),
        [10, 11, 12, 13, 21, 22]
    );
    assert!(route.distance_m.abs_diff(400) <= 2, "{}", route.distance_m);
    assert_eq!(route.duration_s, 61);
    assert_eq!(
        coarse_route(route.distance_m, route.duration_s),
        CoarseRoute {
            distance_m: 500,
            duration_s: 60
        }
    );
    assert_eq!(
        coarse_route(8_740, 1_290),
        CoarseRoute {
            distance_m: 8_500,
            duration_s: 1_320
        }
    );

    // The request carries only the route's hash; the driver quotes on it; the rider locks the same hash.
    let salt = vec![9u8; 32];
    let commitment = route_hash(pack.id(), route.clone(), salt.clone()).unwrap();
    let terms = OfferTerms {
        fare: "234000".into(),
        token: format!("0x{}", "b2".repeat(20)),
        rider: rider_address,
        driver: driver_address,
        expiry: 1_800_000_600,
    };
    let offered = quote_hash(escrow.clone(), commitment.clone(), terms.clone()).unwrap();
    let quote = quote_json(pack.id(), route.clone(), salt, terms.clone()).unwrap();
    // Driver, once the rider reveals the route: the same hash comes out of checking it on the driver's pack.
    let checked = pack.check_quote(escrow.clone(), quote.clone()).unwrap();
    assert_eq!(checked.quote_hash, offered);
    assert_eq!(
        (checked.distance_m, checked.line.len()),
        (route.distance_m, 6)
    );
    // The same route, sealed to the key in the driver's offer, is how it reaches the driver.
    let offer_key = klippelgo_core::sealed::new_offer_key().unwrap();
    let sealed =
        klippelgo_core::sealed::seal_route(offer_key.public, offered.clone(), quote.clone())
            .unwrap();
    assert_eq!(
        klippelgo_core::sealed::open_route(offer_key.secret, offered.clone(), sealed).unwrap(),
        quote
    );
    let padded = quote.replace(
        &format!(r#""distanceM":{}"#, route.distance_m),
        r#""distanceM":4000"#,
    );
    assert!(matches!(
        pack.check_quote(escrow.clone(), padded),
        Err(MapError::Refused(_))
    ));
    let dearer = OfferTerms {
        fare: "999000".into(),
        ..terms
    };
    assert_ne!(
        quote_hash(escrow.clone(), commitment, dearer).unwrap(),
        offered
    );

    // Both phones log the trip: a fix every 2 s at 10 m/s along the route's line.
    let ride = RideId {
        escrow: escrow.clone(),
        ride_id: 7,
        quote_hash: offered.clone(),
    };
    let (rider_hw, rider_key) = hardware(3);
    let (driver_hw, driver_key) = hardware(4);
    let rider_cert = wallet_sign(
        &rider,
        &trace_key_digest(ride.clone(), TripRole::Rider, rider_key.clone()).unwrap(),
    );
    let driver_cert = wallet_sign(
        &driver,
        &trace_key_digest(ride.clone(), TripRole::Driver, driver_key.clone()).unwrap(),
    );
    let fixes: Vec<GpsFix> = {
        let mut fixes = Vec::new();
        for pair in route.line.windows(2) {
            let (a, b) = (pair[0], pair[1]);
            let blocks = ((b.lat_e7 - a.lat_e7).abs() / (BLOCK_LAT / 5)
                + (b.lon_e7 - a.lon_e7).abs() / (BLOCK_LON / 5))
                .max(1);
            for k in 0..blocks {
                fixes.push(GpsFix {
                    t_ms: 1_800_000_000_000 + fixes.len() as u64 * 2000,
                    at: Coordinate {
                        lat_e7: a.lat_e7 + (b.lat_e7 - a.lat_e7) * k / blocks,
                        lon_e7: a.lon_e7 + (b.lon_e7 - a.lon_e7) * k / blocks,
                    },
                    accuracy_cm: 400,
                    speed_cm_s: 1000,
                    mock: false,
                });
            }
        }
        fixes.push(GpsFix {
            t_ms: 1_800_000_000_000 + fixes.len() as u64 * 2000,
            at: *route.line.last().unwrap(),
            accuracy_cm: 400,
            speed_cm_s: 0,
            mock: false,
        });
        fixes
    };
    let record = |role, key: &Vec<u8>, own: &HardwareKey, other: &HardwareKey| {
        let log = TripLog::new(ride.clone(), role, key.clone()).unwrap();
        for (i, fix) in fixes.iter().enumerate() {
            assert!(log.push(*fix));
            if (i + 1) % 15 == 0 || i + 1 == fixes.len() {
                let head = log.to_sign().unwrap();
                let signature = hardware_sign(own, &head.digest);
                assert!(log.signed(head.fixes, signature.clone()).unwrap());
                // The other phone checks the head came from this one's certified key, then countersigns it.
                assert!(
                    peer_head_is_signed(key.clone(), head.fixes, head.head.clone(), signature)
                        .unwrap()
                );
                let digest = head_digest(head.fixes, head.head).unwrap();
                assert!(log
                    .countersigned(head.fixes, hardware_sign(other, &digest))
                    .unwrap());
            }
        }
        assert!(log.to_sign().is_none());
        assert!(!log.push(fixes[0]));
        log
    };
    let rider_log = record(TripRole::Rider, &rider_key, &rider_hw, &driver_hw);
    let driver_log = record(TripRole::Driver, &driver_key, &driver_hw, &rider_hw);
    assert_eq!(driver_log.fixes().len(), fixes.len());
    let rider_trace = rider_log
        .export(rider_cert.clone(), driver_key.clone(), driver_cert.clone())
        .unwrap();
    let driver_trace = driver_log
        .export(driver_cert, rider_key, rider_cert)
        .unwrap();

    // At arrival: map-match (the same two edges), draft the receipt, and check the whole ride.
    assert!(match_request(fixes.clone(), VehicleKind::Car).contains(r#""shape_match":"map_snap""#));
    let (_, matched) = valhalla_answers();
    let evidence = format!(
        r#"{{"chainId":31337,"escrow":"{}","quote":{quote},"traces":[{rider_trace},{driver_trace}]}}"#,
        escrow.escrow
    );
    let started_at = fixes[0].t_ms / 1000;
    let with_receipt = pack
        .draft_receipt(evidence.clone(), matched.clone(), 7, started_at)
        .unwrap();
    let trip = pack.check_trip(with_receipt.clone()).unwrap();
    assert_eq!(trip.receipt_hash.len(), 32);
    assert!(
        trip.driven_distance_m.abs_diff(route.distance_m) <= 3,
        "{trip:?}"
    );
    assert!(
        trip.reached_dropoff && trip.logs == 2 && trip.pickup_gap_m <= 2,
        "{trip:?}"
    );
    assert_eq!(trip.duration_s, (fixes.len() as u64 - 1) * 2);

    // A receipt for another ride id does not hold with these logs, and evidence without one is not a trip.
    assert!(matches!(
        pack.draft_receipt(evidence.clone(), matched, 8, started_at),
        Err(MapError::Refused(_))
    ));
    assert!(matches!(
        pack.check_trip(evidence),
        Err(MapError::Invalid(_))
    ));
    let longer = with_receipt.replace(
        &format!(r#""drivenDistanceM": {}"#, trip.driven_distance_m),
        r#""drivenDistanceM": 9000"#,
    );
    assert_ne!(longer, with_receipt);
    assert!(matches!(pack.check_trip(longer), Err(MapError::Refused(_))));
    let _ = std::fs::remove_dir_all(dir);
}
