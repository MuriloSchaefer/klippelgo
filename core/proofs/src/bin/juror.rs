//! Checks the evidence of a ride against a map pack and says what happened, so a juror does not have to take
//! either party's word for the route, the distance or where the trip ended.
//!
//!   klippelgo-juror <pack dir> <evidence.json> [params.json]
//!
//! The pack is public; the evidence comes from a party to the dispute. Before trusting the result, check that
//! the pack is the one governance activated: `MapRegistry.proofRootOf(<pack id>)` must return the proof root
//! printed here. `params.json` holds `MapRegistry.verifierParams` when they differ from the launch values.
//!
//! Exits 0 when every check holds, 1 when one fails and 2 when the files cannot be read.

use klippelgo_proofs::evidence::Evidence;
use klippelgo_proofs::explain::explain;
use klippelgo_proofs::{hex, MapPack};
use klippelgo_verify::{quote, receipt, Params, RouteQuote, Trace, TripReceipt};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 || args.len() > 3 {
        eprintln!("Usage: klippelgo-juror <pack dir> <evidence.json> [params.json]");
        return ExitCode::from(2);
    }
    match run(&args[0], &args[1], args.get(2).map(String::as_str)) {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(2)
        }
    }
}

fn run(
    pack_dir: &str,
    evidence_path: &str,
    params_path: Option<&str>,
) -> Result<bool, Box<dyn std::error::Error>> {
    let pack = MapPack::open(pack_dir)?;
    let evidence = Evidence::parse(&std::fs::read_to_string(evidence_path)?)?;
    let params = match params_path {
        Some(path) => read_params(&std::fs::read_to_string(path)?)?,
        None => Params::default(),
    };
    let domain = evidence.domain();
    let route_quote = RouteQuote::try_from(&evidence.quote)?;

    println!(
        "Map pack     {} {}",
        pack.manifest.region, pack.manifest.version
    );
    println!("Pack id      {}", hex::encode(&pack.id));
    println!("Proof root   {}", hex::encode(&pack.manifest.proof_root));
    println!("             MapRegistry.proofRootOf(pack id) must return this root.\n");

    let mut holds = true;
    println!("Quote        {}", hex::encode(&route_quote.hash(&domain)));
    let witness = pack.witness(route_quote.path.iter().map(|s| s.way))?;
    match quote::check(&route_quote, &witness, &pack.pack(), &params) {
        Ok(report) => println!(
            "  holds      {} m by road; the route passes {} m from the pickup point and {} m from the drop-off",
            report.distance_m, report.pickup_gap_m, report.dropoff_gap_m
        ),
        Err(e) => {
            holds = false;
            println!("  FAILS      {}", explain(&e));
        }
    }

    let Some(receipt_file) = &evidence.receipt else {
        println!("\nNo receipt in the evidence: only the quote was checked.");
        return Ok(holds);
    };
    let trip = TripReceipt::try_from(receipt_file)?;
    let traces = evidence
        .traces
        .iter()
        .map(Trace::try_from)
        .collect::<Result<Vec<_>, _>>()?;
    println!("\nReceipt      {}", hex::encode(&trip.hash(&domain)));
    let witness = pack.witness(trip.driven_path.iter().map(|s| s.way))?;
    match receipt::check(
        &trip,
        &route_quote,
        &traces,
        &witness,
        &pack.pack(),
        &domain,
        &params,
    ) {
        Ok(report) => {
            let logs = if report.traces == 2 {
                "both phones' logs"
            } else {
                "one phone's log"
            };
            println!("  holds      checked against {logs}");
            println!(
                "  driven     {} m in {} min {} s (quoted: {} m)",
                report.driven_distance_m,
                report.duration_s / 60,
                report.duration_s % 60,
                report.quoted_distance_m
            );
            println!(
                "  started    {} m from the quoted pickup point",
                report.pickup_gap_m
            );
            println!(
                "  ended      {} m from the quoted drop-off: {}",
                report.dropoff_gap_m,
                if report.reached_dropoff {
                    "the trip reached it"
                } else {
                    "the trip did NOT reach it"
                }
            );
            println!(
                "  GPS        longest silence {} s",
                report.longest_gap_ms / 1000
            );
        }
        Err(e) => {
            holds = false;
            println!("  FAILS      {}", explain(&e));
        }
    }
    Ok(holds)
}

/// Reads `MapRegistry.VerifierParams` as JSON with the contract's field names.
fn read_params(json: &str) -> Result<Params, Box<dyn std::error::Error>> {
    let value: serde_json::Value = serde_json::from_str(json)?;
    let field = |name: &str| {
        value[name]
            .as_u64()
            .and_then(|v| u32::try_from(v).ok())
            .ok_or_else(|| format!("params: {name} is missing"))
    };
    Ok(Params {
        endpoint_tolerance_m: field("endpointToleranceM")?,
        distance_tolerance_bps: field("distanceToleranceBps")?,
        trace_margin_m: field("traceMarginM")?,
        max_accuracy_m: field("maxAccuracyM")?,
        detour_tolerance_bps: field("detourToleranceBps")?,
        max_speed_cm_s: field("maxSpeedCmS")?,
        max_accel_cm_s2: field("maxAccelCmS2")?,
        max_head_interval_ms: field("maxHeadIntervalMs")?,
    })
}
