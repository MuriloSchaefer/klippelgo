//! Map packs, routes and trip proofs for the apps (docs/maps-and-routing.md).
//!
//! The phone's Valhalla does the searching and this module does everything around it: it opens and checks the
//! map pack, searches addresses in it, builds the requests Valhalla needs, turns Valhalla's answers into paths,
//! measures them with the verifier, and produces the hashes rider and driver sign. Valhalla's answers are
//! handed in as JSON and treated as untrusted. A route or trip the verifier refuses comes back as
//! [`MapError::Refused`] with the reason.

use klippelgo_proofs::evidence::{fare_bytes, Evidence, QuoteFile, ReceiptFile, TraceFile};
use klippelgo_proofs::explain::explain;
use klippelgo_proofs::pack::{BASEMAP_FILE, ROUTING_FILE, SEARCH_FILE};
use klippelgo_proofs::search::{Kind, Search};
use klippelgo_proofs::{hex, valhalla};
use klippelgo_verify::trace::{self, Log, RideRef};
use klippelgo_verify::{
    quote, receipt, Domain, Fix, Params, Point, Role, RouteQuote, Step, Trace, TripReceipt, Vehicle,
};
use std::sync::{Arc, Mutex};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MapError {
    #[error("map pack: {0}")]
    Pack(String),
    #[error("search: {0}")]
    Search(String),
    #[error("routing: {0}")]
    Routing(String),
    /// The verifier did not accept a route, quote or trip.
    #[error("not verified: {0}")]
    Refused(String),
    #[error("invalid input: {0}")]
    Invalid(String),
}

impl From<klippelgo_proofs::Error> for MapError {
    fn from(e: klippelgo_proofs::Error) -> Self {
        use klippelgo_proofs::Error as E;
        match e {
            E::Valhalla(_) | E::OffTheMap { .. } | E::UnknownWay(_) => {
                MapError::Routing(e.to_string())
            }
            E::Evidence(_) => MapError::Invalid(e.to_string()),
            _ => MapError::Pack(e.to_string()),
        }
    }
}

impl From<klippelgo_verify::Error> for MapError {
    fn from(e: klippelgo_verify::Error) -> Self {
        MapError::Refused(explain(&e))
    }
}

/// A point in degrees × 10⁷, the exact form quotes and proofs use.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct Coordinate {
    pub lat_e7: i32,
    pub lon_e7: i32,
}

impl From<Point> for Coordinate {
    fn from(p: Point) -> Self {
        Coordinate {
            lat_e7: p.lat,
            lon_e7: p.lon,
        }
    }
}

