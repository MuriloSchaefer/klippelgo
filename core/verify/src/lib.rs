//! The deterministic verifier behind every KlippelGo route quote and trip receipt (docs/maps-and-routing.md).
//!
//! Valhalla searches for routes and matches GPS traces; this crate checks what it returns against a map pack's
//! proof index, whose Merkle root governance published on chain. It uses integers only, allocates plain vectors
//! and maps, and does no I/O, so a phone, a juror's laptop and a zkVM all get the same answer from the same
//! bytes.
//!
//! - [`index`]: the proof index format, its chunks and Merkle tree.
//! - [`path`]: a route as `(way, node)` steps, checked against the index.
//! - [`geo`]: fixed-point distances.
//! - [`quote`] and [`receipt`]: canonical encodings, hashes and the checks behind each.
//! - [`trace`]: the GPS log, its hash chain and signed heads.
//! - [`sig`]: wallet and trace-key signatures.

#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

pub mod geo;
pub mod index;
pub mod path;
pub mod quote;
pub mod receipt;
pub mod sig;
pub mod trace;

pub use geo::Point;
pub use index::{Way, WayNode, Witness};
pub use path::{Step, Vehicle};
pub use quote::{RouteQuote, RouteReport};
pub use receipt::{TripReceipt, TripReport};
pub use trace::{Fix, Role, Trace};

pub type Hash = [u8; 32];
pub type Address = [u8; 20];

pub fn keccak256(parts: &[&[u8]]) -> Hash {
    use tiny_keccak::{Hasher, Keccak};
    let mut hasher = Keccak::v256();
    for part in parts {
        hasher.update(part);
    }
    let mut out = [0u8; 32];
    hasher.finalize(&mut out);
    out
}

/// The escrow a quote or receipt belongs to, so a hash signed for one chain or deployment means nothing on
/// another.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Domain {
    pub chain_id: u64,
    pub escrow: Address,
}

/// The map pack a proof is checked against: `MapRegistry` maps the pack id to its proof root.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pack {
    /// keccak-256 of the pack's `manifest.json`.
    pub id: Hash,
    /// Merkle root of the pack's proof index.
    pub proof_root: Hash,
}

/// Tolerances the checks use. Governance sets them in `MapRegistry`; the defaults are the launch values.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Params {
    /// How far the path may start from the pickup point and end from the drop-off point, in metres.
    pub endpoint_tolerance_m: u32,
    /// How far the two phones' route distances may differ before the driver is warned, in basis points.
    pub distance_tolerance_bps: u32,
    /// Slack added to a fix's own reported accuracy when checking that it lies on the driven path, in metres.
    pub trace_margin_m: u32,
    /// Fixes the phone rates worse than this are not used to place the trip on the path, in metres.
    pub max_accuracy_m: u32,
    /// How much longer a stretch of driven path may be than the straight lines between the fixes placed on
    /// it, in basis points. Roads curve between fixes; a path padded with streets nobody drove curves more.
    pub detour_tolerance_bps: u32,
    /// Fastest a vehicle can plausibly move, in cm/s.
    pub max_speed_cm_s: u32,
    /// Largest plausible change of reported speed, in cm/s².
    pub max_accel_cm_s2: u32,
    /// Longest stretch of trace a single signed head may cover, in milliseconds.
    pub max_head_interval_ms: u32,
}

impl Default for Params {
    fn default() -> Self {
        Params {
            endpoint_tolerance_m: 50,
            distance_tolerance_bps: 200,
            trace_margin_m: 25,
            max_accuracy_m: 50,
            detour_tolerance_bps: 2_500,
            max_speed_cm_s: 6_000,
            max_accel_cm_s2: 1_200,
            max_head_interval_ms: 60_000,
        }
    }
}

impl Params {
    /// Whether two phones' distances for the same trip agree closely enough to sign one quote.
    pub fn distances_agree(&self, a_m: u32, b_m: u32) -> bool {
        let (lo, hi) = if a_m <= b_m { (a_m, b_m) } else { (b_m, a_m) };
        (hi - lo) as u64 * 10_000 <= hi as u64 * self.distance_tolerance_bps as u64
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    // ---- the map
    /// A chunk does not hash into the pack's proof root.
    ChunkNotInPack {
        chunk: u32,
    },
    /// A chunk's bytes are not in the canonical form.
    MalformedChunk {
        chunk: u32,
    },
    /// The quote or receipt names a different pack from the one being checked against.
    WrongPack,
    // ---- the path
    PathTooShort,
    /// The path uses a way the witness has no chunk for.
    UnknownWay {
        step: usize,
    },
    /// The step's two nodes are not neighbours on its way.
    NotConnected {
        step: usize,
    },
    /// The way exists but this vehicle may not travel it in this direction.
    NotAllowed {
        step: usize,
    },
    /// A coordinate is too close to a pole for the distance formula.
    OutOfRange,
    // ---- the endpoints and distance
    StartTooFar {
        metres: u32,
    },
    EndTooFar {
        metres: u32,
    },
    DistanceMismatch {
        claimed_m: u32,
        computed_m: u32,
    },
    // ---- the receipt
    WrongQuote,
    WrongFare,
    WrongHead {
        role: trace::Role,
    },
    WrongTimes,
    // ---- the trace
    EmptyTrace,
    FixesOutOfOrder {
        fix: usize,
    },
    MockLocation {
        fix: usize,
    },
    /// The fix is further from the driven path than its accuracy allows, or would need the trip to run
    /// backwards along it.
    OffPath {
        fix: usize,
    },
    TooFast {
        fix: usize,
    },
    ImpossibleAcceleration {
        fix: usize,
    },
    /// The signed heads do not cover the log: one is missing, out of order, or not the head the fixes hash to.
    BadHead {
        head: usize,
    },
    HeadsTooFarApart {
        head: usize,
    },
    BadSignature {
        head: usize,
    },
    /// The final head lacks the other phone's signature.
    NotCountersigned,
    /// The wallet did not certify this trace key for this ride.
    BadCertificate,
}
