//! Turns a simulated ride (mappack/tools/simulate-ride.py) into the evidence two phones would hold after it: a
//! route quote, both GPS logs with signed and countersigned heads, and the trip receipt. Everything is built
//! the way the apps build it, with throwaway keys standing in for the wallets and the phones' hardware keys.
//! Set ONLY_MATCHED=1 to write the receipt from the map-matched log even when the trip followed its quote.
//!
//!   cargo run -p klippelgo-proofs --example simulate_ride -- <pack dir> <ride.json> <evidence.json>

use k256::ecdsa::SigningKey as WalletKey;
use klippelgo_proofs::evidence::{Evidence, FixFile, QuoteFile, ReceiptFile, TraceFile};
use klippelgo_proofs::explain::explain;
use klippelgo_proofs::{valhalla, MapPack};
use klippelgo_verify::sig::TraceKey;
use klippelgo_verify::trace::{key_digest, Log, RideRef};
use klippelgo_verify::{
    keccak256, quote, receipt, Address, Domain, Fix, Params, Point, Role, RouteQuote, TripReceipt,
    Vehicle,
};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::SigningKey as HardwareKey;

type Failure = Box<dyn std::error::Error>;

/// Fixes between two signed heads: one every 30 seconds at a fix every 2 seconds.
const HEAD_EVERY: usize = 15;

