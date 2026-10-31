//! Every number the benchmark uses, with its default and where it comes from. Any of them can be changed on the
//! command line (`--drivers 20000`), and each run writes the ones it used next to its results, so two runs can
//! be compared knowing what differed.
//!
//! Defaults marked "code" are constants of the app or the core today: change them here when the code changes.
//! Defaults marked "assumed" are guesses about people or about services KlippelGo does not run; they are the
//! first thing to argue about.

use serde_json::{Map, Value};
use std::collections::BTreeMap;

pub struct Param {
    pub name: &'static str,
    pub default: f64,
    pub help: &'static str,
}

const fn p(name: &'static str, default: f64, help: &'static str) -> Param {
    Param {
        name,
        default,
        help,
    }
}

pub const PARAMS: &[Param] = &[
    // People and demand.
    p("drivers", 100_000.0, "drivers registered"),
    p("riders", 1_000_000.0, "riders registered"),
    p(
        "drivers_online",
        0.5,
        "assumed: share of drivers with the app open in the busiest hour",
    ),
    p(
        "rides_per_rider_day",
        0.5,
        "assumed: rides a rider takes per day, on average",
    ),
    p(
        "peak_hour_share",
        0.10,
        "assumed: share of a day's rides in its busiest hour",
    ),
    p(
        "cells",
        300.0,
        "assumed: geohash-5 cells (about 22 km2 each in Brazil) where people are",
    ),
    p(
        "hotspot",
        4.0,
        "assumed: the busiest cell's people and rides, over the average cell's",
    ),
    // A ride, as the app plays it.
    p(
        "ask_s",
        20.0,
        "assumed: seconds a rider's request is open before they lock an offer",
    ),
    p(
        "offers_per_request",
        5.0,
        "assumed: drivers who quote each request",
    ),
    p(
        "quote_delay_s",
        3.0,
        "assumed: seconds from a request on a driver's screen to their quote",
    ),
    p(
        "accept_s",
        10.0,
        "assumed: seconds from the lock to the driver's accept",
    ),
    p(
        "pickup_s",
        300.0,
        "assumed: seconds from accept to the rider in the car",
    ),
    p("ride_s", 900.0, "assumed: seconds of the trip"),
    p(
        "request_ttl_s",
        600.0,
        "code: Matchmaker.REQUEST_TTL; a locked request's close and route are announced until then",
    ),
    p("announce_s", 2.5, "code: Matchmaker.ANNOUNCE_EVERY_MS"),
    p(
        "position_pickup_s",
        4.0,
        "code: Matchmaker.POSITION_EVERY_MS",
    ),
    p(
        "position_ride_s",
        0.9,
        "code: Matchmaker.POSITION_ON_THE_RIDE_EVERY_MS",
    ),
    p(
        "max_requests",
        200.0,
        "code: Matchmaker.MAX_REQUESTS a phone keeps",
    ),
    // Gossip messages: JSON, signed, each with the session key's certificate.
    p("size_request", 1100.0, "bytes of a request"),
    p("size_offer", 950.0, "bytes of an offer"),
    p("size_close", 700.0, "bytes of a close"),
    p(
        "size_route",
        4800.0,
        "bytes of a sealed route; tens of KB across a large city",
    ),
    p("size_position", 900.0, "bytes of a sealed position"),
    p(
        "phone_mb_hour_ok",
        100.0,
        "assumed: mobile data an hour a driver accepts",
    ),
    p(
        "drain_s",
        2.0,
        "code: seconds between the app's drains of the gossip inbox (refresh loop)",
    ),
    p(
        "inbox",
        4096.0,
        "code: gossip::MAX_INBOX, messages kept between two drains",
    ),
    p(
        "mesh_n_high",
        12.0,
        "code: libp2p GossipSub default, most peers a router forwards a topic to",
    ),
    // Chain.
    p(
        "gas_per_ride",
        1_340_000.0,
        "measured: transaction gas of a ride's four operations (contracts/docs/scalability.md)",
    ),
    p("ops_per_ride", 4.0, "code: open, accept, start, complete"),
    p("block_time_s", 2.0, "assumed: Polygon PoS"),
    p("block_gas_limit", 45_000_000.0, "assumed: Polygon PoS"),
    p(
        "chain_share_ok",
        0.25,
        "assumed: share of the chain's block space KlippelGo can take before its gas price rises",
    ),
    p("gas_price_gwei", 30.0, "assumed: Polygon PoS"),
    p("coin_brl", 1.5, "assumed: reais per POL"),
    // Chain RPC, per phone with the app on screen.
    p("poll_s", 2.0, "code: AppViewModel refresh loop"),
    p(
        "recheck_blocks",
        16.0,
        "code: KlippelGoClient.RECHECK_BLOCKS read again on each poll",
    ),
    p(
        "logs_per_poll",
        4.0,
        "code: eth_getLogs calls in changedSince's batch",
    ),
    p(
        "events_per_ride",
        4.0,
        "code: RideOpened, RideAccepted, RideStarted, RideCompleted",
    ),
    p(
        "log_bytes",
        700.0,
        "assumed: bytes of one log in an eth_getLogs JSON answer",
    ),
    p("full_read_s", 30.0, "code: AppViewModel.FULL_READ_EVERY_MS"),
    p(
        "full_read_calls",
        10.0,
        "code: about ten requests in a full read (apps/docs/scalability.md)",
    ),
    p(
        "lookups_per_request",
        2.0,
        "code: a driver's chain reads per new request heard (rider's policy, certificate)",
    ),
    p(
        "rpc_node_rps",
        5_000.0,
        "assumed: requests a second one RPC node answers",
    ),
    p(
        "rpc_node_mbps",
        1_000.0,
        "assumed: megabits a second one RPC node sends",
    ),
    // Bundler.
    p("receipt_poll_s", 0.5, "code: Bundler receipt polling"),
    p(
        "bundler_ops_s",
        20.0,
        "assumed: operations a second one bundler instance lands",
    ),
    // Map packs.
    p(
        "pack_mb",
        73.0,
        "measured: São Paulo pack, 15 km across (core/docs/scalability.md)",
    ),
    p(
        "release_days",
        1.0,
        "assumed: days over which phones fetch a new pack release",
    ),
    p(
        "mirror_mbps",
        1_000.0,
        "assumed: megabits a second one mirror sends",
    ),
    // The gossip load test.
    p(
        "grid",
        3.0,
        "bench: cells per side of the simulated square of cells",
    ),
    p(
        "traffic",
        1.0,
        "bench: share of the busiest cell's rides simulated (1 = the target scale)",
    ),
    p(
        "driver_nodes",
        20.0,
        "bench: phones in driver mode per cell; the cell's drivers are shared among them",
    ),
    p(
        "rider_nodes",
        5.0,
        "bench: phones in rider mode per cell; the cell's riders are shared among them",
    ),
    p(
        "warmup_s",
        15.0,
        "bench: seconds of traffic before measuring",
    ),
    p("duration_s", 60.0, "bench: seconds measured"),
    p(
        "grace_s",
        6.0,
        "bench: seconds to wait for late deliveries after the last message",
    ),
    p("seed", 1.0, "bench: random seed"),
];

