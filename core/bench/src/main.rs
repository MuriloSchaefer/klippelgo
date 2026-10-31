//! KlippelGo's scalability benchmark.
//!
//!   klippelgo-bench model   [--param value ...] [--measured run.json]   will the shared services hold?
//!   klippelgo-bench gossip  [--param value ...] [--node path] [--out dir]   load the real router and measure
//!   klippelgo-bench compare <run.json> <run.json>                            what changed between two runs
//!   klippelgo-bench probe   [--phones 20 --rate 5 --size 900 --publishers N] the router alone, no rides
//!   klippelgo-bench params                                                   every parameter and its default
//!
//! See README.md next to this file.

mod gossip;
mod model;
mod params;

use params::Params;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .with_writer(std::io::stderr)
        .init();
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.first().map(String::as_str) {
        Some("model") => model_command(&args[1..]),
        Some("gossip") => gossip_command(&args[1..]),
        Some("compare") => compare_command(&args[1..]),
        Some("probe") => probe_command(&args[1..]),
        Some("params") => {
            print!("{}", params::help());
            Ok(())
        }
        _ => {
            eprintln!("{}", usage());
            std::process::exit(2);
        }
    };
    if let Err(e) = result {
        eprintln!("klippelgo-bench: {e}");
        std::process::exit(1);
    }
}

fn usage() -> String {
    format!(
        "usage: klippelgo-bench <model|gossip|compare|probe|params> [--param value ...]\n\n\
         model    the load the scenario puts on the chain, the bundler, RPC nodes, routers, mirrors and phones\n\
         gossip   runs a square of cells at the busiest cell's traffic through the real router, and measures\n\
         compare  two saved runs side by side\n\
         probe    phones on one topic through the real router, without rides: --phones --rate --size --publishers\n\n\
         Parameters (any command):\n{}",
        params::help()
    )
}

fn runs_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("runs")
}

fn option<'a>(options: &'a [(String, String)], name: &str) -> Option<&'a str> {
    options
        .iter()
        .find(|(k, _)| k == name)
        .map(|(_, v)| v.as_str())
}

fn unknown(options: &[(String, String)], known: &[&str]) -> Result<(), String> {
    match options.iter().find(|(k, _)| !known.contains(&k.as_str())) {
        Some((k, _)) => Err(format!(
            "unknown parameter --{k}; `klippelgo-bench params` lists them"
        )),
        None => Ok(()),
    }
}

fn read_json(path: &str) -> Result<Value, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    serde_json::from_str(&text).map_err(|e| format!("{path}: {e}"))
}

fn model_command(args: &[String]) -> Result<(), String> {
    let (p, options) = Params::from_args(args)?;
    unknown(&options, &["measured", "json"])?;
    let measured = match option(&options, "measured") {
        Some(path) => {
            let run = read_json(path)?;
            Some(
                model::Measured::from_run(&run, path.to_string())
                    .ok_or(format!("{path} is not a gossip run"))?,
            )
        }
        None => None,
    };
    let report = model::evaluate(&p, measured.as_ref());
    if option(&options, "json").is_some() {
        println!(
            "{}",
            serde_json::to_string_pretty(&json!({"params": p.to_json(), "model": report.json}))
                .unwrap()
        );
    } else {
        model::print(&p, &report);
    }
    Ok(())
}

fn gossip_command(args: &[String]) -> Result<(), String> {
    let (p, options) = Params::from_args(args)?;
    unknown(&options, &["node", "out", "label"])?;
    let node = option(&options, "node")
        .map(PathBuf::from)
        .unwrap_or_else(gossip::default_node_binary);
    if !node.is_file() {
        return Err(format!(
            "no router at {}: build it with `cargo build --release -p klippelgo-core --bin klippelgo-node`, or pass --node",
            node.display()
        ));
    }
    let results = gossip::run(&p, &node)?;
    let run = json!({
        "kind": "gossip",
        "at": unix_now(),
        "label": option(&options, "label"),
        "code": git_describe(),
        "params": p.to_json(),
        "changed": p.changed(),
        "results": results,
    });
    print_gossip(&run);

    let dir = option(&options, "out")
        .map(PathBuf::from)
        .unwrap_or_else(runs_dir);
    std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let name = format!("gossip-{}.json", run["at"].as_u64().unwrap());
    let text = serde_json::to_string_pretty(&run).unwrap();
    std::fs::write(dir.join(&name), &text).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("gossip-latest.json"), &text).map_err(|e| e.to_string())?;
    println!();
    println!("  Saved {}", dir.join(name).display());
    Ok(())
}

