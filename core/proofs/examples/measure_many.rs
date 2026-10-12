//! Measures many sampled Valhalla routes on a map pack and tallies where the verifier and Valhalla disagree.
//! Used when a pack or a Valhalla version changes, to see that routes still translate.
//!
//!   cargo run --release -p klippelgo-proofs --example measure_many -- <pack dir> <samples dir>
//!
//! Each sample is a JSON file `{"vehicle", "route": <Valhalla route>, "attributes": <Valhalla trace_attributes>}`
//! (mappack/sample-routes.py writes them).

use klippelgo_proofs::explain::explain;
use klippelgo_proofs::{valhalla, MapPack};
use klippelgo_verify::{quote, Params, Vehicle};
use std::collections::BTreeMap;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [pack_dir, samples] = args.as_slice() else {
        return Err("usage: measure_many <pack dir> <samples dir>".into());
    };
    let pack = MapPack::open(pack_dir)?;
    let mut files: Vec<_> = std::fs::read_dir(samples)?
        .map(|e| e.map(|e| e.path()))
        .collect::<Result<_, _>>()?;
    files.sort();

    let (mut held, mut worst_bps, mut total_m) = (0u32, 0u64, 0u64);
    let mut failures: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for file in &files {
        let sample: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(file)?)?;
        let vehicle = if sample["vehicle"] == "motorcycle" {
            Vehicle::Motorcycle
        } else {
            Vehicle::Car
        };
        let name = file.file_name().unwrap().to_string_lossy().to_string();
        let result = (|| -> Result<(u32, u32), String> {
            let route =
                valhalla::parse_route(&sample["route"].to_string()).map_err(|e| e.to_string())?;
            let edges = valhalla::parse_edges(&sample["attributes"].to_string())
                .map_err(|e| e.to_string())?;
            let steps = valhalla::steps(&edges, |way| pack.way(way)).map_err(|e| e.to_string())?;
            let witness = pack
                .witness(steps.iter().map(|s| s.way))
                .map_err(|e| e.to_string())?;
            let (from, to) = (route.points[0], route.points[route.points.len() - 1]);
            let report = quote::check_route(
                &steps,
                vehicle,
                from,
                to,
                &witness,
                &pack.pack(),
                &Params::default(),
            )
            .map_err(|e| match e {
                klippelgo_verify::Error::NotAllowed { step }
                | klippelgo_verify::Error::NotConnected { step } => {
                    format!("{} (way {})", explain(&e), steps[step].way)
                }
                _ => explain(&e),
            })?;
            Ok((report.distance_m, route.length_m))
        })();
        match result {
            Ok((ours, theirs)) => {
                held += 1;
                total_m += ours as u64;
                worst_bps =
                    worst_bps.max(ours.abs_diff(theirs) as u64 * 10_000 / theirs.max(1) as u64);
            }
            Err(why) => {
                // Group by the kind of failure, without the numbers that differ per route.
                let kind: String = why.chars().filter(|c| !c.is_ascii_digit()).collect();
                failures
                    .entry(kind)
                    .or_default()
                    .push(format!("{name}: {why}"));
            }
        }
    }
    println!(
        "{held} of {} routes hold, {} km in all",
        files.len(),
        total_m / 1000
    );
    println!(
        "largest difference from Valhalla's own length: {}.{:02}%",
        worst_bps / 100,
        worst_bps % 100
    );
    for (kind, examples) in &failures {
        println!("{} × {kind}", examples.len());
        for example in examples.iter().take(5) {
            println!("    {example}");
        }
    }
    Ok(())
}
