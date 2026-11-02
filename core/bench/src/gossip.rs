//! The gossip load test: a square of cells at the busiest cell's traffic, through the real router.
//!
//! The router is `klippelgo-node`, started as its own process so its CPU and memory can be read. Each phone is a
//! real `GossipNode`, as in the app, dialling only the router. What the phones send follows the app: a rider
//! announces their request while asking, drivers who hear it quote and re-announce, the rider locks one offer and
//! announces the close briefly and the sealed route until the driver's first position shows it arrived, and the
//! chosen driver sends positions every 4 s to the pickup and every 0.9 s on the trip. Announcements back off as
//! `Cadence` does in the app. Sizes are those of the real messages.
//! The run starts with the rides of the last twenty minutes already under way, so it measures a steady state.
//!
//! A cell has hundreds of drivers at the target scale, more than one computer runs phones for. So each phone
//! here carries several people: the cell's drivers are shared among `driver_nodes` phones and its riders among
//! `rider_nodes`. A phone still hears exactly what one real phone in that cell hears, so what one phone
//! receives is measured at full traffic; the router forwards to fewer phones than it would, and its cost per
//! message delivered is what the model scales up.
//!
//! Each phone drains its inbox at the app's cadence and keeps at most `max_requests` requests while they are
//! open, as `Matchmaker` does, so a request lost to a full inbox or a full list counts as not seen.

use crate::model::{gap, CellTraffic, Demand};
use crate::params::Params;
use klippelgo_core::gossip::{GossipConfig, GossipNode};
use serde_json::{json, Value};
use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap, HashSet};
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};

const MAGIC: &[u8; 4] = b"KGB1";
const HEADER: usize = 41;
/// Target of a message every driver who hears the cell is meant to receive.
const CELL: u32 = u32::MAX;

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
enum Kind {
    Request = 0,
    Offer = 1,
    Close = 2,
    Route = 3,
    Position = 4,
}

const KINDS: [Kind; 5] = [
    Kind::Request,
    Kind::Offer,
    Kind::Close,
    Kind::Route,
    Kind::Position,
];

impl Kind {
    fn name(self) -> &'static str {
        ["request", "offer", "close", "route", "position"][self as usize]
    }
    fn size(self, p: &Params) -> usize {
        p.get(
            [
                "size_request",
                "size_offer",
                "size_close",
                "size_route",
                "size_position",
            ][self as usize],
        ) as usize
    }
}

/// What a message says about itself, at its start; the rest is padding to the real message's size.
struct Header {
    kind: Kind,
    target: u32,
    /// The trip or offer it is about.
    about: u64,
    /// When the first message about it was sent, for the time until a phone first had it.
    origin_us: i64,
    sent_us: i64,
    cell: u32,
}

impl Header {
    fn encode(&self, size: usize) -> Vec<u8> {
        let mut out = Vec::with_capacity(size.max(HEADER));
        out.extend_from_slice(MAGIC);
        out.push(self.kind as u8);
        out.extend_from_slice(&self.target.to_le_bytes());
        out.extend_from_slice(&self.about.to_le_bytes());
        out.extend_from_slice(&self.origin_us.to_le_bytes());
        out.extend_from_slice(&self.sent_us.to_le_bytes());
        out.extend_from_slice(&self.cell.to_le_bytes());
        out.resize(size.max(HEADER), 0);
        out
    }

    fn decode(data: &[u8]) -> Option<Self> {
        if data.len() < HEADER || &data[..4] != MAGIC {
            return None;
        }
        let u32_at = |i: usize| u32::from_le_bytes(data[i..i + 4].try_into().unwrap());
        let i64_at = |i: usize| i64::from_le_bytes(data[i..i + 8].try_into().unwrap());
        Some(Self {
            kind: *KINDS.get(data[4] as usize)?,
            target: u32_at(5),
            about: i64_at(9) as u64,
            origin_us: i64_at(17),
            sent_us: i64_at(25),
            cell: u32_at(33),
        })
    }
}

/// A request on a phone's list.
struct Held {
    cell: usize,
    first_us: i64,
    last_us: i64,
    expires_us: i64,
}

struct Phone {
    node: Arc<GossipNode>,
    driver: bool,
    cell: usize,
    /// Requests this phone's matchmaker holds: open ones, and those it quoted.
    held: HashMap<u64, Held>,
    longest_list: usize,
    /// Requests and routes this phone has had, so each counts once.
    seen: HashSet<(Kind, u64)>,
    received: u64,
    received_bytes: u64,
    largest_drain: usize,
    full_drains: u64,
}

struct LogicalDriver {
    phone: usize,
    busy: bool,
}

struct Offerer {
    driver: usize,
    offer: u64,
    started_us: Option<i64>,
    sends: u32,
}

