//! The capacity model: from a number of drivers and riders to the load on every service they share, at the
//! busiest hour. Each figure follows from the app's own cadences ([crate::params]), so when the code changes how
//! often it reads, announces or sends, the parameter changes and so does the answer.
//!
//! The gossip figures the model gives for one cell are what `klippelgo-bench gossip` puts on a real router, so a
//! run checks them: the run prints the model's figure for each next to what it measured.

use crate::params::Params;
use serde_json::{json, Value};

/// Messages a second one cell carries, by kind, for a given rate of new requests in that cell.
#[derive(Clone, Copy, Default, Debug)]
pub struct CellTraffic {
    pub request: f64,
    pub offer: f64,
    pub close: f64,
    pub route: f64,
    pub position: f64,
}

impl CellTraffic {
    pub fn for_rate(p: &Params, rides_per_s: f64) -> Self {
        let announce = p.get("announce_s");
        let announced_after_lock = (p.get("request_ttl_s") - p.get("ask_s")).max(0.0);
        Self {
            request: rides_per_s * p.get("ask_s") / announce,
            offer: rides_per_s
                * p.get("offers_per_request")
                * (p.get("ask_s") - p.get("quote_delay_s")).max(0.0)
                / announce,
            // A rider who locked keeps announcing the close, and the route sealed to the driver, until the
            // request expires.
            close: rides_per_s * announced_after_lock / announce,
            route: rides_per_s * announced_after_lock / announce,
            position: rides_per_s
                * (p.get("pickup_s") / p.get("position_pickup_s")
                    + p.get("ride_s") / p.get("position_ride_s")),
        }
    }

    pub fn messages(&self) -> f64 {
        self.request + self.offer + self.close + self.route + self.position
    }

    pub fn bytes(&self, p: &Params) -> f64 {
        self.request * p.get("size_request")
            + self.offer * p.get("size_offer")
            + self.close * p.get("size_close")
            + self.route * p.get("size_route")
            + self.position * p.get("size_position")
    }

    pub fn to_json(self) -> Value {
        json!({
            "request": self.request, "offer": self.offer, "close": self.close,
            "route": self.route, "position": self.position, "total": self.messages(),
        })
    }
}

/// The numbers everything else follows from.
pub struct Demand {
    pub rides_per_s: f64,
    pub drivers_online: f64,
    /// Seconds a rider's app is on screen for one ride: asking, waiting, riding.
    pub rider_screen_s: f64,
    /// Seconds a rider's phone listens in the pickup's cell for one ride.
    pub rider_listen_s: f64,
    pub driver_busy_s: f64,
    /// Rides a second starting in the busiest cell, and its drivers.
    pub hot_rides_per_s: f64,
    pub hot_drivers: f64,
    pub avg_rides_per_s: f64,
    pub avg_drivers: f64,
}

impl Demand {
    pub fn new(p: &Params) -> Self {
        let rides_per_s =
            p.get("riders") * p.get("rides_per_rider_day") * p.get("peak_hour_share") / 3600.0;
        let drivers_online = p.get("drivers") * p.get("drivers_online");
        let cells = p.get("cells");
        let rider_screen_s =
            p.get("ask_s") + p.get("accept_s") + p.get("pickup_s") + p.get("ride_s");
        Self {
            rides_per_s,
            drivers_online,
            rider_screen_s,
            rider_listen_s: rider_screen_s.max(p.get("request_ttl_s")),
            driver_busy_s: p.get("accept_s") + p.get("pickup_s") + p.get("ride_s"),
            hot_rides_per_s: rides_per_s / cells * p.get("hotspot"),
            hot_drivers: drivers_online / cells * p.get("hotspot"),
            avg_rides_per_s: rides_per_s / cells,
            avg_drivers: drivers_online / cells,
        }
    }
}

/// One line of the verdict: a shared resource, how much of it the scenario needs, and how much there is.
pub struct Row {
    pub area: &'static str,
    pub what: String,
    pub need: f64,
    pub have: f64,
    pub unit: &'static str,
    /// The documented limit or lever this row is about.
    pub lever: &'static str,
    /// Whether more of the same machine meets the need: then the row says how many, not that it fails.
    pub scales_out: bool,
}

impl Row {
    pub fn load(&self) -> f64 {
        if self.have > 0.0 {
            self.need / self.have
        } else {
            f64::INFINITY
        }
    }

    pub fn verdict(&self) -> String {
        match self.load() {
            l if l <= 0.5 => "holds".into(),
            l if l <= 1.0 => "tight".into(),
            l if self.scales_out => format!("needs {:.0}", l.ceil()),
            _ => "FAILS".into(),
        }
    }
}

/// What the gossip load test measured, used to turn router work into routers needed.
pub struct Measured {
    pub router_cpu_s_per_million: f64,
    pub request_seen: f64,
    pub offer_seen: f64,
    pub position_delivered: f64,
    pub source: String,
}