/// The parameters of one run: the defaults with the command line's changes.
#[derive(Clone)]
pub struct Params(BTreeMap<&'static str, f64>);

impl Params {
    pub fn from_args(args: &[String]) -> Result<(Self, Vec<(String, String)>), String> {
        let mut values: BTreeMap<&'static str, f64> =
            PARAMS.iter().map(|p| (p.name, p.default)).collect();
        let mut options = Vec::new();
        let mut i = 0;
        while i < args.len() {
            let arg = &args[i];
            let Some(flag) = arg.strip_prefix("--") else {
                return Err(format!("unexpected argument {arg}"));
            };
            let (key, value) = match flag.split_once('=') {
                Some((k, v)) => (k.to_string(), v.to_string()),
                None => {
                    i += 1;
                    let v = args.get(i).ok_or(format!("--{flag} needs a value"))?;
                    (flag.to_string(), v.clone())
                }
            };
            let key = key.replace('-', "_");
            match PARAMS.iter().find(|p| p.name == key) {
                Some(p) => {
                    let v: f64 = value
                        .replace('_', "")
                        .parse()
                        .map_err(|_| format!("--{key} takes a number, not {value}"))?;
                    values.insert(p.name, v);
                }
                None => options.push((key, value)),
            }
            i += 1;
        }
        Ok((Self(values), options))
    }

    pub fn get(&self, name: &str) -> f64 {
        *self
            .0
            .get(name)
            .unwrap_or_else(|| panic!("no parameter {name}"))
    }

    pub fn to_json(&self) -> Value {
        Value::Object(
            self.0
                .iter()
                .map(|(k, v)| (k.to_string(), Value::from(*v)))
                .collect::<Map<_, _>>(),
        )
    }

    /// Parameters that differ from their defaults, for a run's one-line summary.
    pub fn changed(&self) -> Vec<String> {
        PARAMS
            .iter()
            .filter(|p| self.get(p.name) != p.default)
            .map(|p| format!("{}={}", p.name, self.get(p.name)))
            .collect()
    }
}

pub fn help() -> String {
    let mut out = String::new();
    for p in PARAMS {
        out += &format!(
            "  --{:<22} {:>12}  {}\n",
            p.name.replace('_', "-"),
            fmt_default(p.default),
            p.help
        );
    }
    out
}

fn fmt_default(v: f64) -> String {
    if v.fract() == 0.0 {
        format!("{}", v as i64)
    } else {
        format!("{v}")
    }
}