impl From<Coordinate> for Point {
    fn from(c: Coordinate) -> Self {
        Point {
            lat: c.lat_e7,
            lon: c.lon_e7,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct Bounds {
    pub south_west: Coordinate,
    pub north_east: Coordinate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum VehicleKind {
    Car,
    Motorcycle,
}

impl VehicleKind {
    fn vehicle(self) -> Vehicle {
        match self {
            VehicleKind::Car => Vehicle::Car,
            VehicleKind::Motorcycle => Vehicle::Motorcycle,
        }
    }

    fn costing(self) -> &'static str {
        match self {
            VehicleKind::Car => "auto",
            VehicleKind::Motorcycle => "motorcycle",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlaceKind {
    Address,
    Street,
    Neighbourhood,
    PointOfInterest,
}

/// A search result.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Place {
    pub kind: PlaceKind,
    pub name: String,
    /// What tells it apart from others of the same name, e.g. its neighbourhood. May be empty.
    pub context: String,
    pub at: Coordinate,
    /// From the point the search was centred on, when there was one.
    pub distance_m: Option<u32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PathStep {
    pub way: u64,
    pub node: u64,
}

/// A route the verifier has measured on a map pack.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MeasuredRoute {
    /// Where the route meets the road at each end. These, not the pins the rider dropped, go into the quote.
    pub pickup: Coordinate,
    pub dropoff: Coordinate,
    pub vehicle: VehicleKind,
    pub path: Vec<PathStep>,
    /// By the verifier, from the map pack's node coordinates.
    pub distance_m: u32,
    /// Valhalla's estimate. It knows no traffic.
    pub duration_s: u32,
    /// The route's shape, for drawing.
    pub line: Vec<Coordinate>,
}

/// The escrow a quote or receipt is for.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EscrowRef {
    pub chain_id: u64,
    /// `0x` address of `RideEscrow`.
    pub escrow: String,
}

impl EscrowRef {
    fn domain(&self) -> Result<Domain, MapError> {
        Ok(Domain {
            chain_id: self.chain_id,
            escrow: address(&self.escrow)?,
        })
    }
}

/// The terms of a driver's offer.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OfferTerms {
    /// In the token's base units, as a decimal string.
    pub fare: String,
    pub token: String,
    pub rider: String,
    pub driver: String,
    /// Unix seconds.
    pub expiry: u64,
}

/// What a trip receipt was found to say.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TripSummary {
    pub receipt_hash: Vec<u8>,
    pub driven_distance_m: u32,
    pub quoted_distance_m: u32,
    pub duration_s: u64,
    /// How far the trip started from the quoted pickup and ended from the quoted drop-off.
    pub pickup_gap_m: u32,
    pub dropoff_gap_m: u32,
    pub reached_dropoff: bool,
    /// How many phones' logs backed it: 1 or 2.
    pub logs: u32,
}

fn address(text: &str) -> Result<[u8; 20], MapError> {
    hex::decode_array(text).ok_or_else(|| MapError::Invalid(format!("{text} is not an address")))
}

fn hash(bytes: &[u8]) -> Result<[u8; 32], MapError> {
    bytes
        .try_into()
        .map_err(|_| MapError::Invalid("a hash must be 32 bytes".into()))
}

fn steps(path: &[PathStep]) -> Vec<Step> {
    path.iter()
        .map(|s| Step {
            way: s.way,
            node: s.node,
        })
        .collect()
}

/// A map pack on this phone.
#[derive(uniffi::Object)]
pub struct MapPack {
    pack: klippelgo_proofs::MapPack,
    search: Mutex<Option<Search>>,
}

#[uniffi::export]
impl MapPack {
    /// Opens the pack in `dir` and checks its proof index against its manifest. Before using it for a quote,
    /// ask `MapRegistry` whether [`MapPack::id`] is an active pack with [`MapPack::proof_root`].
    #[uniffi::constructor]
    pub fn open(dir: String) -> Result<Arc<Self>, MapError> {
        Ok(Arc::new(MapPack {
            pack: klippelgo_proofs::MapPack::open(dir)?,
            search: Mutex::new(None),
        }))
    }

    /// keccak-256 of the manifest: the id `MapRegistry` knows the pack by.
    pub fn id(&self) -> Vec<u8> {
        self.pack.id.to_vec()
    }

    pub fn proof_root(&self) -> Vec<u8> {
        self.pack.manifest.proof_root.to_vec()
    }

    pub fn region(&self) -> String {
        self.pack.manifest.region.clone()
    }

    /// Year and month, e.g. 202611.
    pub fn version(&self) -> u32 {
        self.pack.manifest.version
    }

    /// The area the pack covers, when it was cut from a larger extract.
    pub fn bounds(&self) -> Option<Bounds> {
        self.pack
            .manifest
            .osm
            .bbox
            .map(|[west, south, east, north]| Bounds {
                south_west: Coordinate {
                    lat_e7: south,
                    lon_e7: west,
                },
                north_east: Coordinate {
                    lat_e7: north,
                    lon_e7: east,
                },
            })
    }

    /// Hashes every file against the manifest. Takes seconds on a full pack: run it once after a download.
    pub fn check_files(&self) -> Result<(), MapError> {
        Ok(self.pack.check_files()?)
    }

    /// The vector tiles, for MapLibre.
    pub fn basemap_path(&self) -> String {
        self.pack.file(BASEMAP_FILE).to_string_lossy().into_owned()
    }

    /// The routing graph, for Valhalla.
    pub fn routing_path(&self) -> String {
        self.pack.file(ROUTING_FILE).to_string_lossy().into_owned()
    }

    /// Finds addresses, streets, neighbourhoods and named places. Results near `near` come first among equally
    /// good matches. Nothing leaves the phone.
    pub fn search(
        &self,
        query: String,
        near: Option<Coordinate>,
        limit: u32,
    ) -> Result<Vec<Place>, MapError> {
        self.with_search(|s| s.search(&query, near.map(Point::from), limit as usize))
            .map(|hits| hits.into_iter().map(place).collect())
    }

    /// Names a point: its address if one is within 30 m, otherwise the nearest street.
    pub fn reverse(&self, at: Coordinate) -> Result<Option<Place>, MapError> {
        self.with_search(|s| s.reverse(at.into()))
            .map(|hit| hit.map(place))
    }

    /// Turns Valhalla's route into a path on this pack and measures it with the verifier.
    ///
    /// `route_json` is Valhalla's answer to [`route_request`] and `attributes_json` its answer to
    /// [`attributes_request`] for that route.
    pub fn measure_route(
        &self,
        route_json: String,
        attributes_json: String,
        vehicle: VehicleKind,
    ) -> Result<MeasuredRoute, MapError> {
        let route = valhalla::parse_route(&route_json)?;
        let path = valhalla::steps(&valhalla::parse_edges(&attributes_json)?, |way| {
            self.pack.way(way)
        })?;
        let (Some(&pickup), Some(&dropoff)) = (route.points.first(), route.points.last()) else {
            return Err(MapError::Routing("the route has no shape".into()));
        };
        let witness = self.pack.witness(path.iter().map(|s| s.way))?;
        let report = quote::check_route(
            &path,
            vehicle.vehicle(),
            pickup,
            dropoff,
            &witness,
            &self.pack.pack(),
            &Params::default(),
        )?;
        Ok(MeasuredRoute {
            pickup: pickup.into(),
            dropoff: dropoff.into(),
            vehicle,
            path: path
                .iter()
                .map(|s| PathStep {
                    way: s.way,
                    node: s.node,
                })
                .collect(),
            distance_m: report.distance_m,
            duration_s: route.time_s,
            line: route.points.into_iter().map(Coordinate::from).collect(),
        })
    }

    /// Checks a quote the other phone sent, on this phone's own copy of the pack: the route is drivable, starts
    /// and ends where it says, and is as long as it says. Returns the quote hash to sign. `quote_json` is the
    /// `quote` part of an evidence file.
    pub fn check_quote(&self, escrow: EscrowRef, quote_json: String) -> Result<Vec<u8>, MapError> {
        let file: QuoteFile =
            serde_json::from_str(&quote_json).map_err(|e| MapError::Invalid(e.to_string()))?;
        let route_quote = RouteQuote::try_from(&file)?;
        let witness = self.pack.witness(route_quote.path.iter().map(|s| s.way))?;
        quote::check(
            &route_quote,
            &witness,
            &self.pack.pack(),
            &Params::default(),
        )?;
        Ok(route_quote.hash(&escrow.domain()?).to_vec())
    }

    /// Writes the trip receipt for a finished ride. `evidence_json` holds the quote and the logs so far (one
    /// phone's or both); `matched_json` is Valhalla's answer to [`match_request`] for this phone's log. The
    /// driven path is the quoted route when the logs followed it, and the map-matched path otherwise, with
    /// detours that map-matching invented dropped. Returns the evidence with the receipt added;
    /// [`MapPack::check_trip`] gives its hash.
    pub fn draft_receipt(
        &self,
        evidence_json: String,
        matched_json: String,
        ride_id: u64,
        started_at: u64,
    ) -> Result<String, MapError> {
        let mut evidence = Evidence::parse(&evidence_json)?;
        let domain = evidence.domain();
        let route_quote = RouteQuote::try_from(&evidence.quote)?;
        let traces = evidence
            .traces
            .iter()
            .map(Trace::try_from)
            .collect::<Result<Vec<_>, _>>()?;
        let head = |role| {
            traces
                .iter()
                .find(|t| t.role == role)
                .and_then(|t| t.heads.last())
                .map_or([0; 32], |h| h.head)
        };
        let ended_at = traces
            .iter()
            .find(|t| t.role == Role::Driver)
            .or(traces.first())
            .and_then(|t| t.fixes.last())
            .map_or(started_at, |f| f.t_ms / 1000);
        let draft = TripReceipt {
            ride_id,
            quote_hash: route_quote.hash(&domain),
            rider_head: head(Role::Rider),
            driver_head: head(Role::Driver),
            driven_path: Vec::new(),
            driven_distance_m: 0,
            started_at,
            ended_at,
            fare: route_quote.fare,
        };
        let measure = |path: &[Step], witness: &klippelgo_verify::Witness| {
            let candidate = TripReceipt {
                driven_path: path.to_vec(),
                ..draft.clone()
            };
            receipt::measure(
                &candidate,
                &route_quote,
                &traces,
                witness,
                &self.pack.pack(),
                &domain,
                &Params::default(),
            )
        };
        // Most trips drive the quoted route, and that path is already known to be sound: try it first. Only a
        // trip that left it needs the map-matched path, with its invented detours dropped.
        let quoted = self.pack.witness(route_quote.path.iter().map(|s| s.way))?;
        let (driven_path, report) = match measure(&route_quote.path, &quoted) {
            Ok(report) => (route_quote.path.clone(), report),
            Err(_) => {
                let matched = valhalla::steps(&valhalla::parse_edges(&matched_json)?, |way| {
                    self.pack.way(way)
                })?;
                let witness = self.pack.witness(matched.iter().map(|s| s.way))?;
                valhalla::without_false_spurs(matched, |path| measure(path, &witness))?
            }
        };
        let trip = TripReceipt {
            driven_path,
            driven_distance_m: report.driven_distance_m,
            ..draft
        };
        evidence.receipt = Some(ReceiptFile::from(&trip));
        Ok(evidence.to_json())
    }

    /// Checks a whole ride: quote, receipt and logs. This is what a phone runs before signing the receipt the
    /// other one drafted, and what a juror's tool runs on revealed evidence.
    pub fn check_trip(&self, evidence_json: String) -> Result<TripSummary, MapError> {
        let evidence = Evidence::parse(&evidence_json)?;
        let domain = evidence.domain();
        let route_quote = RouteQuote::try_from(&evidence.quote)?;
        let file = evidence
            .receipt
            .as_ref()
            .ok_or_else(|| MapError::Invalid("the evidence has no receipt".into()))?;
        let trip = TripReceipt::try_from(file)?;
        let traces = evidence
            .traces
            .iter()
            .map(Trace::try_from)
            .collect::<Result<Vec<_>, _>>()?;
        let ways = route_quote
            .path
            .iter()
            .chain(&trip.driven_path)
            .map(|s| s.way);
        let witness = self.pack.witness(ways)?;
        quote::check(
            &route_quote,
            &witness,
            &self.pack.pack(),
            &Params::default(),
        )?;
        let report = receipt::check(
            &trip,
            &route_quote,
            &traces,
            &witness,
            &self.pack.pack(),
            &domain,
            &Params::default(),
        )?;
        Ok(TripSummary {
            receipt_hash: trip.hash(&domain).to_vec(),
            driven_distance_m: report.driven_distance_m,
            quoted_distance_m: report.quoted_distance_m,
            duration_s: report.duration_s,
            pickup_gap_m: report.pickup_gap_m,
            dropoff_gap_m: report.dropoff_gap_m,
            reached_dropoff: report.reached_dropoff,
            logs: report.traces,
        })
    }
}

impl MapPack {
    fn with_search<T>(
        &self,
        run: impl FnOnce(&Search) -> Result<T, klippelgo_proofs::search::Error>,
    ) -> Result<T, MapError> {
        let mut slot = self.search.lock().unwrap();
        if slot.is_none() {
            *slot = Some(
                Search::open(&self.pack.file(SEARCH_FILE))
                    .map_err(|e| MapError::Search(e.to_string()))?,
            );
        }
        run(slot.as_ref().unwrap()).map_err(|e| MapError::Search(e.to_string()))
    }
}

fn place(hit: klippelgo_proofs::search::Hit) -> Place {
    Place {
        kind: match hit.kind {
            Kind::Address => PlaceKind::Address,
            Kind::Street => PlaceKind::Street,
            Kind::Place => PlaceKind::Neighbourhood,
            Kind::Poi => PlaceKind::PointOfInterest,
        },
        name: hit.name,
        context: hit.context,
        at: hit.at.into(),
        distance_m: hit.distance_m,
    }
}

fn degrees(e7: i32) -> f64 {
    e7 as f64 / 1e7
}

/// The attributes the path translation needs from Valhalla, and nothing else.
fn filters() -> serde_json::Value {
    serde_json::json!({
        "attributes": ["edge.way_id", "edge.begin_shape_index", "edge.end_shape_index", "shape"],
        "action": "include",
    })
}

/// The `route` request for Valhalla: the fastest way from one point to another for a vehicle.
#[uniffi::export]
pub fn route_request(from: Coordinate, to: Coordinate, vehicle: VehicleKind) -> String {
    serde_json::json!({
        "locations": [
            {"lat": degrees(from.lat_e7), "lon": degrees(from.lon_e7)},
            {"lat": degrees(to.lat_e7), "lon": degrees(to.lon_e7)},
        ],
        "costing": vehicle.costing(),
        "units": "kilometers",
        "directions_type": "none",
    })
    .to_string()
}

/// The `trace_attributes` request that makes Valhalla name the OpenStreetMap ways along a route it returned.
#[uniffi::export]
pub fn attributes_request(route_json: String, vehicle: VehicleKind) -> Result<String, MapError> {
    let route = valhalla::parse_route(&route_json)?;
    Ok(serde_json::json!({
        "encoded_polyline": route.shape,
        "shape_match": "walk_or_snap",
        "costing": vehicle.costing(),
        "filters": filters(),
    })
    .to_string())
}

/// The `trace_attributes` request that map-matches a GPS log onto the road network.
#[uniffi::export]
pub fn match_request(fixes: Vec<GpsFix>, vehicle: VehicleKind) -> String {
    let shape: Vec<_> = fixes
        .iter()
        .map(|f| serde_json::json!({"lat": degrees(f.at.lat_e7), "lon": degrees(f.at.lon_e7), "time": f.t_ms as f64 / 1000.0}))
        .collect();
    serde_json::json!({
        "shape": shape,
        "shape_match": "map_snap",
        "costing": vehicle.costing(),
        "trace_options": {"gps_accuracy": 10, "search_radius": 40},
        "filters": filters(),
    })
    .to_string()
}

/// What a gossip request says about a route: its distance to the nearest 500 m and its duration to the nearest
/// minute, each at least one step. Coarse on purpose, so the request does not single out a pair of addresses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct CoarseRoute {
    pub distance_m: u32,
    pub duration_s: u32,
}

#[uniffi::export]
pub fn coarse_route(distance_m: u32, duration_s: u32) -> CoarseRoute {
    let round = |value: u32, step: u32| ((value + step / 2) / step).max(1) * step;
    CoarseRoute {
        distance_m: round(distance_m, 500),
        duration_s: round(duration_s, 60),
    }
}

/// The quote for a measured route and an accepted offer, as the `quote` part of an evidence file. The rider's
/// phone keeps it and, once the encrypted ride channel exists, sends it to the driver to check.
#[uniffi::export]
pub fn quote_json(
    pack_id: Vec<u8>,
    route: MeasuredRoute,
    salt: Vec<u8>,
    terms: OfferTerms,
) -> Result<String, MapError> {
    let route_quote = RouteQuote {
        pack_id: hash(&pack_id)?,
        pickup: route.pickup.into(),
        dropoff: route.dropoff.into(),
        vehicle: route.vehicle.vehicle(),
        path: steps(&route.path),
        distance_m: route.distance_m,
        duration_s: route.duration_s,
        salt: hash(&salt)?,
        fare: fare_bytes(&terms.fare)
            .ok_or_else(|| MapError::Invalid("the fare must be a decimal number".into()))?,
        token: address(&terms.token)?,
        rider: address(&terms.rider)?,
        driver: address(&terms.driver)?,
        expiry: terms.expiry,
        previous: [0; 32],
    };
    Ok(serde_json::to_string(&QuoteFile::from(&route_quote)).expect("a quote always serialises"))
}

/// The rider's commitment to a route, for the gossip request. With a random 32-byte `salt` it gives nothing
/// away about the places; the rider reveals the route and the salt to the driver they pick.
#[uniffi::export]
pub fn route_hash(
    pack_id: Vec<u8>,
    route: MeasuredRoute,
    salt: Vec<u8>,
) -> Result<Vec<u8>, MapError> {
    let route_quote = RouteQuote {
        pack_id: hash(&pack_id)?,
        pickup: route.pickup.into(),
        dropoff: route.dropoff.into(),
        vehicle: route.vehicle.vehicle(),
        path: steps(&route.path),
        distance_m: route.distance_m,
        duration_s: route.duration_s,
        salt: hash(&salt)?,
        fare: [0; 32],
        token: [0; 20],
        rider: [0; 20],
        driver: [0; 20],
        expiry: 0,
        previous: [0; 32],
    };
    Ok(route_quote.route_hash().to_vec())
}

/// The hash a driver signs when quoting and the rider locks in `RideEscrow.open`. It needs only the route's
/// hash, so a driver can quote before seeing the route.
#[uniffi::export]
pub fn quote_hash(
    escrow: EscrowRef,
    route_hash: Vec<u8>,
    terms: OfferTerms,
) -> Result<Vec<u8>, MapError> {
    let fare = fare_bytes(&terms.fare)
        .ok_or_else(|| MapError::Invalid("the fare must be a decimal number".into()))?;
    Ok(quote::quote_hash(
        &escrow.domain()?,
        &hash(&route_hash)?,
        &fare,
        &address(&terms.token)?,
        &address(&terms.rider)?,
        &address(&terms.driver)?,
        terms.expiry,
        &[0; 32],
    )
    .to_vec())
}

// ---------------------------------------------------------------- trip log

/// One GNSS reading.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct GpsFix {
    /// Unix milliseconds.
    pub t_ms: u64,
    pub at: Coordinate,
    /// The radius the platform reports for the fix, in centimetres.
    pub accuracy_cm: u32,
    pub speed_cm_s: u32,
    /// The platform flagged the location as coming from a mock provider.
    pub mock: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TripRole {
    Rider,
    Driver,
}

impl TripRole {
    fn role(self) -> Role {
        match self {
            TripRole::Rider => Role::Rider,
            TripRole::Driver => Role::Driver,
        }
    }
}

/// The ride a log is recorded for.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RideId {
    pub escrow: EscrowRef,
    pub ride_id: u64,
    pub quote_hash: Vec<u8>,
}