impl Measured {
    pub fn from_run(run: &Value, source: String) -> Option<Self> {
        let r = &run["results"];
        Some(Self {
            router_cpu_s_per_million: r["router"]["cpu_s_per_million_deliveries"].as_f64()?,
            request_seen: r["drivers"]["requests_seen"].as_f64()?,
            offer_seen: r["riders"]["offers_seen"].as_f64()?,
            position_delivered: r["riders"]["positions_delivered"].as_f64()?,
            source,
        })
    }
}

pub struct Report {
    pub rows: Vec<Row>,
    pub facts: Vec<(String, String)>,
    pub json: Value,
}

pub fn evaluate(p: &Params, measured: Option<&Measured>) -> Report {
    let d = Demand::new(p);
    let mut rows = Vec::new();
    let mut facts = Vec::new();
    let mb = 1e6;

    facts.push((
        "Rides a second at the peak".into(),
        format!(
            "{:.1} ({:.0} a day)",
            d.rides_per_s,
            p.get("riders") * p.get("rides_per_rider_day")
        ),
    ));
    facts.push((
        "Drivers online at the peak".into(),
        format!("{:.0}", d.drivers_online),
    ));
    let riders_on_screen = d.rides_per_s * d.rider_screen_s;
    facts.push((
        "Riders with the app on screen".into(),
        format!("{riders_on_screen:.0}"),
    ));
    facts.push((
        "Busiest cell".into(),
        format!(
            "{:.0} drivers, a new ride every {:.1} s",
            d.hot_drivers,
            1.0 / d.hot_rides_per_s
        ),
    ));

    // ------------------------------------------------------------- drivers
    rows.push(Row {
        area: "people",
        what: "Drivers busy with a ride, of those online".into(),
        need: d.rides_per_s * d.driver_busy_s,
        have: d.drivers_online,
        unit: "drivers",
        scales_out: false,
        lever: "more drivers online; not a system limit",
    });

    // ------------------------------------------------------------- chain
    let gas_per_s = d.rides_per_s * p.get("gas_per_ride");
    let chain_gas_per_s = p.get("block_gas_limit") / p.get("block_time_s");
    rows.push(Row {
        area: "chain",
        what: "Block space: gas a second".into(),
        need: gas_per_s,
        have: chain_gas_per_s * p.get("chain_share_ok"),
        unit: "gas/s",
        scales_out: false,
        lever: "contracts scalability: deploy per region or on a rollup; fewer operations per ride",
    });
    let brl_per_ride = p.get("gas_per_ride") * p.get("gas_price_gwei") * 1e-9 * p.get("coin_brl");
    facts.push((
        "Share of the whole chain's block space".into(),
        format!("{:.0}%", 100.0 * gas_per_s / chain_gas_per_s),
    ));
    facts.push((
        "Gas a ride costs at the assumed price".into(),
        format!("R$ {brl_per_ride:.3}"),
    ));

    // ------------------------------------------------------------- bundler
    let ops_per_s = d.rides_per_s * p.get("ops_per_ride");
    rows.push(Row {
        area: "bundler",
        what: "Operations a second, against one bundler".into(),
        need: ops_per_s,
        have: p.get("bundler_ops_s"),
        unit: "ops/s",
        scales_out: true,
        lever: "several bundlers, several executor keys each",
    });

    // ------------------------------------------------------------- RPC
    let phones_on_screen = d.drivers_online + riders_on_screen;
    let polls_per_s = 1.0 / p.get("poll_s");
    // A poll finds a new block when blocks come at least as often as polls.
    let new_block_polls = polls_per_s.min(1.0 / p.get("block_time_s"));
    let per_phone_rps = polls_per_s
        + new_block_polls * p.get("logs_per_poll")
        + p.get("full_read_calls") / p.get("full_read_s");
    let hot_cells_traffic = CellTraffic::for_rate(p, d.hot_rides_per_s);
    let avg_traffic = CellTraffic::for_rate(p, d.avg_rides_per_s);
    // Drivers read the chain for each new rider whose request they hear in their nine cells.
    let driver_lookup_rps = 9.0 * d.avg_rides_per_s * p.get("lookups_per_request");
    let rpc_rps = phones_on_screen * per_phone_rps + d.drivers_online * driver_lookup_rps;
    rows.push(Row {
        area: "rpc",
        what: "Requests a second, against one RPC node".into(),
        need: rpc_rps,
        have: p.get("rpc_node_rps"),
        unit: "req/s",
        scales_out: true,
        lever: "apps scalability S10 and 'Reading the chain': an event stream or indexer instead of polling",
    });
    // Each poll reads every escrow event of the new blocks and the 16 before them.
    let blocks_per_poll =
        p.get("recheck_blocks") + (p.get("poll_s") / p.get("block_time_s")).max(1.0);
    let events_per_block = d.rides_per_s * p.get("events_per_ride") * p.get("block_time_s");
    let log_bytes_per_phone_s =
        new_block_polls * blocks_per_poll * events_per_block * p.get("log_bytes");
    rows.push(Row {
        area: "rpc",
        what: "Bytes sent by RPC nodes, against one node".into(),
        need: phones_on_screen * log_bytes_per_phone_s,
        have: p.get("rpc_node_mbps") * 1e6 / 8.0,
        unit: "B/s",
        scales_out: true,
        lever:
            "filter escrow logs by rider and driver topics; stop re-reading 16 blocks every poll",
    });
    facts.push((
        "Escrow events in each poll of each phone".into(),
        format!(
            "{:.0} ({:.0} blocks of {:.0})",
            blocks_per_poll * events_per_block,
            blocks_per_poll,
            events_per_block
        ),
    ));
    facts.push((
        "Mobile data a phone uses on chain polling".into(),
        format!("{:.0} MB an hour", log_bytes_per_phone_s * 3600.0 / mb),
    ));

    // ------------------------------------------------------------- gossip, one phone
    let driver_msgs = 9.0 * hot_cells_traffic.messages();
    let driver_bytes = 9.0 * hot_cells_traffic.bytes(p);
    rows.push(Row {
        area: "phone",
        what: "Gossip a driver's phone receives in the busiest cell, per drain, against its inbox"
            .into(),
        need: driver_msgs * p.get("drain_s"),
        have: p.get("inbox"),
        unit: "msgs",
        scales_out: false,
        lever: "apps S6, S8: positions and routes over a direct channel; back off announcements",
    });
    // A phone keeps every request it heard until the request expires, closed or not.
    rows.push(Row {
        area: "phone",
        what: "Requests a driver's phone holds in the busiest cell, against its cap".into(),
        need: 9.0 * d.hot_rides_per_s * p.get("request_ttl_s"),
        have: p.get("max_requests"),
        unit: "requests",
        scales_out: false,
        lever: "apps S5: forget closed requests; rank instead of cap",
    });
    rows.push(Row {
        area: "phone",
        what: "Mobile data a driver's phone uses in the busiest cell, gossip and chain".into(),
        need: (driver_bytes + log_bytes_per_phone_s) * 3600.0 / mb,
        have: p.get("phone_mb_hour_ok"),
        unit: "MB/h",
        scales_out: false,
        lever: "apps S6, S8 and 'Reading the chain'",
    });
    facts.push((
        "Gossip a driver's phone receives in the busiest cell".into(),
        format!(
            "{driver_msgs:.0} msgs/s, {:.0} MB an hour",
            driver_bytes * 3600.0 / mb
        ),
    ));
    facts.push((
        "Gossip a driver's phone receives in an average cell".into(),
        format!(
            "{:.0} msgs/s, {:.0} MB an hour",
            9.0 * avg_traffic.messages(),
            9.0 * avg_traffic.bytes(p) * 3600.0 / mb
        ),
    ));

    // ------------------------------------------------------------- gossip, routers
    let avg_subscribers = 9.0 * d.avg_drivers + d.avg_rides_per_s * d.rider_listen_s;
    let hot_subscribers = 9.0 * d.hot_drivers + d.hot_rides_per_s * d.rider_listen_s;
    let deliveries = p.get("cells") * avg_traffic.messages() * avg_subscribers;
    let delivered_bytes = p.get("cells") * avg_traffic.bytes(p) * avg_subscribers;
    rows.push(Row {
        area: "router",
        what: "Phones subscribed to the busiest cell, against GossipSub's mesh".into(),
        need: hot_subscribers,
        have: p.get("mesh_n_high"),
        unit: "phones",
        scales_out: false,
        lever: "core scalability: routers per region; phones dialling each other (relays, hole punching)",
    });
    rows.push(Row {
        area: "router",
        what: "Bytes sent by routers, against one 1 Gbit/s router".into(),
        need: delivered_bytes,
        have: 1e9 / 8.0,
        unit: "B/s",
        scales_out: true,
        lever: "core scalability: fewer re-announcements, direct channel, routers per region",
    });
    let routers_cpu = measured.map(|m| deliveries * m.router_cpu_s_per_million / 1e6);
    if let Some(cores) = routers_cpu {
        rows.push(Row {
            area: "router",
            what: "Router CPU (measured cost per delivery), against one router's core".into(),
            need: cores,
            have: 1.0,
            unit: "cores",
            scales_out: true,
            lever: "the router runs on one thread today; routers per region",
        });
    }
    facts.push((
        "Gossip deliveries a second, all routers".into(),
        format!("{:.2e}", deliveries),
    ));

    // ------------------------------------------------------------- mirrors
    let release_bytes = (p.get("drivers") + p.get("riders")) * p.get("pack_mb") * mb;
    rows.push(Row {
        area: "mirror",
        what: "A pack release, against one mirror".into(),
        need: release_bytes / (p.get("release_days") * 86_400.0),
        have: p.get("mirror_mbps") * 1e6 / 8.0,
        unit: "B/s",
        scales_out: true,
        lever: "any CDN or IPFS: mirrors are untrusted static files",
    });

    if let Some(m) = measured {
        facts.push(("Measured in".into(), m.source.clone()));
        facts.push((
            "Measured: drivers who saw a request, riders who got an offer, positions delivered"
                .into(),
            format!(
                "{:.1}%, {:.1}%, {:.1}%",
                100.0 * m.request_seen,
                100.0 * m.offer_seen,
                100.0 * m.position_delivered
            ),
        ));
    }

    let json = json!({
        "facts": facts.iter().map(|(k, v)| json!({"what": k, "value": v})).collect::<Vec<_>>(),
        "rows": rows.iter().map(|r| json!({
            "area": r.area, "what": r.what, "need": r.need, "have": r.have, "unit": r.unit,
            "load": r.load(), "verdict": r.verdict(), "scales_out": r.scales_out, "lever": r.lever,
        })).collect::<Vec<_>>(),
        "busiest_cell": hot_cells_traffic.to_json(),
        "average_cell": avg_traffic.to_json(),
    });
    Report { rows, facts, json }
}

