//! A whole ride on a small invented street grid: a quote, two GPS logs and a receipt, and the ways each can be
//! forged.

use k256::ecdsa::SigningKey as WalletKey;
use klippelgo_verify::geo::segment_mm;
use klippelgo_verify::index::{
    ProofIndex, CAR_BACKWARD, CAR_FORWARD, MOTORCYCLE_BACKWARD, MOTORCYCLE_FORWARD,
};
use klippelgo_verify::path::{measure, metres, resolve};
use klippelgo_verify::sig::TraceKey;
use klippelgo_verify::trace::{advance, genesis, head_digest, key_digest, RideRef, SignedHead};
use klippelgo_verify::{
    keccak256, quote, receipt, Address, Domain, Error, Fix, Pack, Params, Point, Role, RouteQuote,
    Step, Trace, TripReceipt, Vehicle, Way, WayNode,
};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::SigningKey as HardwareKey;

// A 6 × 6 grid of blocks about 100 m on a side, south-west corner near Praça da Sé. Row `r` is way 100 + r
// running east, column `c` is way 200 + c running north. Column 2 is one-way northbound.
const SIZE: u64 = 6;
const LAT0: i32 = -235_505_000;
const LON0: i32 = -466_333_000;
const DLAT: i32 = 9_000;
const DLON: i32 = 9_800;

fn node(r: u64, c: u64) -> WayNode {
    WayNode {
        id: 1000 + r * 10 + c,
        at: Point {
            lat: LAT0 + r as i32 * DLAT,
            lon: LON0 + c as i32 * DLON,
        },
    }
}

fn city() -> ProofIndex {
    let two_way = CAR_FORWARD | CAR_BACKWARD | MOTORCYCLE_FORWARD | MOTORCYCLE_BACKWARD;
    let mut ways = Vec::new();
    for i in 0..SIZE {
        ways.push(Way {
            id: 100 + i,
            flags: two_way,
            nodes: (0..SIZE).map(|c| node(i, c)).collect(),
        });
        ways.push(Way {
            id: 200 + i,
            flags: if i == 2 {
                CAR_FORWARD | MOTORCYCLE_FORWARD
            } else {
                two_way
            },
            nodes: (0..SIZE).map(|r| node(r, i)).collect(),
        });
    }
    // Enough other streets that the index has several chunks.
    for id in 300..500 {
        ways.push(Way {
            id,
            flags: two_way,
            nodes: vec![node(0, 0), node(0, 1)],
        });
    }
    ways.sort_by_key(|w| w.id);
    ProofIndex::build(&ways).unwrap()
}

/// East along row 0 to column 2, north up the one-way to row 3, east along row 3 to column 5.
fn route() -> Vec<Step> {
    let mut steps = vec![Step {
        way: 100,
        node: node(0, 0).id,
    }];
    steps.extend((1..=2).map(|c| Step {
        way: 100,
        node: node(0, c).id,
    }));
    steps.extend((1..=3).map(|r| Step {
        way: 202,
        node: node(r, 2).id,
    }));
    steps.extend((3..=5).map(|c| Step {
        way: 103,
        node: node(3, c).id,
    }));
    steps
}

fn wallet(seed: u8) -> (WalletKey, Address) {
    let key = WalletKey::from_slice(&[seed; 32]).unwrap();
    let point = key.verifying_key().to_encoded_point(false);
    let mut address = [0u8; 20];
    address.copy_from_slice(&keccak256(&[&point.as_bytes()[1..]])[12..]);
    (key, address)
}

fn wallet_sign(key: &WalletKey, digest: &[u8; 32]) -> [u8; 65] {
    let prefixed = keccak256(&[b"\x19Ethereum Signed Message:\n32", digest]);
    let (signature, recovery) = key.sign_prehash_recoverable(&prefixed).unwrap();
    let mut out = [0u8; 65];
    out[..64].copy_from_slice(&signature.to_bytes());
    out[64] = 27 + recovery.to_byte();
    out
}

fn hardware(seed: u8) -> (HardwareKey, TraceKey) {
    let key = HardwareKey::from_slice(&[seed; 32]).unwrap();
    let public = key
        .verifying_key()
        .to_encoded_point(true)
        .as_bytes()
        .try_into()
        .unwrap();
    (key, public)
}

fn hardware_sign(key: &HardwareKey, digest: &[u8; 32]) -> [u8; 64] {
    let signature: p256::ecdsa::Signature = key.sign(digest);
    signature.to_bytes().into()
}

