//! The route quote rider and driver sign before the fare is locked, and the check behind it.

use crate::index::Witness;
use crate::path::{measure, metres, path_hash, resolve, Step, Vehicle};
use crate::{keccak256, Address, Domain, Error, Hash, Pack, Params, Point};
use alloc::vec::Vec;

const ROUTE_TAG: &[u8] = b"KlippelGo route v1";
const QUOTE_TAG: &[u8] = b"KlippelGo route quote v1";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteQuote {
    /// `manifestHash` of the map pack the route was computed on.
    pub pack_id: Hash,
    pub pickup: Point,
    pub dropoff: Point,
    pub vehicle: Vehicle,
    pub path: Vec<Step>,
    /// Metres along `path` from the pickup to the drop-off, as [`check`] computes it.
    pub distance_m: u32,
    /// The router's estimate, in seconds. Nothing checks it.
    pub duration_s: u32,
    /// Random, so the route hash gives nothing away about the places it commits to.
    pub salt: Hash,
    /// The accepted offer, in the token's base units, as a 256-bit big-endian number.
    pub fare: [u8; 32],
    pub token: Address,
    pub rider: Address,
    pub driver: Address,
    /// Unix seconds.
    pub expiry: u64,
    /// Hash of the quote this one amends (a stop or route change mid-trip), or zero for a ride's first quote.
    pub previous: Hash,
}

impl RouteQuote {
    /// The rider's commitment to a route. It goes out in the gossip request, where it reveals nothing, and a
    /// driver's offer signs over it, so the route the driver later checks is the one they priced.
    ///
    /// ```text
    /// keccak256(
    ///   keccak256("KlippelGo route v1") ‖ pack id ‖ pickup lat i32 ‖ pickup lon i32 ‖ dropoff lat i32 ‖
    ///   dropoff lon i32 ‖ vehicle u8 ‖ path hash ‖ distance u32 ‖ duration u32 ‖ salt)
    /// ```
    pub fn route_hash(&self) -> Hash {
        keccak256(&[
            &keccak256(&[ROUTE_TAG]),
            &self.pack_id,
            &self.pickup.lat.to_be_bytes(),
            &self.pickup.lon.to_be_bytes(),
            &self.dropoff.lat.to_be_bytes(),
            &self.dropoff.lon.to_be_bytes(),
            &[self.vehicle as u8],
            &path_hash(&self.path),
            &self.distance_m.to_be_bytes(),
            &self.duration_s.to_be_bytes(),
            &self.salt,
        ])
    }

    /// The hash both phones sign and the rider locks in `RideEscrow.open` as `quoteHash`.
    pub fn hash(&self, domain: &Domain) -> Hash {
        quote_hash(
            domain,
            &self.route_hash(),
            &self.fare,
            &self.token,
            &self.rider,
            &self.driver,
            self.expiry,
            &self.previous,
        )
    }
}

/// The quote hash from a route hash and the terms of the offer. A driver computes it this way when quoting,
/// before the rider has revealed the route.
///
/// ```text
/// keccak256(
///   keccak256("KlippelGo route quote v1") ‖ chain id u64 ‖ escrow address ‖ route hash ‖ fare u256 ‖ token ‖
///   rider ‖ driver ‖ expiry u64 ‖ previous quote hash)
/// ```
#[allow(clippy::too_many_arguments)]
pub fn quote_hash(
    domain: &Domain,
    route_hash: &Hash,
    fare: &[u8; 32],
    token: &Address,
    rider: &Address,
    driver: &Address,
    expiry: u64,
    previous: &Hash,
) -> Hash {
    keccak256(&[
        &keccak256(&[QUOTE_TAG]),
        &domain.chain_id.to_be_bytes(),
        &domain.escrow,
        route_hash,
        fare,
        token,
        rider,
        driver,
        &expiry.to_be_bytes(),
        previous,
    ])
}

/// What the verifier found for a route.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RouteReport {
    pub distance_m: u32,
    /// How far the pickup and drop-off points are from the path, in metres.
    pub pickup_gap_m: u32,
    pub dropoff_gap_m: u32,
}

/// Measures a path on a pack's map without trusting any number that came with it: the chunks must belong to
/// the pack, the path must be drivable, and it must start and end near the two points.
pub fn check_route(
    path: &[Step],
    vehicle: Vehicle,
    pickup: Point,
    dropoff: Point,
    witness: &Witness,
    pack: &Pack,
    params: &Params,
) -> Result<RouteReport, Error> {
    let ways = witness.ways(&pack.proof_root)?;
    let points = resolve(path, &ways, vehicle)?;
    let measured = measure(&points, pickup.check()?, dropoff.check()?);
    let report = RouteReport {
        distance_m: metres(measured.distance_mm),
        pickup_gap_m: metres(measured.start_gap_mm),
        dropoff_gap_m: metres(measured.end_gap_mm),
    };
    let limit = params.endpoint_tolerance_m as u64 * 1000;
    if measured.start_gap_mm > limit {
        return Err(Error::StartTooFar {
            metres: report.pickup_gap_m,
        });
    }
    if measured.end_gap_mm > limit {
        return Err(Error::EndTooFar {
            metres: report.dropoff_gap_m,
        });
    }
    Ok(report)
}

/// Checks a quote: its route passes [`check_route`] on the pack it names and its distance is the one the
/// verifier computes.
pub fn check(
    quote: &RouteQuote,
    witness: &Witness,
    pack: &Pack,
    params: &Params,
) -> Result<RouteReport, Error> {
    if quote.pack_id != pack.id {
        return Err(Error::WrongPack);
    }
    let report = check_route(
        &quote.path,
        quote.vehicle,
        quote.pickup,
        quote.dropoff,
        witness,
        pack,
        params,
    )?;
    if report.distance_m != quote.distance_m {
        return Err(Error::DistanceMismatch {
            claimed_m: quote.distance_m,
            computed_m: report.distance_m,
        });
    }
    Ok(report)
}
