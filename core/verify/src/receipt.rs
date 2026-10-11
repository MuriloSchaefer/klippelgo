//! The trip receipt rider and driver sign at arrival, and the check behind it.

use crate::geo::{distance_mm, project, reach, segment_mm};
use crate::index::Witness;
use crate::path::{metres, path_hash, resolve, Step};
use crate::quote::RouteQuote;
use crate::trace::{self, Fix, RideRef, Role, Trace};
use crate::{keccak256, Domain, Error, Hash, Pack, Params, Point};
use alloc::vec::Vec;

const RECEIPT_TAG: &[u8] = b"KlippelGo trip receipt v1";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TripReceipt {
    pub ride_id: u64,
    /// The ride's last quote: the one locked in the escrow, or its latest signed amendment.
    pub quote_hash: Hash,
    /// The final countersigned head of each phone's log.
    pub rider_head: Hash,
    pub driver_head: Hash,
    /// The roads actually driven, from map-matching the log.
    pub driven_path: Vec<Step>,
    /// Metres along `driven_path` between the first and the last fix, as [`check`] computes it.
    pub driven_distance_m: u32,
    /// Unix seconds: when the pickup proof was made and when the last fix was taken.
    pub started_at: u64,
    pub ended_at: u64,
    /// The quoted fare, as a 256-bit big-endian number.
    pub fare: [u8; 32],
}

impl TripReceipt {
    /// The hash both phones sign and the rider submits in `RideEscrow.complete` as `receiptHash`.
    ///
    /// ```text
    /// keccak256(
    ///   keccak256("KlippelGo trip receipt v1") ‖ chain id u64 ‖ escrow address ‖ ride id u256 ‖ quote hash ‖
    ///   rider head ‖ driver head ‖ driven path hash ‖ driven distance u32 ‖ started at u64 ‖ ended at u64 ‖
    ///   fare u256)
    /// ```
    pub fn hash(&self, domain: &Domain) -> Hash {
        let mut ride_id = [0u8; 32];
        ride_id[24..].copy_from_slice(&self.ride_id.to_be_bytes());
        keccak256(&[
            &keccak256(&[RECEIPT_TAG]),
            &domain.chain_id.to_be_bytes(),
            &domain.escrow,
            &ride_id,
            &self.quote_hash,
            &self.rider_head,
            &self.driver_head,
            &path_hash(&self.driven_path),
            &self.driven_distance_m.to_be_bytes(),
            &self.started_at.to_be_bytes(),
            &self.ended_at.to_be_bytes(),
            &self.fare,
        ])
    }
}

/// What the verifier found for a trip. These are the facts a dispute turns on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TripReport {
    pub driven_distance_m: u32,
    pub quoted_distance_m: u32,
    /// How far the first fix is from the quoted pickup and the last fix from the quoted drop-off, in metres.
    pub pickup_gap_m: u32,
    pub dropoff_gap_m: u32,
    /// The trip ended within the endpoint tolerance of the quoted drop-off.
    pub reached_dropoff: bool,
    pub duration_s: u64,
    /// Longest silence between two fixes in any of the logs, in milliseconds.
    pub longest_gap_ms: u64,
    /// How many logs were checked: one, or both.
    pub traces: u32,
}

/// Checks a receipt against the quote it settles, the map and the logs. With both phones' logs the two must
/// tell the same trip; one log is enough to check a receipt when the other party withholds theirs, because each
/// log carries the other phone's countersignatures.
///
/// The driven distance is measured on the driver's log when it is given and on the rider's otherwise; the other
/// log must agree with it within the distance tolerance.
pub fn check(
    receipt: &TripReceipt,
    quote: &RouteQuote,
    traces: &[Trace],
    witness: &Witness,
    pack: &Pack,
    domain: &Domain,
    params: &Params,
) -> Result<TripReport, Error> {
    let report = measure(receipt, quote, traces, witness, pack, domain, params)?;
    if report.driven_distance_m != receipt.driven_distance_m {
        return Err(Error::DistanceMismatch {
            claimed_m: receipt.driven_distance_m,
            computed_m: report.driven_distance_m,
        });
    }
    Ok(report)
}