struct Trip {
    cell: usize,
    rider: usize,
    t0_us: i64,
    offerers: Vec<Offerer>,
    /// Offers the rider's phone has, in the order they arrived.
    offers_seen: Vec<u64>,
    chosen: Option<usize>,
    positions_from_us: i64,
    locked_us: i64,
    asking: bool,
    closing: bool,
    revealing: bool,
    driving: bool,
    request_sends: u32,
    close_sends: u32,
    route_sends: u32,
    /// The rider's phone had a position from the driver: the route arrived.
    acknowledged: bool,
    /// Asked while measuring: counts towards the figures about whole requests.
    measured: bool,
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
enum Event {
    Arrival(usize),
    Announce(u64),
    Close(u64),
    Route(u64),
    Offer(u64, usize),
    Choose(u64),
    Position(u64),
    Drain(usize),
}

/// A small deterministic random source, so a seed gives the same rides.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
    fn unit(&mut self) -> f64 {
        (self.next() >> 11) as f64 / (1u64 << 53) as f64
    }
    fn exp(&mut self, rate: f64) -> f64 {
        -(1.0 - self.unit()).ln() / rate
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

#[derive(Default)]
struct Tally {
    sent: [u64; 5],
    sent_bytes: [u64; 5],
    expected: [u64; 5],
    delivered: [u64; 5],
    latency_ms: [Vec<f64>; 5],
    trips: u64,
    unmatched: u64,
    requests_expected: u64,
    requests_seen: u64,
    requests_near_expected: u64,
    requests_near_seen: u64,
    requests_full: u64,
    request_first_ms: Vec<f64>,
    offers_expected: u64,
    offers_seen: u64,
    offer_first_ms: Vec<f64>,
    routes_expected: u64,
    routes_seen: u64,
    route_first_ms: Vec<f64>,
    deliveries: u64,
}

struct World<'a> {
    p: &'a Params,
    grid: usize,
    phones: Vec<Phone>,
    drivers: Vec<LogicalDriver>,
    drivers_of_cell: Vec<Vec<usize>>,
    riders_of_cell: Vec<Vec<usize>>,
    /// Driver phones that hear each cell.
    listeners: Vec<usize>,
    trips: HashMap<u64, Trip>,
    offer_trips: HashMap<u64, u64>,
    next_trip: u64,
    next_offer: u64,
    events: BinaryHeap<Reverse<(i64, u64, Event)>>,
    seq: u64,
    rng: Rng,
    /// New rides a second in each cell.
    rate: f64,
    now: i64,
    measure_from: i64,
    measure_to: i64,
    tally: Tally,
}

fn us(seconds: f64) -> i64 {
    (seconds * 1e6) as i64
}

fn topic(cell: usize) -> String {
    format!("/klippelgo/match/1/bc{cell:03}")
}

fn ride_topic(trip: u64) -> String {
    format!("/klippelgo/ride/1/0x{trip:064x}")
}

/// The cells within one step of `cell` on a `grid` by `grid` square, itself included: what a driver there hears.
fn around(grid: usize, cell: usize) -> Vec<usize> {
    let (x, y) = (cell % grid, cell / grid);
    let mut out = Vec::new();
    for ny in y.saturating_sub(1)..=(y + 1).min(grid - 1) {
        for nx in x.saturating_sub(1)..=(x + 1).min(grid - 1) {
            out.push(ny * grid + nx);
        }
    }
    out
}

impl World<'_> {
    fn hears(&self, phone: usize, cell: usize) -> bool {
        let p = &self.phones[phone];
        if p.driver {
            let g = self.grid;
            (p.cell % g).abs_diff(cell % g) <= 1 && (p.cell / g).abs_diff(cell / g) <= 1
        } else {
            p.cell == cell
        }
    }

    fn at(&mut self, time_us: i64, event: Event) {
        self.seq += 1;
        self.events.push(Reverse((time_us, self.seq, event)));
    }

    fn measuring(&self, t: i64) -> bool {
        t >= self.measure_from && t < self.measure_to
    }

    /// The rider's phone listens on the ride's topic from the lock, as `Matchmaker.revealRoute` does.
    fn join_ride(&self, id: u64) {
        if self.p.get("ride_topics") > 0.0 {
            let _ = self.phones[self.trips[&id].rider]
                .node
                .subscribe(ride_topic(id));
        }
    }

    fn leave_ride(&self, id: u64) {
        if self.p.get("ride_topics") > 0.0 {
            let _ = self.phones[self.trips[&id].rider]
                .node
                .unsubscribe(ride_topic(id));
        }
    }

    fn publish(
        &mut self,
        from: usize,
        kind: Kind,
        cell: usize,
        target: u32,
        about: u64,
        origin_us: i64,
    ) {
        let now = self.now;
        let data = Header {
            kind,
            target,
            about,
            origin_us,
            sent_us: now,
            cell: cell as u32,
        }
        .encode(kind.size(self.p));
        if self.measuring(now) {
            let k = kind as usize;
            self.tally.sent[k] += 1;
            self.tally.sent_bytes[k] += data.len() as u64;
            self.tally.expected[k] += if target == CELL {
                self.listeners[cell] as u64
            } else {
                1
            };
        }
        let to = if kind == Kind::Position && self.p.get("ride_topics") > 0.0 {
            ride_topic(about)
        } else {
            topic(cell)
        };
        let _ = self.phones[from].node.publish(to, data);
    }

    fn idle_drivers_around(&self, cell: usize) -> Vec<usize> {
        around(self.grid, cell)
            .iter()
            .flat_map(|c| self.drivers_of_cell[*c].iter().copied())
            .filter(|d| !self.drivers[*d].busy)
            .collect()
    }