impl RideId {
    fn ride(&self) -> Result<RideRef, MapError> {
        Ok(RideRef {
            domain: self.escrow.domain()?,
            ride_id: self.ride_id,
            quote_hash: hash(&self.quote_hash)?,
        })
    }
}

fn trace_key(bytes: &[u8]) -> Result<[u8; 33], MapError> {
    bytes.try_into().map_err(|_| {
        MapError::Invalid("a trace key is a compressed P-256 point of 33 bytes".into())
    })
}

fn signature<const N: usize>(bytes: &[u8]) -> Result<[u8; N], MapError> {
    bytes
        .try_into()
        .map_err(|_| MapError::Invalid(format!("a signature of {N} bytes was expected")))
}

/// A head of the log, ready for the phone's hardware key.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HeadToSign {
    /// How many fixes the head covers.
    pub fixes: u32,
    pub head: Vec<u8>,
    /// What the trace key signs (ECDSA P-256 over the SHA-256 of these 32 bytes), and what the other phone's
    /// trace key countersigns.
    pub digest: Vec<u8>,
}

/// What a wallet signs, as an Ethereum signed message, to certify a trace key for one ride. The key is a
/// P-256 key made in the Secure Enclave or StrongBox for this ride.
#[uniffi::export]
pub fn trace_key_digest(ride: RideId, role: TripRole, key: Vec<u8>) -> Result<Vec<u8>, MapError> {
    Ok(trace::key_digest(&ride.ride()?, role.role(), &trace_key(&key)?).to_vec())
}