/// Everything [`check`] does except holding the receipt to its own `driven_distance_m`. A phone calls this on
/// a draft receipt to learn the distance it then writes in and signs.
pub fn measure(
    receipt: &TripReceipt,
    quote: &RouteQuote,
    traces: &[Trace],
    witness: &Witness,
    pack: &Pack,
    domain: &Domain,
    params: &Params,
) -> Result<TripReport, Error> {
    if quote.hash(domain) != receipt.quote_hash {
        return Err(Error::WrongQuote);
    }
    if quote.pack_id != pack.id {
        return Err(Error::WrongPack);
    }
    if quote.fare != receipt.fare {
        return Err(Error::WrongFare);
    }
    let ways = witness.ways(&pack.proof_root)?;
    let points = resolve(&receipt.driven_path, &ways, quote.vehicle)?;
    let ride = RideRef {
        domain: *domain,
        ride_id: receipt.ride_id,
        quote_hash: receipt.quote_hash,
    };

    let by_role = |role| traces.iter().find(|t| t.role == role);
    let (primary, secondary) = match (by_role(Role::Driver), by_role(Role::Rider)) {
        (Some(driver), rider) => (driver, rider),
        (None, Some(rider)) => (rider, None),
        (None, None) => return Err(Error::EmptyTrace),
    };
    let mut report = TripReport {
        driven_distance_m: 0,
        quoted_distance_m: quote.distance_m,
        pickup_gap_m: 0,
        dropoff_gap_m: 0,
        reached_dropoff: false,
        duration_s: 0,
        longest_gap_ms: 0,
        traces: 0,
    };
    for log in [Some(primary), secondary].into_iter().flatten() {
        let (owner, peer, head) = match log.role {
            Role::Rider => (&quote.rider, &quote.driver, &receipt.rider_head),
            Role::Driver => (&quote.driver, &quote.rider, &receipt.driver_head),
        };
        let checked = trace::check(log, &ride, owner, peer, params)?;
        if checked.head != *head {
            return Err(Error::WrongHead { role: log.role });
        }
        let driven_m = metres(follow(&points, &log.fixes, params)?);
        if report.traces == 0 {
            report.driven_distance_m = driven_m;
        } else if !params.distances_agree(driven_m, report.driven_distance_m) {
            return Err(Error::DistanceMismatch {
                claimed_m: report.driven_distance_m,
                computed_m: driven_m,
            });
        }
        report.longest_gap_ms = report.longest_gap_ms.max(checked.longest_gap_ms);
        report.traces += 1;
    }
    if let Some(other) = secondary {
        if other.key != primary.peer_key || primary.key != other.peer_key {
            return Err(Error::BadCertificate);
        }
    }

    let (first, last) = (primary.fixes[0], primary.fixes[primary.fixes.len() - 1]);
    if receipt.ended_at != last.t_ms / 1000 || receipt.started_at > receipt.ended_at {
        return Err(Error::WrongTimes);
    }
    report.duration_s = receipt.ended_at - receipt.started_at;
    report.pickup_gap_m = metres(distance_mm(first.at, quote.pickup));
    report.dropoff_gap_m = metres(distance_mm(last.at, quote.dropoff));
    report.reached_dropoff = report.dropoff_gap_m <= params.endpoint_tolerance_m;
    Ok(report)
}

/// Follows a log along the driven path and returns the distance covered between its first and last usable fix,
/// in millimetres.
///
/// Each fix is placed on the path within its own accuracy plus the margin. It may not be placed behind the
/// fix before it, nor further ahead than a vehicle could have got, by two measures: the top speed, and the
/// straight-line distance the log itself moved, stretched by the detour tolerance. Among the places left, the
/// one that is both near the fix and near where the log's movement predicts wins.
///
/// A log that cannot be placed this way did not drive this path. The path cannot be padded either: a stretch
/// nobody drove would have to be crossed without the log moving.
///
/// Fixes the phone itself rated worse than `max_accuracy_m` (tunnels, urban canyons) are skipped here. They
/// still count in the hash chain and the motion checks.
fn follow(points: &[Point], fixes: &[Fix], params: &Params) -> Result<u64, Error> {
    let mut starts = Vec::with_capacity(points.len());
    let mut total = 0u64;
    for segment in points.windows(2) {
        starts.push(total);
        total += segment_mm(segment[0], segment[1]);
    }
    let stretch = |straight: u64| {
        (straight as u128 * (10_000 + params.detour_tolerance_bps as u128) / 10_000) as i128
    };

    struct Placed<'a> {
        first: u64,
        at: u64,
        fix: &'a Fix,
        /// Straight-line distance the log has moved so far.
        moved: u64,
        /// The smallest lead of the path position over the stretched log movement seen so far. A later fix
        /// may not be placed further ahead of its own movement than this plus the slack.
        lead: i128,
    }
    let mut placed: Option<Placed> = None;
    for (i, fix) in fixes.iter().enumerate() {
        if fix.accuracy_cm as u64 > params.max_accuracy_m as u64 * 100 {
            continue;
        }
        let slack = fix.accuracy_cm as u64 * 10 + params.trace_margin_m as u64 * 1000;
        // The stretch of path this fix may be placed on, and where the log's own movement expects it.
        let (floor, limit, expected, moved) = match &placed {
            None => (0, u64::MAX, None, 0),
            Some(p) => {
                let slack =
                    slack + p.fix.accuracy_cm as u64 * 10 + params.trace_margin_m as u64 * 1000;
                let straight = distance_mm(p.fix.at, fix.at);
                let moved = p.moved + straight;
                let by_speed = p.at
                    + params.max_speed_cm_s as u64 * fix.t_ms.saturating_sub(p.fix.t_ms) / 100
                    + slack;
                let by_movement = (p.lead + stretch(moved) + 2 * slack as i128).max(0) as u64;
                (
                    p.at.saturating_sub(slack),
                    by_speed.min(by_movement),
                    Some(p.at + straight),
                    moved,
                )
            }
        };

        let (dlat, dlon) = reach(fix.at, slack);
        let mut best: Option<(u64, u64)> = None; // misfit, position
        let mut beyond = false;
        let from = starts
            .partition_point(|start| *start <= floor)
            .saturating_sub(1);
        for s in from..starts.len() {
            let (a, b) = (points[s], points[s + 1]);
            let outside = |p: i32, a: i32, b: i32, d: i64| {
                (p as i64) < a.min(b) as i64 - d || p as i64 > a.max(b) as i64 + d
            };
            if outside(fix.at.lat, a.lat, b.lat, dlat) || outside(fix.at.lon, a.lon, b.lon, dlon) {
                continue;
            }
            let on = project(fix.at, a, b);
            let position = starts[s] + on.along_mm;
            if on.distance_mm > slack || position < floor {
                continue;
            }
            if position > limit {
                beyond = true;
                continue;
            }
            let misfit = on.distance_mm + expected.map_or(0, |e| position.abs_diff(e) / 2);
            if best.is_none_or(|(least, _)| misfit < least) {
                best = Some((misfit, position));
            }
        }
        let Some((_, position)) = best else {
            return Err(if beyond {
                Error::TooFast { fix: i }
            } else {
                Error::OffPath { fix: i }
            });
        };
        placed = Some(match placed {
            None => Placed {
                first: position,
                at: position,
                fix,
                moved,
                lead: position as i128,
            },
            Some(p) => {
                let at = position.max(p.at);
                Placed {
                    first: p.first,
                    at,
                    fix,
                    moved,
                    lead: p.lead.min(at as i128 - stretch(moved)),
                }
            }
        });
    }
    let placed = placed.ok_or(Error::EmptyTrace)?;
    Ok(placed.at - placed.first)
}