    /// A ride asked for at `t0_us`, which may be before the run began: it starts in the phase it would be in.
    fn new_trip(&mut self, cell: usize, t0_us: i64) {
        let now = self.now;
        let ask = us(self.p.get("ask_s"));
        let id = self.next_trip;
        self.next_trip += 1;
        let riders = &self.riders_of_cell[cell];
        let rider = riders[self.rng.below(riders.len())];
        let measured = self.measuring(t0_us) && t0_us + ask <= self.measure_to;
        self.trips.insert(
            id,
            Trip {
                cell,
                rider,
                t0_us,
                offerers: vec![],
                offers_seen: vec![],
                chosen: None,
                positions_from_us: 0,
                locked_us: 0,
                asking: true,
                closing: false,
                revealing: false,
                driving: false,
                request_sends: 0,
                close_sends: 0,
                route_sends: 0,
                acknowledged: false,
                measured,
            },
        );
        if now - t0_us < ask {
            // Still asking: some idle drivers who hear the cell will quote once they see the request.
            let mut idle = self.idle_drivers_around(cell);
            let mut offerers = Vec::new();
            while offerers.len() < self.p.get("offers_per_request") as usize && !idle.is_empty() {
                let driver = idle.swap_remove(self.rng.below(idle.len()));
                self.next_offer += 1;
                self.offer_trips.insert(self.next_offer, id);
                offerers.push(Offerer {
                    driver,
                    offer: self.next_offer,
                    started_us: None,
                    sends: 0,
                });
            }
            self.trips.get_mut(&id).unwrap().offerers = offerers;
            if measured {
                self.tally.trips += 1;
                self.tally.requests_expected += self.listeners[cell] as u64;
                self.tally.requests_near_expected += self.p.get("driver_nodes") as u64;
            }
            self.at(now, Event::Announce(id));
            self.at(t0_us + ask, Event::Choose(id));
        } else {
            // Locked before the run began: a driver has it, and what the rider still announces depends on how
            // long ago. A route already acknowledged reached the driver's phone.
            let idle = self.idle_drivers_around(cell);
            if idle.is_empty() {
                self.trips.remove(&id);
                return;
            }
            let driver = idle[self.rng.below(idle.len())];
            let locked = t0_us + ask;
            self.start_ride(id, driver, locked);
            let closing = now - locked < us(self.p.get("live_for_s"));
            let revealing = now - locked < us(self.p.get("accept_s") + self.p.get("drain_s"));
            let phone = self.drivers[driver].phone;
            let trip = self.trips.get_mut(&id).unwrap();
            trip.asking = false;
            trip.closing = closing;
            trip.revealing = revealing;
            if !revealing {
                self.phones[phone].seen.insert((Kind::Route, id));
            }
            let phase = us(self.rng.unit() * self.p.get("announce_s"));
            if closing {
                self.at(now + phase, Event::Close(id));
            }
            if revealing {
                self.at(now + phase, Event::Route(id));
            }
            self.maybe_forget(id);
        }
    }

    fn start_ride(&mut self, id: u64, driver: usize, locked_us: i64) {
        let from = locked_us + us(self.p.get("accept_s"));
        let end = from + us(self.p.get("pickup_s") + self.p.get("ride_s"));
        let phase = us(self.rng.unit() * self.p.get("position_ride_s"));
        let trip = self.trips.get_mut(&id).unwrap();
        trip.chosen = Some(driver);
        trip.locked_us = locked_us;
        trip.positions_from_us = from;
        if end > self.now {
            trip.driving = true;
            self.drivers[driver].busy = true;
            self.join_ride(id);
            let first = from.max(self.now + phase);
            self.at(first, Event::Position(id));
        }
    }