fn probe_command(args: &[String]) -> Result<(), String> {
    let (_, options) = Params::from_args(args)?;
    unknown(
        &options,
        &["node", "phones", "seconds", "rate", "size", "publishers"],
    )?;
    let node = option(&options, "node")
        .map(PathBuf::from)
        .unwrap_or_else(gossip::default_node_binary);
    let phones = option(&options, "phones")
        .unwrap_or("20")
        .parse()
        .map_err(|_| "--phones takes a number")?;
    let seconds = option(&options, "seconds")
        .unwrap_or("20")
        .parse()
        .map_err(|_| "--seconds takes a number")?;
    let rate = option(&options, "rate")
        .unwrap_or("1")
        .parse()
        .map_err(|_| "--rate takes a number")?;
    let size = option(&options, "size")
        .unwrap_or("0")
        .parse()
        .map_err(|_| "--size takes a number")?;
    let publishers = option(&options, "publishers")
        .map_or(Ok(phones), str::parse)
        .map_err(|_| "--publishers takes a number")?;
    let result = gossip::probe(&node, phones, seconds, rate, size, publishers.min(phones))?;
    println!("{}", serde_json::to_string(&result).unwrap());
    Ok(())
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// The commit the run measured, and whether the working tree had changes on top of it.
fn git_describe() -> Value {
    let git = |args: &[&str]| {
        Command::new("git")
            .args(args)
            .current_dir(env!("CARGO_MANIFEST_DIR"))
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
    };
    json!({
        "commit": git(&["rev-parse", "--short", "HEAD"]),
        "dirty": git(&["status", "--porcelain", "--", ".."]).map(|s| !s.is_empty()),
    })
}

fn pct(v: &Value) -> String {
    v.as_f64()
        .map(|x| format!("{:.1}%", 100.0 * x))
        .unwrap_or_else(|| "-".into())
}

fn num(v: &Value) -> String {
    v.as_f64().map(model::si).unwrap_or_else(|| "-".into())
}

fn ms(v: &Value) -> String {
    match (v["p50"].as_f64(), v["p95"].as_f64(), v["p99"].as_f64()) {
        (Some(a), Some(b), Some(c)) => format!("{a:.0} / {b:.0} / {c:.0} ms"),
        _ => "-".into(),
    }
}

/// The figures a run is judged by, as (label, value as text, value as number, higher is better).
fn headline(run: &Value) -> Vec<(&'static str, String, Option<f64>, bool)> {
    let r = &run["results"];
    let (d, ri, ro, g) = (
        &r["drivers"],
        &r["riders"],
        &r["router"],
        &r["load_generator"],
    );
    vec![
        (
            "Drivers who saw a request while it was open",
            pct(&d["requests_seen"]),
            d["requests_seen"].as_f64(),
            true,
        ),
        (
            "  ...dropped because their list was full",
            pct(&d["requests_dropped_list_full"]),
            d["requests_dropped_list_full"].as_f64(),
            false,
        ),
        (
            "  time to first see it, p50 / p95 / p99",
            ms(&d["request_first_seen_ms"]),
            d["request_first_seen_ms"]["p95"].as_f64(),
            false,
        ),
        (
            "Offers that reached their rider in time",
            pct(&ri["offers_seen"]),
            ri["offers_seen"].as_f64(),
            true,
        ),
        (
            "  time to first see it, p50 / p95 / p99",
            ms(&ri["offer_first_seen_ms"]),
            ri["offer_first_seen_ms"]["p95"].as_f64(),
            false,
        ),
        (
            "Requests left with no offer",
            pct(&ri["unmatched"]),
            ri["unmatched"].as_f64(),
            false,
        ),
        (
            "Routes that reached the chosen driver",
            pct(&d["routes_seen"]),
            d["routes_seen"].as_f64(),
            true,
        ),
        (
            "Positions that reached their rider",
            pct(&ri["positions_delivered"]),
            ri["positions_delivered"].as_f64(),
            true,
        ),
        (
            "  latency, p50 / p95 / p99",
            ms(&r["messages"]["position"]["latency_ms"]),
            r["messages"]["position"]["latency_ms"]["p95"].as_f64(),
            false,
        ),
        (
            "Driver phone: messages a second (model)",
            format!(
                "{} ({})",
                num(&d["msgs_per_s"]),
                num(&d["model_msgs_per_s"])
            ),
            d["msgs_per_s"].as_f64(),
            false,
        ),
        (
            "Driver phone: MB an hour",
            num(&d["mb_per_hour"]),
            d["mb_per_hour"].as_f64(),
            false,
        ),
        (
            "Driver phone: largest drain (inbox holds 4096)",
            num(&d["largest_drain"]),
            d["largest_drain"].as_f64(),
            false,
        ),
        (
            "Rider phone: messages a second",
            num(&ri["msgs_per_s"]),
            ri["msgs_per_s"].as_f64(),
            false,
        ),
        (
            "Router: deliveries a second (model)",
            format!(
                "{} ({})",
                num(&ro["deliveries_per_s"]),
                num(&ro["model_deliveries_per_s"])
            ),
            ro["deliveries_per_s"].as_f64(),
            true,
        ),
        (
            "Router: CPU cores",
            format!("{:.2}", ro["cpu_cores"].as_f64().unwrap_or(0.0)),
            ro["cpu_cores"].as_f64(),
            false,
        ),
        (
            "Router: CPU seconds per million deliveries",
            format!(
                "{:.2}",
                ro["cpu_s_per_million_deliveries"].as_f64().unwrap_or(0.0)
            ),
            ro["cpu_s_per_million_deliveries"].as_f64(),
            false,
        ),
        (
            "Router: memory",
            format!("{:.0} MB", ro["rss_mb"].as_f64().unwrap_or(0.0)),
            ro["rss_mb"].as_f64(),
            false,
        ),
        (
            "Load generator: CPU cores of those available",
            format!(
                "{:.1} of {}",
                g["cpu_cores"].as_f64().unwrap_or(0.0),
                g["cores_available"]
            ),
            None,
            false,
        ),
        (
            "Load generator: most behind schedule",
            format!("{:.0} ms", g["most_behind_ms"].as_f64().unwrap_or(0.0)),
            None,
            false,
        ),
    ]
}

fn print_gossip(run: &Value) {
    let r = &run["results"];
    println!();
    println!(
        "Gossip: {} cells, {:.3} new rides a second in each, {} drivers each on {} phones; {} phones in all{}",
        r["cells"],
        r["rides_per_s_per_cell"].as_f64().unwrap_or(0.0),
        r["drivers_per_cell"],
        run["params"]["driver_nodes"],
        r["phones"],
        match run["changed"].as_array() {
            Some(c) if !c.is_empty() => format!(" ({})", c.iter().filter_map(|v| v.as_str()).collect::<Vec<_>>().join(", ")),
            _ => String::new(),
        }
    );
    println!();
    for (label, value, _, _) in headline(run) {
        println!("  {label:<52} {value}");
    }
    println!();
    println!(
        "  {:<10} {:>14} {:>14} {:>11} {:>26}",
        "message", "sent/s/cell", "model", "delivered", "latency p50/p95/p99"
    );
    for kind in ["request", "offer", "close", "route", "position"] {
        let m = &r["messages"][kind];
        println!(
            "  {:<10} {:>14.2} {:>14.2} {:>11} {:>26}",
            kind,
            m["sent_per_s_per_cell"].as_f64().unwrap_or(0.0),
            m["model_per_s_per_cell"].as_f64().unwrap_or(0.0),
            pct(&m["delivered"]),
            ms(&m["latency_ms"])
        );
    }
    let g = &r["load_generator"];
    if g["most_behind_ms"].as_f64().unwrap_or(0.0) > 500.0
        || g["cpu_cores"].as_f64().unwrap_or(0.0)
            > 0.8 * g["cores_available"].as_f64().unwrap_or(1.0)
    {
        println!();
        println!("  This computer was the limit for part of the run: compare runs on the same machine, or lower");
        println!("  --driver-nodes, --rider-nodes or --grid.");
    }
}

fn compare_command(args: &[String]) -> Result<(), String> {
    let [a, b] = args else {
        return Err("compare takes two run files".into());
    };
    let (ra, rb) = (read_json(a)?, read_json(b)?);
    let label = |r: &Value, path: &str| {
        r["label"].as_str().map(String::from).unwrap_or_else(|| {
            Path::new(path)
                .file_stem()
                .unwrap()
                .to_string_lossy()
                .into()
        })
    };
    println!("  {:<52} {:>24} {:>24}", "", label(&ra, a), label(&rb, b));
    for ((name, va, na, better_up), (_, vb, nb, _)) in headline(&ra).into_iter().zip(headline(&rb))
    {
        let change = match (na, nb) {
            (Some(x), Some(y)) if x != 0.0 => {
                let d = (y - x) / x.abs();
                let good = (d > 0.0) == better_up;
                format!(
                    "{:+.0}%{}",
                    100.0 * d,
                    if d.abs() < 0.05 {
                        ""
                    } else if good {
                        " better"
                    } else {
                        " worse"
                    }
                )
            }
            _ => String::new(),
        };
        println!("  {name:<52} {va:>24} {vb:>24}  {change}");
    }
    let (pa, pb) = (&ra["params"], &rb["params"]);
    let differ: Vec<String> = pa
        .as_object()
        .into_iter()
        .flatten()
        .filter(|(k, v)| pb.get(k.as_str()) != Some(v))
        .map(|(k, v)| format!("{k}: {v} -> {}", pb.get(k.as_str()).unwrap_or(&Value::Null)))
        .collect();
    if !differ.is_empty() {
        println!();
        println!("  Parameters that differ: {}", differ.join(", "));
    }
    Ok(())
}