struct World {
    index: ProofIndex,
    pack: Pack,
    domain: Domain,
    params: Params,
    quote: RouteQuote,
    rider: WalletKey,
    driver: WalletKey,
}

impl World {
    fn new() -> Self {
        let index = city();
        let pack = Pack {
            id: [7; 32],
            proof_root: index.directory.root(),
        };
        let (rider, rider_address) = wallet(1);
        let (driver, driver_address) = wallet(2);
        let mut fare = [0u8; 32];
        fare[29..].copy_from_slice(&[0x03, 0x92, 0x10]); // R$ 23,40 in BRZ
        let mut quote = RouteQuote {
            pack_id: pack.id,
            // Mid-block on row 0 and a few metres off the street; just short of the last corner on row 3.
            pickup: Point {
                lat: LAT0 + 900,
                lon: LON0 + DLON / 2,
            },
            dropoff: Point {
                lat: LAT0 + 3 * DLAT,
                lon: LON0 + 5 * DLON - DLON / 4,
            },
            vehicle: Vehicle::Car,
            path: route(),
            distance_m: 0,
            duration_s: 240,
            salt: [5; 32],
            fare,
            token: [0xb2; 20],
            rider: rider_address,
            driver: driver_address,
            expiry: 1_800_000_000,
            previous: [0; 32],
        };
        let world = World {
            index,
            pack,
            domain: Domain {
                chain_id: 31337,
                escrow: [0xe5; 20],
            },
            params: Params::default(),
            quote: quote.clone(),
            rider,
            driver,
        };
        quote.distance_m = world.check_route(&quote).unwrap().distance_m;
        World { quote, ..world }
    }

    fn check_route(&self, quote: &RouteQuote) -> Result<quote::RouteReport, Error> {
        let witness = self.index.witness(quote.path.iter().map(|s| s.way));
        quote::check_route(
            &quote.path,
            quote.vehicle,
            quote.pickup,
            quote.dropoff,
            &witness,
            &self.pack,
            &self.params,
        )
    }

    fn check_quote(&self, quote: &RouteQuote) -> Result<quote::RouteReport, Error> {
        let witness = self.index.witness(quote.path.iter().map(|s| s.way));
        quote::check(quote, &witness, &self.pack, &self.params)
    }

    fn ride(&self) -> RideRef {
        RideRef {
            domain: self.domain,
            ride_id: 42,
            quote_hash: self.quote.hash(&self.domain),
        }
    }

    /// A fix every 2 s at 10 m/s along the quoted route from pickup to drop-off, nudged sideways by `wobble`.
    fn drive(&self, wobble: i32) -> Vec<Fix> {
        let ways = self
            .index
            .witness(self.quote.path.iter().map(|s| s.way))
            .ways(&self.pack.proof_root)
            .unwrap();
        let mut points = resolve(&self.quote.path, &ways, Vehicle::Car).unwrap();
        let last = points.len() - 1;
        points[0] = Point {
            lat: LAT0,
            lon: self.quote.pickup.lon,
        };
        points[last] = self.quote.dropoff;
        let mut fixes = Vec::new();
        let mut t_ms = 1_700_000_000_000u64;
        for pair in points.windows(2) {
            let n = (segment_mm(pair[0], pair[1]) / 20_000).max(1) as i32;
            for k in 0..n {
                fixes.push(Fix {
                    t_ms,
                    at: Point {
                        lat: pair[0].lat
                            + (pair[1].lat - pair[0].lat) * k / n
                            + wobble * (k % 3 - 1),
                        lon: pair[0].lon
                            + (pair[1].lon - pair[0].lon) * k / n
                            + wobble * ((k + 1) % 3 - 1),
                    },
                    accuracy_cm: 500,
                    speed_cm_s: 1000,
                    mock: false,
                });
                t_ms += 2000;
            }
        }
        fixes.push(Fix {
            t_ms,
            at: points[last],
            accuracy_cm: 500,
            speed_cm_s: 0,
            mock: false,
        });
        fixes
    }