    fn handle(&mut self, event: Event) {
        let now = self.now;
        match event {
            Event::Arrival(cell) => {
                if now < self.measure_to {
                    self.new_trip(cell, now);
                    let next = now + us(self.rng.exp(self.rate));
                    self.at(next, Event::Arrival(cell));
                }
            }
            Event::Announce(id) => {
                let Some(trip) = self.trips.get(&id) else {
                    return;
                };
                let (cell, rider, t0, sends) =
                    (trip.cell, trip.rider, trip.t0_us, trip.request_sends);
                if now - t0 >= us(self.p.get("ask_s")) {
                    return;
                }
                self.publish(rider, Kind::Request, cell, CELL, id, t0);
                self.trips.get_mut(&id).unwrap().request_sends += 1;
                let wait = gap(self.p, sends, self.p.get("open_gap_most_s"));
                self.at(now + us(wait), Event::Announce(id));
            }
            Event::Close(id) => {
                let Some(trip) = self.trips.get(&id) else {
                    return;
                };
                let (cell, rider, locked, sends) =
                    (trip.cell, trip.rider, trip.locked_us, trip.close_sends);
                if now - locked >= us(self.p.get("live_for_s")) {
                    self.trips.get_mut(&id).unwrap().closing = false;
                    self.maybe_forget(id);
                    return;
                }
                self.publish(rider, Kind::Close, cell, CELL, id, locked);
                self.trips.get_mut(&id).unwrap().close_sends += 1;
                let wait = gap(self.p, sends, self.p.get("close_gap_most_s"));
                self.at(now + us(wait), Event::Close(id));
            }
            Event::Route(id) => {
                let Some(trip) = self.trips.get(&id) else {
                    return;
                };
                let (cell, rider, t0, locked, sends) = (
                    trip.cell,
                    trip.rider,
                    trip.t0_us,
                    trip.locked_us,
                    trip.route_sends,
                );
                if trip.acknowledged || now - t0 >= us(self.p.get("request_ttl_s")) {
                    self.trips.get_mut(&id).unwrap().revealing = false;
                    self.maybe_forget(id);
                    return;
                }
                let phone = self.drivers[trip.chosen.unwrap()].phone as u32;
                self.publish(rider, Kind::Route, cell, phone, id, locked);
                self.trips.get_mut(&id).unwrap().route_sends += 1;
                let wait = gap(self.p, sends, self.p.get("route_gap_most_s"));
                self.at(now + us(wait), Event::Route(id));
            }
            Event::Offer(id, k) => {
                let Some(trip) = self.trips.get(&id) else {
                    return;
                };
                if now >= trip.t0_us + us(self.p.get("ask_s")) || now >= self.measure_to {
                    return;
                }
                let o = &trip.offerers[k];
                let (cell, rider, offer, started, sends) = (
                    trip.cell,
                    trip.rider as u32,
                    o.offer,
                    o.started_us.unwrap(),
                    o.sends,
                );
                let phone = self.drivers[o.driver].phone;
                self.publish(phone, Kind::Offer, cell, rider, offer, started);
                self.trips.get_mut(&id).unwrap().offerers[k].sends += 1;
                let wait = gap(self.p, sends, self.p.get("open_gap_most_s"));
                self.at(now + us(wait), Event::Offer(id, k));
            }
            Event::Choose(id) => {
                let Some(trip) = self.trips.get(&id) else {
                    return;
                };
                let measured = trip.measured;
                let pick = trip
                    .offers_seen
                    .iter()
                    .filter_map(|offer| trip.offerers.iter().find(|o| o.offer == *offer))
                    .map(|o| o.driver)
                    .find(|d| !self.drivers[*d].busy);
                for o in &trip.offerers {
                    self.offer_trips.remove(&o.offer);
                }
                match pick {
                    Some(driver) => {
                        if measured {
                            self.tally.routes_expected += 1;
                        }
                        self.start_ride(id, driver, now);
                        let trip = self.trips.get_mut(&id).unwrap();
                        trip.asking = false;
                        trip.closing = true;
                        trip.revealing = true;
                        self.at(now, Event::Close(id));
                        self.at(now, Event::Route(id));
                    }
                    None => {
                        if measured {
                            self.tally.unmatched += 1;
                        }
                        self.trips.get_mut(&id).unwrap().asking = false;
                        self.maybe_forget(id);
                    }
                }
            }
            Event::Position(id) => {
                let Some(trip) = self.trips.get(&id) else {
                    return;
                };
                let (cell, rider, from, driver) = (
                    trip.cell,
                    trip.rider as u32,
                    trip.positions_from_us,
                    trip.chosen.unwrap(),
                );
                let elapsed = now - from;
                let (pickup, ride) = (us(self.p.get("pickup_s")), us(self.p.get("ride_s")));
                if elapsed >= pickup + ride || now >= self.measure_to {
                    self.drivers[driver].busy = false;
                    self.leave_ride(id);
                    self.trips.get_mut(&id).unwrap().driving = false;
                    self.maybe_forget(id);
                    return;
                }
                let phone = self.drivers[driver].phone;
                // Positions are sealed to a key that comes with the route: without it the driver has none to send.
                if self.phones[phone].seen.contains(&(Kind::Route, id)) {
                    self.publish(phone, Kind::Position, cell, rider, id, now);
                }
                let every = if elapsed < pickup {
                    self.p.get("position_pickup_s")
                } else {
                    self.p.get("position_ride_s")
                };
                self.at(now + us(every), Event::Position(id));
            }
            Event::Drain(phone) => {
                self.drain(phone);
                self.at(now + us(self.p.get("drain_s")), Event::Drain(phone));
            }
        }
    }

    fn maybe_forget(&mut self, id: u64) {
        if self
            .trips
            .get(&id)
            .is_some_and(|t| !t.asking && !t.closing && !t.revealing && !t.driving)
        {
            self.trips.remove(&id);
        }
    }

    fn drain(&mut self, phone: usize) {
        let now = self.now;
        let messages = self.phones[phone].node.drain();
        let measuring = self.measuring(now);
        if measuring {
            let ph = &mut self.phones[phone];
            ph.largest_drain = ph.largest_drain.max(messages.len());
            if messages.len() >= self.p.get("inbox") as usize {
                ph.full_drains += 1;
            }
        }
        let ask = us(self.p.get("ask_s"));
        // As Matchmaker.tick: a request not heard for a while is gone. The app keeps those its phone quoted, a
        // few; here a phone quotes for all its people, so keeping them would fill the list as no real phone's is.
        let live = us(self.p.get("live_for_s"));
        self.phones[phone]
            .held
            .retain(|_, h| h.expires_us > now && now - h.last_us < live);
        for m in messages {
            if measuring {
                self.tally.deliveries += 1;
                self.phones[phone].received += 1;
                self.phones[phone].received_bytes += m.data.len() as u64;
            }
            let Some(h) = Header::decode(&m.data) else {
                continue;
            };
            let meant = if h.target == CELL {
                self.phones[phone].driver && self.hears(phone, h.cell as usize)
            } else {
                h.target as usize == phone
            };
            if !meant {
                continue;
            }
            if self.measuring(h.sent_us) {
                let k = h.kind as usize;
                self.tally.delivered[k] += 1;
                self.tally.latency_ms[k].push((now - h.sent_us) as f64 / 1000.0);
            }
            match h.kind {
                Kind::Request => self.request_heard(phone, h.about, h.origin_us),
                Kind::Offer => {
                    let Some(trip) = self
                        .offer_trips
                        .get(&h.about)
                        .and_then(|id| self.trips.get_mut(id))
                    else {
                        continue;
                    };
                    if now < trip.t0_us + ask && !trip.offers_seen.contains(&h.about) {
                        trip.offers_seen.push(h.about);
                        if trip.measured {
                            self.tally.offers_seen += 1;
                            self.tally
                                .offer_first_ms
                                .push((now - h.origin_us) as f64 / 1000.0);
                        }
                    }
                }
                Kind::Route => {
                    let measured = self.trips.get(&h.about).is_some_and(|t| t.measured);
                    if self.phones[phone].seen.insert((Kind::Route, h.about)) && measured {
                        self.tally.routes_seen += 1;
                        self.tally
                            .route_first_ms
                            .push((now - h.origin_us) as f64 / 1000.0);
                    }
                }
                Kind::Close => {
                    self.phones[phone].held.remove(&h.about);
                }
                Kind::Position => {
                    if let Some(trip) = self.trips.get_mut(&h.about) {
                        trip.acknowledged = true;
                    }
                }
            }
        }
        let ph = &mut self.phones[phone];
        if measuring {
            ph.longest_list = ph.longest_list.max(ph.held.len());
        }
    }

