//! Measures a Valhalla route on a map pack with the verifier, to see the two agree.
//!
//!   cargo run -p klippelgo-proofs --example measure -- <pack dir> <trace_attributes.json> <lat,lon> <lat,lon> [car|motorcycle]
//!
//! `trace_attributes.json` is Valhalla's answer for the route's shape (see mappack/README.md). The points are
//! the pickup and drop-off in degrees.

use klippelgo_proofs::explain::explain;
use klippelgo_proofs::{valhalla, MapPack};
use klippelgo_verify::{quote, Params, Point, Vehicle};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 4 {
        return Err("usage: measure <pack dir> <trace_attributes.json> <lat,lon> <lat,lon> [car|motorcycle]".into());
    }
    let point = |text: &str| -> Result<Point, Box<dyn std::error::Error>> {
        let (lat, lon) = text.split_once(',').ok_or("a point is lat,lon")?;
        Ok(Point {
            lat: (lat.parse::<f64>()? * 1e7).round() as i32,
            lon: (lon.parse::<f64>()? * 1e7).round() as i32,
        })
    };
    let vehicle = match args.get(4).map(String::as_str) {
        Some("motorcycle") => Vehicle::Motorcycle,
        _ => Vehicle::Car,
    };
    let pack = MapPack::open(&args[0])?;
    let edges = valhalla::parse_edges(&std::fs::read_to_string(&args[1])?)?;
    let steps = valhalla::steps(&edges, |way| pack.way(way))?;
    let witness = pack.witness(steps.iter().map(|s| s.way))?;
    let bytes: usize = witness
        .chunks
        .iter()
        .map(|c| c.bytes.len() + c.proof.len() * 32)
        .sum();
    println!(
        "{} edges, {} steps, witness of {} chunks ({} bytes)",
        edges.len(),
        steps.len(),
        witness.chunks.len(),
        bytes
    );
    match quote::check_route(
        &steps,
        vehicle,
        point(&args[2])?,
        point(&args[3])?,
        &witness,
        &pack.pack(),
        &Params::default(),
    ) {
        Ok(report) => println!(
            "{} m by the verifier; pickup {} m and drop-off {} m from the route",
            report.distance_m, report.pickup_gap_m, report.dropoff_gap_m
        ),
        Err(e) => return Err(explain(&e).into()),
    }
    Ok(())
}