    /// Both phones' logs, each signed every 15 fixes and countersigned by the other.
    fn traces(&self, rider_fixes: Vec<Fix>, driver_fixes: Vec<Fix>) -> [Trace; 2] {
        let ride = self.ride();
        let (rider_hw, rider_key) = hardware(3);
        let (driver_hw, driver_key) = hardware(4);
        let rider_cert = wallet_sign(&self.rider, &key_digest(&ride, Role::Rider, &rider_key));
        let driver_cert = wallet_sign(&self.driver, &key_digest(&ride, Role::Driver, &driver_key));
        let log = |role, fixes: Vec<Fix>, own: &HardwareKey, other: &HardwareKey| {
            let (key, certificate, peer_key, peer_certificate) = match role {
                Role::Rider => (rider_key, rider_cert, driver_key, driver_cert),
                Role::Driver => (driver_key, driver_cert, rider_key, rider_cert),
            };
            let mut head = genesis(&ride, role, &key);
            let mut heads = Vec::new();
            for (i, fix) in fixes.iter().enumerate() {
                head = advance(&head, fix);
                if (i + 1) % 15 == 0 || i + 1 == fixes.len() {
                    let digest = head_digest(i as u32 + 1, &head);
                    heads.push(SignedHead {
                        fixes: i as u32 + 1,
                        head,
                        signature: hardware_sign(own, &digest),
                        countersignature: Some(hardware_sign(other, &digest)),
                    });
                }
            }
            Trace {
                role,
                key,
                certificate,
                peer_key,
                peer_certificate,
                fixes,
                heads,
            }
        };
        [
            log(Role::Rider, rider_fixes, &rider_hw, &driver_hw),
            log(Role::Driver, driver_fixes, &driver_hw, &rider_hw),
        ]
    }

    fn receipt(&self, traces: &[Trace; 2], path: Vec<Step>) -> TripReceipt {
        let last = traces[1].fixes.last().unwrap();
        let mut receipt = TripReceipt {
            ride_id: 42,
            quote_hash: self.quote.hash(&self.domain),
            rider_head: traces[0].heads.last().unwrap().head,
            driver_head: traces[1].heads.last().unwrap().head,
            driven_path: path,
            driven_distance_m: 0,
            started_at: traces[1].fixes[0].t_ms / 1000,
            ended_at: last.t_ms / 1000,
            fare: self.quote.fare,
        };
        // Learn the distance the verifier computes, as a phone does before signing.
        if let Err(Error::DistanceMismatch { computed_m, .. }) =
            self.check_receipt(&receipt, traces)
        {
            receipt.driven_distance_m = computed_m;
        }
        receipt
    }

    fn check_receipt(
        &self,
        receipt: &TripReceipt,
        traces: &[Trace],
    ) -> Result<receipt::TripReport, Error> {
        let witness = self
            .index
            .witness(receipt.driven_path.iter().map(|s| s.way));
        receipt::check(
            receipt,
            &self.quote,
            traces,
            &witness,
            &self.pack,
            &self.domain,
            &self.params,
        )
    }
}

#[test]
fn quote_distance_is_recomputed_from_the_map() {
    let world = World::new();
    let report = world.check_quote(&world.quote).unwrap();
    // Half a block, a block, three blocks north, then two and three quarter blocks: 7.25 blocks of about 100 m.
    let block_ew = segment_mm(node(0, 0).at, node(0, 1).at);
    let block_ns = segment_mm(node(0, 2).at, node(1, 2).at);
    let expected = metres(block_ew * 17 / 4 + block_ns * 3);
    assert!(
        report.distance_m.abs_diff(expected) <= 1,
        "{} vs {expected}",
        report.distance_m
    );
    assert_eq!(report.pickup_gap_m, 10);
    assert_eq!(report.dropoff_gap_m, 0);

    let mut padded = world.quote.clone();
    padded.distance_m += 500;
    assert!(matches!(
        world.check_quote(&padded),
        Err(Error::DistanceMismatch { .. })
    ));
}

#[test]
fn quote_rejects_paths_a_car_cannot_drive() {
    let world = World::new();
    let with_path = |path: Vec<Step>| RouteQuote {
        path,
        ..world.quote.clone()
    };

    // Down the one-way street.
    let mut wrong_way = vec![Step {
        way: 103,
        node: node(3, 2).id,
    }];
    wrong_way.extend((0..3).rev().map(|r| Step {
        way: 202,
        node: node(r, 2).id,
    }));
    assert_eq!(
        world.check_route(&with_path(wrong_way)),
        Err(Error::NotAllowed { step: 1 })
    );

    // Jumping over a corner.
    let mut teleport = route();
    teleport.remove(1);
    assert_eq!(
        world.check_route(&with_path(teleport)),
        Err(Error::NotConnected { step: 1 })
    );

    // A street that is not on the map.
    let mut invented = route();
    invented[1].way = 999;
    assert_eq!(
        world.check_route(&with_path(invented)),
        Err(Error::UnknownWay { step: 1 })
    );

    // A path that starts three blocks from the pickup.
    let far: Vec<Step> = (3..=5)
        .map(|c| Step {
            way: 103,
            node: node(3, c).id,
        })
        .collect();
    assert!(matches!(
        world.check_route(&with_path(far)),
        Err(Error::StartTooFar { .. })
    ));

    assert_eq!(
        world.check_route(&with_path(vec![route()[0]])),
        Err(Error::PathTooShort)
    );
}

