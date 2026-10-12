//! Reads an `.osm.pbf` once for ways and once for nodes, on one thread and in file order, so the same file
//! always yields the same data.

use crate::roads;
use klippelgo_proofs::search::{Feature, Kind, Street};
use klippelgo_verify::{Point, Way, WayNode};
use osmpbf::{Element, ElementReader};
use std::collections::{BTreeMap, HashMap};
use std::path::Path;

pub struct Extract {
    /// Drivable ways, sorted by id: the rows of the proof index.
    pub ways: Vec<Way>,
    /// Ways left out because the extract lacks one of their nodes (they cross its edge).
    pub incomplete: usize,
    pub streets: Vec<Street>,
    pub features: Vec<Feature>,
}

struct RawWay {
    id: u64,
    flags: u8,
    name: Option<String>,
    refs: Vec<i64>,
}

struct RawArea {
    refs: Vec<i64>,
    features: Vec<Feature>,
}

pub fn read(pbf: &Path, with_search: bool) -> Result<Extract, osmpbf::Error> {
    let mut roads: Vec<RawWay> = Vec::new();
    let mut areas: Vec<RawArea> = Vec::new();
    let mut coords: HashMap<i64, Option<Point>> = HashMap::new();

    ElementReader::from_path(pbf)?.for_each(|element| {
        let Element::Way(way) = element else { return };
        let tags: BTreeMap<&str, &str> = way.tags().collect();
        let tag = |key: &str| tags.get(key).copied();
        let flags = roads::flags(tag);
        if flags != 0 && way.id() > 0 {
            let refs: Vec<i64> = way.refs().collect();
            if refs.len() >= 2 && refs.len() <= u16::MAX as usize {
                coords.extend(refs.iter().map(|r| (*r, None)));
                roads.push(RawWay {
                    id: way.id() as u64,
                    flags,
                    name: tag("name").filter(|_| with_search).map(str::to_string),
                    refs,
                });
            }
        } else if with_search && !roads::is_road(tag("highway")) {
            let features = features(tag, Point { lat: 0, lon: 0 });
            if !features.is_empty() {
                let refs: Vec<i64> = way.refs().collect();
                coords.extend(refs.iter().map(|r| (*r, None)));
                areas.push(RawArea { refs, features });
            }
        }
    })?;

    let mut point_features = Vec::new();
    ElementReader::from_path(pbf)?.for_each(|element| {
        let (id, at, tags): (i64, Point, Vec<(&str, &str)>) = match &element {
            Element::Node(n) => (
                n.id(),
                point(n.decimicro_lat(), n.decimicro_lon()),
                n.tags().collect(),
            ),
            Element::DenseNode(n) => (
                n.id(),
                point(n.decimicro_lat(), n.decimicro_lon()),
                n.tags().collect(),
            ),
            _ => return,
        };
        if let Some(slot) = coords.get_mut(&id) {
            *slot = Some(at);
        }
        if with_search && !tags.is_empty() {
            let tags: BTreeMap<&str, &str> = tags.into_iter().collect();
            point_features.extend(features(|key| tags.get(key).copied(), at));
        }
    })?;
    let mut extract = Extract {
        ways: Vec::with_capacity(roads.len()),
        incomplete: 0,
        streets: Vec::new(),
        features: point_features,
    };
    roads.sort_by_key(|w| w.id);
    roads.dedup_by_key(|w| w.id);
    for road in roads {
        let nodes: Option<Vec<WayNode>> = road
            .refs
            .iter()
            .map(|r| {
                Some(WayNode {
                    id: u64::try_from(*r).ok()?,
                    at: (*coords.get(r)?)?,
                })
            })
            .collect();
        let Some(nodes) = nodes else {
            extract.incomplete += 1;
            continue;
        };
        if let Some(name) = road.name {
            extract.streets.push(Street {
                name,
                nodes: nodes.iter().map(|n| (n.id, n.at)).collect(),
            });
        }
        extract.ways.push(Way {
            id: road.id,
            flags: road.flags,
            nodes,
        });
    }
    for area in areas {
        let points: Vec<Point> = area
            .refs
            .iter()
            .filter_map(|r| coords.get(r).copied().flatten())
            .collect();
        if points.is_empty() {
            continue;
        }
        // The middle of the outline's bounding box: good enough to drop a pin on a building.
        let mid = |f: fn(&Point) -> i32| {
            let (lo, hi) = points
                .iter()
                .map(f)
                .fold((i32::MAX, i32::MIN), |(lo, hi), v| (lo.min(v), hi.max(v)));
            ((lo as i64 + hi as i64) / 2) as i32
        };
        let at = Point {
            lat: mid(|p| p.lat),
            lon: mid(|p| p.lon),
        };
        extract
            .features
            .extend(area.features.into_iter().map(|f| Feature { at, ..f }));
    }
    Ok(extract)
}

fn point(lat: i32, lon: i32) -> Point {
    Point { lat, lon }
}

/// What a node or an outline contributes to search: a place name, an address, a named point of interest.
fn features<'a>(tag: impl Fn(&str) -> Option<&'a str>, at: Point) -> Vec<Feature> {
    let mut out = Vec::new();
    let locality = [tag("addr:suburb"), tag("addr:city")]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(", ");
    let address = match (tag("addr:street"), tag("addr:housenumber")) {
        (Some(street), Some(number)) => Some(format!("{street}, {number}")),
        _ => None,
    };
    if let Some(name) = tag("name") {
        if matches!(
            tag("place"),
            Some("city" | "town" | "village" | "hamlet" | "suburb" | "quarter" | "neighbourhood")
        ) {
            out.push(Feature {
                kind: Kind::Place,
                name: name.into(),
                context: tag("is_in").unwrap_or("").into(),
                at,
            });
        } else if POI_KEYS
            .iter()
            .any(|key| tag(key).is_some_and(|v| v != "no"))
        {
            let context = match &address {
                Some(address) if locality.is_empty() => address.clone(),
                Some(address) => format!("{address}, {locality}"),
                None => locality.clone(),
            };
            out.push(Feature {
                kind: Kind::Poi,
                name: name.into(),
                context,
                at,
            });
        }
    }
    if let Some(address) = address {
        out.push(Feature {
            kind: Kind::Address,
            name: address,
            context: locality,
            at,
        });
    }
    out
}

const POI_KEYS: [&str; 12] = [
    "amenity",
    "shop",
    "tourism",
    "leisure",
    "office",
    "healthcare",
    "railway",
    "public_transport",
    "aeroway",
    "historic",
    "craft",
    "building",
];