pub fn print(p: &Params, report: &Report) {
    println!(
        "KlippelGo at {} drivers and {} riders, busiest hour{}",
        p.get("drivers"),
        p.get("riders"),
        match p
            .changed()
            .iter()
            .filter(|c| !c.starts_with("drivers=") && !c.starts_with("riders="))
            .count()
        {
            0 => String::new(),
            _ => format!(" ({})", p.changed().join(", ")),
        }
    );
    println!();
    for (what, value) in &report.facts {
        println!("  {what:<66} {value}");
    }
    println!();
    println!(
        "  {:<8} {:<80} {:>10} {:>10} {:>8}  verdict",
        "area", "what", "need", "have", "load"
    );
    for r in &report.rows {
        println!(
            "  {:<8} {:<80} {:>10} {:>10} {:>7.0}%  {}",
            r.area,
            r.what,
            si(r.need),
            si(r.have),
            100.0 * r.load(),
            r.verdict()
        );
    }
    let failing: Vec<_> = report.rows.iter().filter(|r| r.load() > 0.5).collect();
    if !failing.is_empty() {
        println!();
        println!("  Levers for what does not hold:");
        for r in failing {
            println!("    {:<8} {}", r.area, r.lever);
        }
    }
}

/// A number with an SI suffix, for the table.
pub fn si(v: f64) -> String {
    let (div, suffix) = match v.abs() {
        a if a >= 1e9 => (1e9, "G"),
        a if a >= 1e6 => (1e6, "M"),
        a if a >= 1e3 => (1e3, "k"),
        _ => (1.0, ""),
    };
    let x = v / div;
    if x.abs() >= 100.0 || suffix.is_empty() && x.fract() == 0.0 {
        format!("{x:.0}{suffix}")
    } else {
        format!("{x:.1}{suffix}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_cells_traffic_follows_the_apps_cadences() {
        let (p, _) = Params::from_args(&[]).unwrap();
        let t = CellTraffic::for_rate(&p, 0.1);
        // 20 s asking, announced every 2.5 s; five offers from 3 s on; close and route for the other 580 s.
        assert!((t.request - 0.8).abs() < 1e-9);
        assert!((t.offer - 0.1 * 5.0 * 17.0 / 2.5).abs() < 1e-9);
        assert!((t.close - 0.1 * 580.0 / 2.5).abs() < 1e-9 && t.route == t.close);
        assert!((t.position - 0.1 * (300.0 / 4.0 + 900.0 / 0.9)).abs() < 1e-9);
    }

    #[test]
    fn the_target_scale_needs_more_than_one_of_each_service() {
        let (p, _) = Params::from_args(&[]).unwrap();
        let d = Demand::new(&p);
        assert!((d.rides_per_s - 1_000_000.0 * 0.5 * 0.1 / 3600.0).abs() < 1e-9);
        let report = evaluate(&p, None);
        let chain = report.rows.iter().find(|r| r.area == "chain").unwrap();
        assert_eq!(chain.verdict(), "FAILS");
        let small = Params::from_args(&[
            "--riders".into(),
            "1000".into(),
            "--drivers".into(),
            "100".into(),
        ])
        .unwrap()
        .0;
        assert!(evaluate(&small, None)
            .rows
            .iter()
            .all(|r| r.area == "router" || r.area == "phone" || r.verdict() == "holds"));
    }
}