fn main() -> Result<(), Failure> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [pack_dir, ride_path, out] = args.as_slice() else {
        return Err("usage: simulate_ride <pack dir> <ride.json> <evidence.json>".into());
    };
    let pack = MapPack::open(pack_dir)?;
    let ride: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(ride_path)?)?;
    let params = Params::default();
    let domain = Domain {
        chain_id: 31337,
        escrow: [0xe5; 20],
    };
    let vehicle = if ride["vehicle"] == "motorcycle" {
        Vehicle::Motorcycle
    } else {
        Vehicle::Car
    };
    let (rider, rider_address) = wallet(1);
    let (driver, driver_address) = wallet(2);

    // The rider's phone: route, path, verifier, quote.
    let route = valhalla::parse_route(&ride["route"].to_string())?;
    let path = valhalla::steps(
        &valhalla::parse_edges(&ride["attributes"].to_string())?,
        |way| pack.way(way),
    )?;
    let (pickup, dropoff) = (route.points[0], route.points[route.points.len() - 1]);
    let witness = pack.witness(path.iter().map(|s| s.way))?;
    let measured = quote::check_route(
        &path,
        vehicle,
        pickup,
        dropoff,
        &witness,
        &pack.pack(),
        &params,
    )
    .map_err(|e| explain(&e))?;
    let mut fare = [0u8; 32];
    fare[29..].copy_from_slice(&[0x03, 0x92, 0x10]); // R$ 23,40 in BRZ
    let route_quote = RouteQuote {
        pack_id: pack.id,
        pickup,
        dropoff,
        vehicle,
        path,
        distance_m: measured.distance_m,
        duration_s: route.time_s,
        salt: keccak256(&[b"simulated ride"]),
        fare,
        token: [0xb2; 20],
        rider: rider_address,
        driver: driver_address,
        expiry: 1_800_000_600,
        previous: [0; 32],
    };
    // The driver's phone checks the same quote on its own copy of the pack before accepting.
    quote::check(&route_quote, &witness, &pack.pack(), &params).map_err(|e| explain(&e))?;
    let ride_ref = RideRef {
        domain,
        ride_id: 1,
        quote_hash: route_quote.hash(&domain),
    };

    // Both phones log the trip, sign a head every 30 s and countersign each other's.
    let (rider_hw, rider_key) = hardware(3);
    let (driver_hw, driver_key) = hardware(4);
    let rider_cert = wallet_sign(&rider, &key_digest(&ride_ref, Role::Rider, &rider_key));
    let driver_cert = wallet_sign(&driver, &key_digest(&ride_ref, Role::Driver, &driver_key));
    let rider_log = record(
        &ride_ref,
        Role::Rider,
        rider_key,
        &fixes(&ride["riderFixes"])?,
        &rider_hw,
        &driver_hw,
    )
    .finish(rider_cert, driver_key, driver_cert);
    let driver_log = record(
        &ride_ref,
        Role::Driver,
        driver_key,
        &fixes(&ride["driverFixes"])?,
        &driver_hw,
        &rider_hw,
    )
    .finish(driver_cert, rider_key, rider_cert);

    // At arrival both sign the receipt. The driven path is the quoted route if the logs followed it. Otherwise
    // the driver's log is map-matched; map-matching may invent a short detour up a side street, which the
    // verifier refuses, so those are dropped until it accepts the path.
    let traces = [rider_log, driver_log];
    let mut trip = TripReceipt {
        ride_id: ride_ref.ride_id,
        quote_hash: ride_ref.quote_hash,
        rider_head: traces[0].heads.last().unwrap().head,
        driver_head: traces[1].heads.last().unwrap().head,
        driven_path: Vec::new(),
        driven_distance_m: 0,
        started_at: traces[1].fixes[0].t_ms / 1000,
        ended_at: traces[1].fixes.last().unwrap().t_ms / 1000,
        fare,
    };
    let measure = |path: &[klippelgo_verify::Step], witness: &klippelgo_verify::Witness| {
        let draft = TripReceipt {
            driven_path: path.to_vec(),
            ..trip.clone()
        };
        receipt::measure(
            &draft,
            &route_quote,
            &traces,
            witness,
            &pack.pack(),
            &domain,
            &params,
        )
    };
    let quoted = pack.witness(route_quote.path.iter().map(|s| s.way))?;
    let (driven_path, report) = match measure(&route_quote.path, &quoted) {
        Ok(report) if std::env::var_os("ONLY_MATCHED").is_none() => {
            (route_quote.path.clone(), report)
        }
        _ => {
            let matched = valhalla::steps(
                &valhalla::parse_edges(&ride["matched"].to_string())?,
                |way| pack.way(way),
            )?;
            let witness = pack.witness(matched.iter().map(|s| s.way))?;
            let matched_steps = matched.len();
            let (path, report) =
                valhalla::without_false_spurs(matched, |path| measure(path, &witness))
                    .map_err(|e| explain(&e))?;
            if path.len() != matched_steps {
                println!(
                    "dropped {} steps of detours the log does not back",
                    matched_steps - path.len()
                );
            }
            (path, report)
        }
    };
    trip.driven_path = driven_path;
    trip.driven_distance_m = report.driven_distance_m;

    let evidence = Evidence {
        chain_id: domain.chain_id,
        escrow: domain.escrow,
        quote: QuoteFile::from(&route_quote),
        receipt: Some(ReceiptFile::from(&trip)),
        traces: traces.iter().map(TraceFile::from).collect(),
    };
    std::fs::write(out, evidence.to_json())?;
    println!(
        "quoted {} m (Valhalla: {} m), driven {} m, {} fixes per phone, evidence {} KB",
        route_quote.distance_m,
        route.length_m,
        trip.driven_distance_m,
        traces[1].fixes.len(),
        std::fs::metadata(out)?.len() / 1024
    );
    Ok(())
}

fn fixes(value: &serde_json::Value) -> Result<Vec<Fix>, Failure> {
    let files: Vec<FixFile> = serde_json::from_value(value.clone())?;
    Ok(files
        .iter()
        .map(|f| Fix {
            t_ms: f.t,
            at: Point {
                lat: f.lat,
                lon: f.lon,
            },
            accuracy_cm: f.accuracy_cm,
            speed_cm_s: f.speed_cm_s,
            mock: f.mock,
        })
        .collect())
}

fn record(
    ride: &RideRef,
    role: Role,
    key: TraceKey,
    fixes: &[Fix],
    own: &HardwareKey,
    other: &HardwareKey,
) -> Log {
    let mut log = Log::new(ride, role, key);
    for (i, fix) in fixes.iter().enumerate() {
        assert!(log.push(*fix));
        if (i + 1) % HEAD_EVERY == 0 || i + 1 == fixes.len() {
            let (covered, digest) = log.to_sign().unwrap();
            assert!(log.signed(covered, hardware_sign(own, &digest)));
            assert!(log.countersigned(covered, hardware_sign(other, &digest)));
        }
    }
    log
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