    fn request_heard(&mut self, phone: usize, id: u64, origin_us: i64) {
        let now = self.now;
        let ttl = us(self.p.get("request_ttl_s"));
        let cap = self.p.get("max_requests") as usize;
        let rank = self.p.get("rank_requests") > 0.0;
        let Some((measured, cell)) = self.trips.get(&id).map(|t| (t.measured, t.cell)) else {
            return;
        };
        let ph = &mut self.phones[phone];
        if let Some(h) = ph.held.get_mut(&id) {
            h.last_us = now;
            return;
        }
        // As Matchmaker: a full list gives up its farthest request, the oldest of those. A newcomer no nearer
        // than that one is dropped. In a neighbouring cell counts as far, in the phone's own as near.
        if ph.held.len() >= cap {
            let far = |c: usize| usize::from(c != ph.cell);
            let worst = ph
                .held
                .iter()
                .max_by_key(|(_, h)| (far(h.cell), Reverse(h.first_us)))
                .map(|(k, h)| (*k, far(h.cell)));
            match worst {
                Some((out, band)) if rank && far(cell) <= band => {
                    ph.held.remove(&out);
                }
                _ => {
                    // Counted once per phone, under a key of its own: it may still get in once room frees.
                    if measured && ph.seen.insert((Kind::Close, id)) {
                        self.tally.requests_full += 1;
                    }
                    return;
                }
            }
        }
        ph.held.insert(
            id,
            Held {
                cell,
                first_us: now,
                last_us: now,
                expires_us: origin_us + ttl,
            },
        );
        let near = ph.cell == cell;
        let trip = self.trips.get_mut(&id).unwrap();
        if now >= trip.t0_us + us(self.p.get("ask_s")) {
            return;
        }
        if trip.measured && ph.seen.insert((Kind::Request, id)) {
            self.tally.requests_seen += 1;
            self.tally.requests_near_seen += u64::from(near);
            self.tally
                .request_first_ms
                .push((now - origin_us) as f64 / 1000.0);
        }
        // The drivers on this phone who will quote it do so a few seconds after it reached their screen.
        let at = now + us(self.p.get("quote_delay_s"));
        let measured = trip.measured;
        let mut starts = Vec::new();
        for (k, o) in trip.offerers.iter_mut().enumerate() {
            if o.started_us.is_none()
                && self.drivers[o.driver].phone == phone
                && !self.drivers[o.driver].busy
            {
                o.started_us = Some(at);
                starts.push(k);
            }
        }
        for k in starts {
            if measured {
                self.tally.offers_expected += 1;
            }
            self.at(at, Event::Offer(id, k));
        }
    }
}

// ------------------------------------------------------------------------------------------------ the router

/// `klippelgo-node`, run as its own process.
struct Router {
    child: Child,
    address: String,
}

impl Router {
    fn start(binary: &Path) -> Result<Self, String> {
        let mut child = Command::new(binary)
            .arg("/ip4/127.0.0.1/tcp/0")
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|e| format!("could not start {}: {e}", binary.display()))?;
        let stdout = child.stdout.take().unwrap();
        let mut lines = BufReader::new(stdout).lines();
        let address = loop {
            match lines.next() {
                Some(Ok(line)) => {
                    if let Some(a) = line.strip_prefix("Listening on ") {
                        break a.trim().to_string();
                    }
                }
                _ => {
                    let _ = child.kill();
                    return Err("the router stopped before listening".into());
                }
            }
        };
        // Keep reading what it prints, so it never blocks on a full pipe.
        std::thread::spawn(move || for _ in lines {});
        Ok(Self { child, address })
    }

    fn usage(&self) -> Usage {
        Usage::of(self.child.id())
    }
}