/// Whether a head the other phone sent for countersigning carries their trace key's signature. Check their
/// key's certificate once, at ride start, before trusting the key.
#[uniffi::export]
pub fn peer_head_is_signed(
    peer_key: Vec<u8>,
    fixes: u32,
    head: Vec<u8>,
    signature_rs: Vec<u8>,
) -> Result<bool, MapError> {
    Ok(trace::peer_head_is_signed(
        &trace_key(&peer_key)?,
        fixes,
        &hash(&head)?,
        &signature(&signature_rs)?,
    ))
}

/// The digest to countersign for a head the other phone sent.
#[uniffi::export]
pub fn head_digest(fixes: u32, head: Vec<u8>) -> Result<Vec<u8>, MapError> {
    Ok(trace::head_digest(fixes, &hash(&head)?).to_vec())
}

/// This phone's GPS log for one ride. Push a fix every 2 seconds; every 30 seconds ask [`TripLog::to_sign`],
/// sign the digest with the trace key, hand the signature back, and send the head to the other phone to
/// countersign.
#[derive(uniffi::Object)]
pub struct TripLog {
    log: Mutex<Log>,
}

#[uniffi::export]
impl TripLog {
    #[uniffi::constructor]
    pub fn new(ride: RideId, role: TripRole, key: Vec<u8>) -> Result<Arc<Self>, MapError> {
        Ok(Arc::new(TripLog {
            log: Mutex::new(Log::new(&ride.ride()?, role.role(), trace_key(&key)?)),
        }))
    }