#[test]
fn quote_is_tied_to_its_pack_and_its_escrow() {
    let world = World::new();
    let other_pack = RouteQuote {
        pack_id: [8; 32],
        ..world.quote.clone()
    };
    assert_eq!(world.check_quote(&other_pack), Err(Error::WrongPack));

    // The same streets from a map with a different root.
    let witness = world.index.witness(world.quote.path.iter().map(|s| s.way));
    let forged = Pack {
        id: world.pack.id,
        proof_root: [9; 32],
    };
    assert!(matches!(
        quote::check(&world.quote, &witness, &forged, &world.params),
        Err(Error::ChunkNotInPack { .. })
    ));

    let elsewhere = Domain {
        chain_id: 137,
        ..world.domain
    };
    assert_ne!(
        world.quote.hash(&world.domain),
        world.quote.hash(&elsewhere)
    );
    let cheaper = RouteQuote {
        duration_s: 1,
        ..world.quote.clone()
    };
    assert_ne!(world.quote.hash(&world.domain), cheaper.hash(&world.domain));
}

#[test]
fn receipt_holds_for_an_honest_trip() {
    let world = World::new();
    let traces = world.traces(world.drive(150), world.drive(-120));
    let receipt = world.receipt(&traces, route());
    let report = world.check_receipt(&receipt, &traces).unwrap();
    assert!(
        report.driven_distance_m.abs_diff(world.quote.distance_m) <= 5,
        "{report:?}"
    );
    assert!(
        report.reached_dropoff && report.pickup_gap_m <= 15 && report.traces == 2,
        "{report:?}"
    );
    assert_eq!(report.longest_gap_ms, 2000);

    // Either log alone is enough.
    assert_eq!(
        world.check_receipt(&receipt, &traces[1..]).unwrap().traces,
        1
    );
    let alone = world.check_receipt(&receipt, &traces[..1]);
    assert!(
        alone.is_ok() || matches!(alone, Err(Error::DistanceMismatch { .. })),
        "{alone:?}"
    );
    assert_eq!(world.check_receipt(&receipt, &[]), Err(Error::EmptyTrace));
}

#[test]
fn receipt_cannot_claim_roads_nobody_drove() {
    let world = World::new();
    let traces = world.traces(world.drive(0), world.drive(0));

    // The honest route with a two-block excursion along row 3 and back added in the middle.
    let mut padded = route();
    let at = padded
        .iter()
        .position(|s| {
            *s == Step {
                way: 202,
                node: node(3, 2).id,
            }
        })
        .unwrap();
    let excursion = [node(3, 1), node(3, 0), node(3, 1), node(3, 2)].map(|n| Step {
        way: 103,
        node: n.id,
    });
    padded.splice(at + 1..at + 1, excursion);
    let receipt = world.receipt(&traces, padded);
    assert!(matches!(
        world.check_receipt(&receipt, &traces),
        Err(Error::TooFast { .. })
    ));

    // A different street altogether: row 4 instead of row 3.
    let mut other = route();
    other.truncate(at + 1);
    other.push(Step {
        way: 202,
        node: node(4, 2).id,
    });
    other.extend((3..=5).map(|c| Step {
        way: 104,
        node: node(4, c).id,
    }));
    let receipt = world.receipt(&traces, other);
    assert!(matches!(
        world.check_receipt(&receipt, &traces),
        Err(Error::OffPath { .. })
    ));
}

#[test]
fn receipt_reports_a_trip_that_stopped_short() {
    let world = World::new();
    let mut fixes = world.drive(0);
    fixes.truncate(fixes.len() / 2);
    let traces = world.traces(fixes.clone(), fixes);
    let receipt = world.receipt(&traces, route());
    let report = world.check_receipt(&receipt, &traces).unwrap();
    assert!(
        !report.reached_dropoff && report.dropoff_gap_m > 200,
        "{report:?}"
    );
    assert!(
        report.driven_distance_m < world.quote.distance_m * 6 / 10,
        "{report:?}"
    );
}