#[cfg(test)]
mod tests {
    use super::*;

    const LAT: i32 = -235_500_000;
    const LON: i32 = -466_300_000;

    /// A point `east` and `north` metres from a corner in São Paulo.
    fn at(east: i32, north: i32) -> Point {
        Point {
            lat: LAT + north * 90,
            lon: LON + east * 98,
        }
    }

    /// A fix every 2 s walking the corners at 10 m/s.
    fn drive(corners: &[Point]) -> Vec<Fix> {
        let mut fixes = Vec::new();
        for pair in corners.windows(2) {
            let n = (segment_mm(pair[0], pair[1]) / 20_000).max(1) as i32;
            for k in 0..n {
                fixes.push(Fix {
                    t_ms: 1_700_000_000_000 + fixes.len() as u64 * 2000,
                    at: Point {
                        lat: pair[0].lat + (pair[1].lat - pair[0].lat) * k / n,
                        lon: pair[0].lon + (pair[1].lon - pair[0].lon) * k / n,
                    },
                    accuracy_cm: 500,
                    speed_cm_s: 1000,
                    mock: false,
                });
            }
        }
        fixes
    }

    #[test]
    fn follows_a_tight_loop_whose_every_point_is_within_gps_slack() {
        // 200 m east, once round a 20 m square (a loop ramp, a small roundabout), then 200 m on. Every point of
        // the loop is within the 30 m slack of every fix on it, so only the log's own movement tells where on
        // it the vehicle is.
        let path = [
            at(0, 0),
            at(200, 0),
            at(220, 0),
            at(220, 20),
            at(200, 20),
            at(200, 0),
            at(200, -200),
        ];
        let driven = follow(&path, &drive(&path), &Params::default()).unwrap();
        let length: u64 = path.windows(2).map(|s| segment_mm(s[0], s[1])).sum();
        assert!(driven.abs_diff(length) < 25_000, "{driven} of {length}");

        // The same log does not back a path that goes round the loop three times.
        let mut thrice = path[..2].to_vec();
        for _ in 0..3 {
            thrice.extend_from_slice(&path[2..6]);
        }
        thrice.push(path[6]);
        assert!(matches!(
            follow(&thrice, &drive(&path), &Params::default()),
            Err(Error::TooFast { .. })
        ));
    }

    #[test]
    fn skips_fixes_the_phone_rated_poorly() {
        let path = [at(0, 0), at(400, 0)];
        let mut fixes = drive(&path);
        // A tunnel: three fixes far off the road, honestly reported as poor.
        for fix in &mut fixes[5..8] {
            fix.at.lat += 9_000;
            fix.accuracy_cm = 20_000;
        }
        assert!(follow(&path, &fixes, &Params::default()).is_ok());
        // Claimed as good fixes, they are off the path.
        for fix in &mut fixes[5..8] {
            fix.accuracy_cm = 500;
        }
        assert_eq!(
            follow(&path, &fixes, &Params::default()),
            Err(Error::OffPath { fix: 5 })
        );
    }
}