    /// Appends a fix. Returns false for a fix that is not later than the last one.
    pub fn push(&self, fix: GpsFix) -> bool {
        self.log.lock().unwrap().push(Fix {
            t_ms: fix.t_ms,
            at: fix.at.into(),
            accuracy_cm: fix.accuracy_cm,
            speed_cm_s: fix.speed_cm_s,
            mock: fix.mock,
        })
    }

    pub fn fixes(&self) -> Vec<GpsFix> {
        let log = self.log.lock().unwrap();
        log.fixes()
            .iter()
            .map(|f| GpsFix {
                t_ms: f.t_ms,
                at: f.at.into(),
                accuracy_cm: f.accuracy_cm,
                speed_cm_s: f.speed_cm_s,
                mock: f.mock,
            })
            .collect()
    }

    /// The head to sign now, or nothing when no fix arrived since the last signed head.
    pub fn to_sign(&self) -> Option<HeadToSign> {
        let log = self.log.lock().unwrap();
        log.to_sign().map(|(fixes, digest)| HeadToSign {
            fixes,
            head: log.head().to_vec(),
            digest: digest.to_vec(),
        })
    }

    /// Stores this phone's signature (r ‖ s, 64 bytes) over the head [`TripLog::to_sign`] returned. Returns
    /// false when a fix arrived in between: ask again.
    pub fn signed(&self, fixes: u32, signature_rs: Vec<u8>) -> Result<bool, MapError> {
        Ok(self
            .log
            .lock()
            .unwrap()
            .signed(fixes, signature(&signature_rs)?))
    }

    /// Stores the other phone's signature over one of this log's signed heads.
    pub fn countersigned(&self, fixes: u32, signature_rs: Vec<u8>) -> Result<bool, MapError> {
        Ok(self
            .log
            .lock()
            .unwrap()
            .countersigned(fixes, signature(&signature_rs)?))
    }

    /// The log as a trace for an evidence file, with the certificates exchanged at ride start: this wallet's
    /// signature over [`trace_key_digest`], and the other phone's trace key with its wallet's.
    pub fn export(
        &self,
        certificate: Vec<u8>,
        peer_key: Vec<u8>,
        peer_certificate: Vec<u8>,
    ) -> Result<String, MapError> {
        let log = self.log.lock().unwrap().clone();
        let finished = log.finish(
            signature(&certificate)?,
            trace_key(&peer_key)?,
            signature(&peer_certificate)?,
        );
        Ok(serde_json::to_string(&TraceFile::from(&finished)).expect("a trace always serialises"))
    }
}
