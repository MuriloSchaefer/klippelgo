//! A route as steps along OpenStreetMap ways, and the check that it is one a vehicle could legally drive.
//!
//! A path is a list of `(way, node)` steps. The first step names the node the path starts at and a way that
//! node is on. Every later step moves to a neighbouring node along its way, so each step is one road segment
//! and its length is not open to interpretation.

use crate::geo::{project, segment_mm, Point};
use crate::index::Way;
use crate::{keccak256, Error, Hash};
use alloc::collections::BTreeMap;
use alloc::vec::Vec;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Vehicle {
    Car = 0,
    Motorcycle = 1,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Step {
    pub way: u64,
    pub node: u64,
}

/// keccak256(count u32 ‖ count × (way id u64 ‖ node id u64)), big-endian.
pub fn path_hash(steps: &[Step]) -> Hash {
    let mut bytes = Vec::with_capacity(4 + steps.len() * 16);
    bytes.extend_from_slice(&(steps.len() as u32).to_be_bytes());
    for step in steps {
        bytes.extend_from_slice(&step.way.to_be_bytes());
        bytes.extend_from_slice(&step.node.to_be_bytes());
    }
    keccak256(&[&bytes])
}

/// Follows the steps through the proven ways and returns the coordinates of each node passed. Fails when a way
/// is missing from the map, two consecutive nodes are not neighbours on the step's way, or the vehicle may not
/// travel that way in that direction.
pub fn resolve(
    steps: &[Step],
    ways: &BTreeMap<u64, Way>,
    vehicle: Vehicle,
) -> Result<Vec<Point>, Error> {
    if steps.len() < 2 {
        return Err(Error::PathTooShort);
    }
    let way = |step: usize| ways.get(&steps[step].way).ok_or(Error::UnknownWay { step });

    let start = way(0)?.nodes.iter().find(|n| n.id == steps[0].node);
    let mut points = Vec::with_capacity(steps.len());
    points.push(start.ok_or(Error::NotConnected { step: 0 })?.at.check()?);

    for step in 1..steps.len() {
        let (from, to) = (steps[step - 1].node, steps[step].node);
        let way = way(step)?;
        let mut blocked = false;
        let mut reached = None;
        for pair in way.nodes.windows(2) {
            let forward = pair[0].id == from && pair[1].id == to;
            let backward = pair[1].id == from && pair[0].id == to;
            if !forward && !backward {
                continue;
            }
            if way.allows(vehicle, forward) {
                reached = Some(if forward { pair[1].at } else { pair[0].at });
                break;
            }
            blocked = true;
        }
        match reached {
            Some(point) => points.push(point.check()?),
            None if blocked => return Err(Error::NotAllowed { step }),
            None => return Err(Error::NotConnected { step }),
        }
    }
    Ok(points)
}

/// A path's length between two points beside it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Measured {
    /// Along the path from where `start` projects on its first segment to where `end` projects on its last.
    pub distance_mm: u64,
    /// How far `start` and `end` are from the path.
    pub start_gap_mm: u64,
    pub end_gap_mm: u64,
}

/// Measures the path between a pickup and a drop-off. A route rarely starts exactly on a node, so the first and
/// last segments count only from and up to the point nearest the pickup and the drop-off.
pub fn measure(points: &[Point], start: Point, end: Point) -> Measured {
    let last = points.len() - 1;
    let total: u64 = points.windows(2).map(|s| segment_mm(s[0], s[1])).sum();
    let from = project(start, points[0], points[1]);
    let to = project(end, points[last - 1], points[last]);
    let tail = segment_mm(points[last - 1], points[last]).saturating_sub(to.along_mm);
    Measured {
        distance_mm: total.saturating_sub(from.along_mm).saturating_sub(tail),
        start_gap_mm: from.distance_mm,
        end_gap_mm: to.distance_mm,
    }
}

/// Millimetres to whole metres, rounding half up.
pub fn metres(mm: u64) -> u32 {
    ((mm + 500) / 1000).min(u32::MAX as u64) as u32
}