#[test]
fn trace_cannot_be_rewritten_or_faked() {
    let world = World::new();
    let honest = world.traces(world.drive(0), world.drive(0));
    let receipt = world.receipt(&honest, route());
    let with = |edit: &dyn Fn(&mut Trace)| {
        let mut traces = honest.clone();
        edit(&mut traces[1]);
        world.check_receipt(&receipt, &traces)
    };

    // Moving a fix after the fact breaks the signed head that covers it.
    assert_eq!(
        with(&|t| t.fixes[3].at.lat += 10),
        Err(Error::BadHead { head: 0 })
    );
    assert_eq!(
        with(&|t| t.fixes[3].mock = true),
        Err(Error::MockLocation { fix: 3 })
    );
    assert_eq!(
        with(&|t| t.fixes.swap(3, 4)),
        Err(Error::FixesOutOfOrder { fix: 4 })
    );
    assert_eq!(
        with(&|t| t.heads[0].signature[5] ^= 1),
        Err(Error::BadSignature { head: 0 })
    );
    assert_eq!(
        with(&|t| t.heads.last_mut().unwrap().countersignature = None),
        Err(Error::NotCountersigned)
    );
    assert!(matches!(
        with(&|t| {
            t.heads.pop();
        }),
        Err(Error::BadHead { .. })
    ));
    assert_eq!(
        with(&|t| t.certificate[10] ^= 1),
        Err(Error::BadCertificate)
    );
    // A trace key the rider's wallet never certified.
    assert_eq!(
        with(&|t| t.peer_key = hardware(9).1),
        Err(Error::BadCertificate)
    );

    // A fresh log that jumps a kilometre in two seconds, properly signed.
    let mut jump = world.drive(0);
    jump[5].at.lon += 100_000;
    let traces = world.traces(world.drive(0), jump);
    let receipt = world.receipt(&traces, route());
    assert_eq!(
        world.check_receipt(&receipt, &traces),
        Err(Error::TooFast { fix: 5 })
    );

    // A log signed for another ride does not fit this one.
    let reused = TripReceipt {
        ride_id: 43,
        ..world.receipt(&honest, route())
    };
    assert_eq!(
        world.check_receipt(&reused, &honest),
        Err(Error::BadCertificate)
    );

    // Heads signed 28 s apart are too sparse for a court that asks for one every 20 s.
    let strict = World {
        params: Params {
            max_head_interval_ms: 20_000,
            ..world.params
        },
        ..World::new()
    };
    assert_eq!(
        strict.check_receipt(&receipt, &honest),
        Err(Error::HeadsTooFarApart { head: 0 })
    );
}

#[test]
fn receipt_is_tied_to_the_quote_and_fare() {
    let world = World::new();
    let traces = world.traces(world.drive(0), world.drive(0));
    let receipt = world.receipt(&traces, route());

    let mut dearer = receipt.clone();
    dearer.fare[31] ^= 1;
    assert_eq!(world.check_receipt(&dearer, &traces), Err(Error::WrongFare));
    let other_quote = TripReceipt {
        quote_hash: [1; 32],
        ..receipt.clone()
    };
    assert_eq!(
        world.check_receipt(&other_quote, &traces),
        Err(Error::WrongQuote)
    );
    let other_head = TripReceipt {
        rider_head: [1; 32],
        ..receipt.clone()
    };
    assert_eq!(
        world.check_receipt(&other_head, &traces),
        Err(Error::WrongHead { role: Role::Rider })
    );
    let later = TripReceipt {
        ended_at: receipt.ended_at + 600,
        ..receipt.clone()
    };
    assert_eq!(world.check_receipt(&later, &traces), Err(Error::WrongTimes));
    assert_ne!(receipt.hash(&world.domain), later.hash(&world.domain));
}

#[test]
fn measuring_trims_to_the_pickup_and_dropoff() {
    let points = [node(0, 0).at, node(0, 1).at, node(0, 2).at];
    let whole = measure(&points, points[0], points[2]);
    assert_eq!(whole.distance_mm, 2 * segment_mm(points[0], points[1]));
    let mid = Point {
        lat: LAT0,
        lon: LON0 + DLON / 2,
    };
    let trimmed = measure(&points, mid, points[2]);
    assert!(trimmed.distance_mm.abs_diff(whole.distance_mm * 3 / 4) <= 2);
}