impl Drop for Router {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// CPU time, memory and bytes written of a process, from /proc (Linux). Zero elsewhere.
#[derive(Clone, Copy, Default)]
struct Usage {
    cpu_s: f64,
    rss_mb: f64,
    written: f64,
}

impl Usage {
    fn of(pid: u32) -> Self {
        let read =
            |f: &str| std::fs::read_to_string(format!("/proc/{pid}/{f}")).unwrap_or_default();
        // utime and stime are fields 14 and 15, in clock ticks of 1/100 s; the name before them may hold spaces.
        let stat = read("stat");
        let cpu_s = stat
            .rsplit_once(')')
            .map(|(_, rest)| {
                let f: Vec<f64> = rest
                    .split_whitespace()
                    .filter_map(|x| x.parse().ok())
                    .collect();
                (f.get(11).unwrap_or(&0.0) + f.get(12).unwrap_or(&0.0)) / 100.0
            })
            .unwrap_or(0.0);
        let field = |text: &str, key: &str| {
            text.lines()
                .find_map(|l| l.strip_prefix(key))
                .and_then(|v| v.split_whitespace().next()?.parse::<f64>().ok())
                .unwrap_or(0.0)
        };
        Self {
            cpu_s,
            rss_mb: field(&read("status"), "VmRSS:") / 1024.0,
            written: field(&read("io"), "wchar:"),
        }
    }
}

// ------------------------------------------------------------------------------------------------ the run

fn percentiles(values: &mut [f64]) -> Value {
    if values.is_empty() {
        return Value::Null;
    }
    values.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let at = |q: f64| values[((values.len() - 1) as f64 * q).round() as usize];
    json!({ "p50": at(0.5), "p95": at(0.95), "p99": at(0.99), "max": values[values.len() - 1], "n": values.len() })
}

fn ratio(a: u64, b: u64) -> Value {
    if b == 0 {
        Value::Null
    } else {
        json!(a as f64 / b as f64)
    }
}

pub fn run(p: &Params, node_binary: &Path) -> Result<Value, String> {
    let grid = p.get("grid").max(1.0) as usize;
    let cells = grid * grid;
    let demand = Demand::new(p);
    let rate = demand.hot_rides_per_s * p.get("traffic");
    let people = (demand.hot_drivers * p.get("traffic")).round().max(1.0) as usize;
    let (driver_nodes, rider_nodes) = (
        p.get("driver_nodes").max(1.0) as usize,
        p.get("rider_nodes").max(1.0) as usize,
    );
    if rate <= 0.0 {
        return Err("no rides: check --riders, --rides-per-rider-day and --traffic".into());
    }

    eprintln!(
        "gossip: {cells} cells, each {:.2} rides/s and {people} drivers on {driver_nodes} driver phones and {rider_nodes} rider phones",
        rate
    );
    let router = Router::start(node_binary)?;
    eprintln!(
        "gossip: router {} (pid {})",
        router.address,
        router.child.id()
    );

    let mut phones = Vec::new();
    for cell in 0..cells {
        for k in 0..driver_nodes + rider_nodes {
            let node = GossipNode::start(GossipConfig {
                listen: None,
                bootstrap: vec![router.address.clone()],
                router: false,
            })
            .map_err(|e| format!("phone: {e}"))?;
            phones.push(Phone {
                node,
                driver: k < driver_nodes,
                cell,
                held: HashMap::new(),
                longest_list: 0,
                seen: HashSet::new(),
                received: 0,
                received_bytes: 0,
                largest_drain: 0,
                full_drains: 0,
            });
        }
    }
    let deadline = Instant::now() + Duration::from_secs(60);
    while phones.iter().any(|ph| ph.node.connected_peers() == 0) {
        if Instant::now() > deadline {
            let missing = phones
                .iter()
                .filter(|ph| ph.node.connected_peers() == 0)
                .count();
            return Err(format!(
                "{missing} phones could not reach the router in a minute"
            ));
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    for ph in &phones {
        let wanted = if ph.driver {
            around(grid, ph.cell)
        } else {
            vec![ph.cell]
        };
        for c in wanted {
            ph.node.subscribe(topic(c)).map_err(|e| e.to_string())?;
        }
    }
    eprintln!("gossip: {} phones connected and subscribed", phones.len());
    std::thread::sleep(Duration::from_secs(3));

    let mut drivers = Vec::new();
    let mut drivers_of_cell = vec![Vec::new(); cells];
    let mut riders_of_cell = vec![Vec::new(); cells];
    for (i, ph) in phones.iter().enumerate() {
        if !ph.driver {
            riders_of_cell[ph.cell].push(i);
        }
    }
    for (cell, of_cell) in drivers_of_cell.iter_mut().enumerate() {
        let phones_here: Vec<usize> = (0..phones.len())
            .filter(|i| phones[*i].driver && phones[*i].cell == cell)
            .collect();
        for k in 0..people {
            of_cell.push(drivers.len());
            drivers.push(LogicalDriver {
                phone: phones_here[k % phones_here.len()],
                busy: false,
            });
        }
    }
    let listeners = (0..cells)
        .map(|c| around(grid, c).len() * driver_nodes)
        .collect();
    let (warmup, duration, grace) = (
        us(p.get("warmup_s")),
        us(p.get("duration_s")),
        us(p.get("grace_s")),
    );
    let mut world = World {
        p,
        grid,
        phones,
        drivers,
        drivers_of_cell,
        riders_of_cell,
        listeners,
        trips: HashMap::new(),
        offer_trips: HashMap::new(),
        next_trip: 1,
        next_offer: 0,
        events: BinaryHeap::new(),
        seq: 0,
        rng: Rng(p.get("seed") as u64),
        rate,
        now: 0,
        measure_from: warmup,
        measure_to: warmup + duration,
        tally: Tally::default(),
    };

    // The rides of the last twenty minutes, each in the phase it would be in now.
    let history = us(demand
        .rider_listen_s
        .max(demand.driver_busy_s + p.get("ask_s")));
    for cell in 0..cells {
        let mut t0 = 0;
        loop {
            t0 -= us(world.rng.exp(rate)).max(1);
            if t0 < -history {
                break;
            }
            world.new_trip(cell, t0);
        }
        let first = us(world.rng.exp(rate));
        world.at(first, Event::Arrival(cell));
    }
    let n = world.phones.len();
    for i in 0..n {
        let phase = us(p.get("drain_s")) * i as i64 / n as i64;
        world.at(phase, Event::Drain(i));
    }

    let start = Instant::now();
    let (mut router_from, mut bench_from) = (Usage::default(), Usage::default());
    let (mut router_to, mut bench_to) = (Usage::default(), Usage::default());
    let mut started = false;
    let mut late_ms: f64 = 0.0;
    loop {
        let now = start.elapsed().as_micros() as i64;
        if !started && now >= world.measure_from {
            started = true;
            router_from = router.usage();
            bench_from = Usage::of(std::process::id());
            eprintln!("gossip: measuring for {} s", p.get("duration_s"));
        }
        if started && router_to.cpu_s == 0.0 && now >= world.measure_to {
            router_to = router.usage();
            bench_to = Usage::of(std::process::id());
        }
        if now >= world.measure_to + grace {
            break;
        }
        while let Some(Reverse((t, _, _))) = world.events.peek() {
            if *t > now {
                break;
            }
            let Reverse((t, _, event)) = world.events.pop().unwrap();
            // How far behind its schedule the load generator runs: if this grows, the computer is the limit.
            if world.measuring(t) {
                late_ms = late_ms.max((now - t) as f64 / 1000.0);
            }
            world.now = now;
            world.handle(event);
        }
        let next = world
            .events
            .peek()
            .map(|Reverse((t, _, _))| *t)
            .unwrap_or(now + 5_000);
        let wait = (next - start.elapsed().as_micros() as i64).clamp(0, 5_000);
        std::thread::sleep(Duration::from_micros(wait as u64));
    }

    // ------------------------------------------------------------------ results
    let seconds = p.get("duration_s");
    let t = &mut world.tally;
    let cell_traffic = CellTraffic::for_rate(p, rate);
    let driver_phones: Vec<&Phone> = world.phones.iter().filter(|ph| ph.driver).collect();
    let rider_phones: Vec<&Phone> = world.phones.iter().filter(|ph| !ph.driver).collect();
    let heard = |ph: &Phone| {
        if ph.driver {
            around(grid, ph.cell).len() as f64
        } else {
            1.0
        }
    };
    let mean = |phones: &[&Phone], f: &dyn Fn(&Phone) -> f64| {
        phones.iter().map(|ph| f(ph)).sum::<f64>() / phones.len().max(1) as f64
    };
    let driver_msgs = mean(&driver_phones, &|ph| ph.received as f64 / seconds);
    let driver_msgs_model = mean(&driver_phones, &|ph| heard(ph) * cell_traffic.messages(p));
    let driver_bytes = mean(&driver_phones, &|ph| ph.received_bytes as f64 / seconds);
    let driver_bytes_model = mean(&driver_phones, &|ph| heard(ph) * cell_traffic.bytes(p));
    // Phones in the middle of the square hear nine cells, as a driver in a city does.
    let middle: Vec<&Phone> = driver_phones
        .iter()
        .copied()
        .filter(|ph| heard(ph) == 9.0)
        .collect();
    let subscribers: f64 = (0..cells)
        .map(|c| (world.listeners[c] + rider_nodes) as f64)
        .sum();
    let own_topic = p.get("ride_topics").clamp(0.0, 1.0) * cells as f64 * cell_traffic.position;
    let deliveries_model = cell_traffic.messages(p) * subscribers + own_topic;
    let router_cpu = router_to.cpu_s - router_from.cpu_s;
    let deliveries_per_s = t.deliveries as f64 / seconds;

    let mut by_kind = serde_json::Map::new();
    for kind in KINDS {
        let k = kind as usize;
        let model = [
            cell_traffic.request,
            cell_traffic.offer,
            cell_traffic.close,
            cell_traffic.route,
            cell_traffic.position,
        ][k];
        by_kind.insert(
            kind.name().into(),
            json!({
                "sent_per_s": t.sent[k] as f64 / seconds,
                "sent_per_s_per_cell": t.sent[k] as f64 / seconds / cells as f64,
                "model_per_s_per_cell": model,
                "delivered": ratio(t.delivered[k], t.expected[k]),
                "latency_ms": percentiles(&mut t.latency_ms[k]),
            }),
        );
    }

    Ok(json!({
        "cells": cells,
        "rides_per_s_per_cell": rate,
        "drivers_per_cell": people,
        "phones": world.phones.len(),
        "received_per_phone": world.phones.iter().map(|ph| ph.received).collect::<Vec<_>>(),
        "connected_at_end": world.phones.iter().filter(|ph| ph.node.connected_peers() > 0).count(),
        "trips_asked": t.trips,
        "drivers": {
            "phones": driver_phones.len(),
            "msgs_per_s": driver_msgs,
            "model_msgs_per_s": driver_msgs_model,
            "middle_msgs_per_s": mean(&middle, &|ph| ph.received as f64 / seconds),
            "bytes_per_s": driver_bytes,
            "model_bytes_per_s": driver_bytes_model,
            "mb_per_hour": driver_bytes * 3600.0 / 1e6,
            "largest_drain": driver_phones.iter().map(|ph| ph.largest_drain).max().unwrap_or(0),
            "drains_at_inbox_cap": driver_phones.iter().map(|ph| ph.full_drains).sum::<u64>(),
            "longest_list": driver_phones.iter().map(|ph| ph.longest_list).max().unwrap_or(0),
            "requests_seen": ratio(t.requests_seen, t.requests_expected),
            "requests_near_seen": ratio(t.requests_near_seen, t.requests_near_expected),
            "requests_dropped_list_full": ratio(t.requests_full, t.requests_expected),
            "request_first_seen_ms": percentiles(&mut t.request_first_ms),
            "routes_seen": ratio(t.routes_seen, t.routes_expected),
            "route_first_seen_ms": percentiles(&mut t.route_first_ms),
        },
        "riders": {
            "phones": rider_phones.len(),
            "msgs_per_s": mean(&rider_phones, &|ph| ph.received as f64 / seconds),
            "bytes_per_s": mean(&rider_phones, &|ph| ph.received_bytes as f64 / seconds),
            "offers_seen": ratio(t.offers_seen, t.offers_expected),
            "offer_first_seen_ms": percentiles(&mut t.offer_first_ms),
            "unmatched": ratio(t.unmatched, t.trips),
            "positions_delivered": ratio(t.delivered[Kind::Position as usize], t.expected[Kind::Position as usize]),
        },
        "router": {
            "deliveries_per_s": deliveries_per_s,
            "model_deliveries_per_s": deliveries_model,
            "cpu_cores": router_cpu / seconds,
            "cpu_s_per_million_deliveries": if t.deliveries > 0 { router_cpu / (t.deliveries as f64 / 1e6) } else { 0.0 },
            "written_mb_per_s": (router_to.written - router_from.written) / seconds / 1e6,
            "rss_mb": router_to.rss_mb,
        },
        "messages": by_kind,
        "load_generator": {
            "cpu_cores": (bench_to.cpu_s - bench_from.cpu_s) / seconds,
            "cores_available": std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1),
            "most_behind_ms": late_ms,
        },
    }))
}

/// The smallest question about the router: `phones` phones on one topic, each publishing once a second for
/// `seconds`, and the share of the others' messages each one received. Nothing about rides, so a delivery
/// problem it shows is the network's, not the ride model's.
pub fn probe(
    node_binary: &Path,
    phones: usize,
    seconds: u64,
    per_second: usize,
    size: usize,
    publishers: usize,
) -> Result<Value, String> {
    if phones < 2 || publishers == 0 || seconds == 0 || per_second == 0 {
        return Err(
            "the probe needs two phones or more, a publisher, a second and a message a second"
                .into(),
        );
    }
    let router = Router::start(node_binary)?;
    let nodes: Vec<Arc<GossipNode>> = (0..phones)
        .map(|_| {
            GossipNode::start(GossipConfig {
                listen: None,
                bootstrap: vec![router.address.clone()],
                router: false,
            })
        })
        .collect::<Result<_, _>>()
        .map_err(|e| e.to_string())?;
    let deadline = Instant::now() + Duration::from_secs(60);
    while nodes.iter().any(|n| n.connected_peers() == 0) {
        if Instant::now() > deadline {
            return Err("phones could not reach the router in a minute".into());
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let topic = topic(0);
    for n in &nodes {
        n.subscribe(topic.clone()).map_err(|e| e.to_string())?;
    }
    std::thread::sleep(Duration::from_secs(3));
    let mut received = vec![0u64; phones];
    let mut by_second = Vec::new();
    for s in 0..seconds {
        for (i, n) in nodes.iter().enumerate().take(publishers) {
            for k in 0..per_second {
                let mut data = format!("probe {s} {i} {k}").into_bytes();
                data.resize(size.max(data.len()), 0);
                let _ = n.publish(topic.clone(), data);
            }
        }
        std::thread::sleep(Duration::from_secs(1));
        let mut got = 0;
        for (i, n) in nodes.iter().enumerate() {
            let k = n.drain().len() as u64;
            received[i] += k;
            got += k;
        }
        by_second.push(got as f64 / (per_second * publishers * (phones - 1)) as f64);
    }
    // Each phone hears every publisher but itself.
    let expected_of =
        |i: usize| seconds * per_second as u64 * (publishers - usize::from(i < publishers)) as u64;
    let expected: u64 = (0..phones).map(expected_of).sum();
    let shares: Vec<f64> = received
        .iter()
        .enumerate()
        .map(|(i, r)| *r as f64 / expected_of(i).max(1) as f64)
        .collect();
    Ok(json!({
        "phones": phones,
        "delivered": received.iter().sum::<u64>() as f64 / expected as f64,
        "share_per_phone": shares,
        "phones_getting_most": shares.iter().filter(|s| **s > 0.9).count(),
        "phones_getting_little": shares.iter().filter(|s| **s < 0.1).count(),
        "by_second": by_second,
    }))
}

/// `klippelgo-node` next to this program, as `cargo build` leaves them.
pub fn default_node_binary() -> PathBuf {
    let me = std::env::current_exe().unwrap_or_default();
    me.with_file_name(if cfg!(windows) {
        "klippelgo-node.exe"
    } else {
        "klippelgo-node"
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_header_reads_back_and_pads_to_the_real_size() {
        let h = Header {
            kind: Kind::Route,
            target: 7,
            about: 42,
            origin_us: -5,
            sent_us: 1_000_000,
            cell: 4,
        };
        let data = h.encode(4800);
        assert_eq!(data.len(), 4800);
        let back = Header::decode(&data).unwrap();
        assert_eq!(
            (
                back.kind,
                back.target,
                back.about,
                back.origin_us,
                back.sent_us,
                back.cell
            ),
            (Kind::Route, 7, 42, -5, 1_000_000, 4)
        );
        assert!(Header::decode(b"not a bench message, but long enough to have a header").is_none());
    }

    #[test]
    fn a_driver_hears_the_cells_around_it_and_no_further() {
        // 0 1 2 / 3 4 5 / 6 7 8
        assert_eq!(around(3, 4).len(), 9);
        assert_eq!(around(3, 0), vec![0, 1, 3, 4]);
        assert_eq!(around(3, 5), vec![1, 2, 4, 5, 7, 8]);
        assert_eq!(around(1, 0), vec![0]);
    }
}
