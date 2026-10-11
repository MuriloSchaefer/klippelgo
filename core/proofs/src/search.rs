//! Address and place search inside a map pack, with no geocoding service: one SQLite file with a full-text
//! index (FTS5, accents folded so "sao joao" finds "São João") and a spatial index (R*Tree) for "what is here".
//!
//! [`build`] writes the file when a pack is made; [`Search`] reads it on the phone.

use klippelgo_verify::geo::distance_mm;
use klippelgo_verify::Point;
use rusqlite::{params, Connection, OpenFlags};
use std::collections::{BTreeMap, HashMap};
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Kind {
    /// A street with a house number.
    Address = 0,
    Street = 1,
    /// A city, town or neighbourhood.
    Place = 2,
    /// A named shop, station, hospital, square and so on.
    Poi = 3,
}

/// Something with a name at one point.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Feature {
    pub kind: Kind,
    pub name: String,
    /// What tells it apart from others of the same name: a neighbourhood, a city, an address.
    pub context: String,
    pub at: Point,
}

/// One named OpenStreetMap way. Ways with the same name that touch become one street.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Street {
    pub name: String,
    pub nodes: Vec<(u64, Point)>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Counts {
    pub streets: usize,
    pub addresses: usize,
    pub places: usize,
    pub pois: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hit {
    pub kind: Kind,
    pub name: String,
    pub context: String,
    pub at: Point,
    /// From the point the search was centred on, when there was one.
    pub distance_m: Option<u32>,
}

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("search database: {0}")]
    Sqlite(#[from] rusqlite::Error),
}

const SCHEMA: &str = "
    PRAGMA journal_mode = OFF;
    PRAGMA synchronous = OFF;
    CREATE TABLE place (
        id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL, context TEXT NOT NULL,
        lat INTEGER NOT NULL, lon INTEGER NOT NULL);
    CREATE VIRTUAL TABLE place_text USING fts5(
        name, context, content = 'place', content_rowid = 'id',
        tokenize = 'unicode61 remove_diacritics 2', prefix = '2 3 4');
    CREATE VIRTUAL TABLE place_box USING rtree_i32(id, min_lat, max_lat, min_lon, max_lon, +place INTEGER);
";

/// How far around a street to look for the neighbourhood it is in, in 10⁻⁷ degrees (about 5 km).
const LOCALITY_REACH: i32 = 500_000;

/// Writes the search database, replacing any file at `path`.
pub fn build(
    path: &Path,
    streets: Vec<Street>,
    mut features: Vec<Feature>,
) -> Result<Counts, Error> {
    let _ = std::fs::remove_file(path);
    let mut db = Connection::open(path)?;
    db.execute_batch(SCHEMA)?;
    let tx = db.transaction()?;
    let mut counts = Counts::default();
    {
        let mut place = tx.prepare(
            "INSERT INTO place (id, kind, name, context, lat, lon) VALUES (?, ?, ?, ?, ?, ?)",
        )?;
        let mut text =
            tx.prepare("INSERT INTO place_text (rowid, name, context) VALUES (?, ?, ?)")?;
        let mut boxes = tx.prepare(
            "INSERT INTO place_box (id, min_lat, max_lat, min_lon, max_lon, place) VALUES (?, ?, ?, ?, ?, ?)",
        )?;
        let (mut next_place, mut next_box) = (1i64, 1i64);
        let mut add = |kind: Kind,
                       name: &str,
                       context: &str,
                       at: Point,
                       extents: &[[i32; 4]]|
         -> rusqlite::Result<()> {
            place.execute(params![
                next_place, kind as u8, name, context, at.lat, at.lon
            ])?;
            text.execute(params![next_place, name, context])?;
            for e in extents {
                boxes.execute(params![next_box, e[0], e[1], e[2], e[3], next_place])?;
                next_box += 1;
            }
            next_place += 1;
            Ok(())
        };

        features.sort_by(|a, b| {
            (a.kind as u8, &a.name, a.at.lat, a.at.lon).cmp(&(
                b.kind as u8,
                &b.name,
                b.at.lat,
                b.at.lon,
            ))
        });
        features.dedup();
        let localities = Localities::new(&features);
        for f in &features {
            add(
                f.kind,
                &f.name,
                &f.context,
                f.at,
                &[[f.at.lat, f.at.lat, f.at.lon, f.at.lon]],
            )?;
            match f.kind {
                Kind::Address => counts.addresses += 1,
                Kind::Place => counts.places += 1,
                Kind::Poi => counts.pois += 1,
                Kind::Street => {}
            }
        }
        for street in merge(streets) {
            let context = localities.nearest(street.at).unwrap_or_default();
            add(
                Kind::Street,
                &street.name,
                context,
                street.at,
                &street.extents,
            )?;
            counts.streets += 1;
        }
    }
    tx.execute(
        "INSERT INTO place_text (place_text) VALUES ('optimize')",
        [],
    )?;
    tx.commit()?;
    db.execute_batch("VACUUM;")?;
    Ok(counts)
}

struct Merged {
    name: String,
    at: Point,
    /// One box per way: min lat, max lat, min lon, max lon.
    extents: Vec<[i32; 4]>,
}

/// Joins the ways of each name that share a node into one street, placed at its node nearest the middle.
fn merge(streets: Vec<Street>) -> Vec<Merged> {
    let mut by_name: BTreeMap<String, Vec<Vec<(u64, Point)>>> = BTreeMap::new();
    for street in streets {
        by_name.entry(street.name).or_default().push(street.nodes);
    }
    let mut out = Vec::new();
    for (name, ways) in by_name {
        // Union-find over the ways, joined through shared node ids.
        let mut group: Vec<usize> = (0..ways.len()).collect();
        fn root(group: &mut [usize], mut i: usize) -> usize {
            while group[i] != i {
                group[i] = group[group[i]];
                i = group[i];
            }
            i
        }
        let mut seen: HashMap<u64, usize> = HashMap::new();
        for (w, nodes) in ways.iter().enumerate() {
            for (id, _) in nodes {
                if let Some(&other) = seen.get(id) {
                    let (a, b) = (root(&mut group, w), root(&mut group, other));
                    group[a.max(b)] = a.min(b);
                } else {
                    seen.insert(*id, w);
                }
            }
        }
        let mut members: BTreeMap<usize, Vec<usize>> = BTreeMap::new();
        for w in 0..ways.len() {
            members.entry(root(&mut group, w)).or_default().push(w);
        }
        for ways_in in members.into_values() {
            let points: Vec<Point> = ways_in
                .iter()
                .flat_map(|&w| ways[w].iter().map(|(_, p)| *p))
                .collect();
            let mean = |f: fn(&Point) -> i32| {
                (points.iter().map(|p| f(p) as i64).sum::<i64>() / points.len() as i64) as i32
            };
            let centre = Point {
                lat: mean(|p| p.lat),
                lon: mean(|p| p.lon),
            };
            let at = *points
                .iter()
                .min_by_key(|p| (distance_mm(**p, centre), p.lat, p.lon))
                .unwrap();
            let extents = ways_in
                .iter()
                .map(|&w| {
                    let fold = |f: fn(&Point) -> i32| {
                        ways[w]
                            .iter()
                            .map(|(_, p)| f(p))
                            .fold((i32::MAX, i32::MIN), |(lo, hi), v| (lo.min(v), hi.max(v)))
                    };
                    let ((s, n), (w, e)) = (fold(|p| p.lat), fold(|p| p.lon));
                    [s, n, w, e]
                })
                .collect();
            out.push(Merged {
                name: name.clone(),
                at,
                extents,
            });
        }
    }
    out
}

/// Neighbourhoods and towns on a coarse grid, to label streets with where they are.
struct Localities<'a> {
    cells: HashMap<(i32, i32), Vec<&'a Feature>>,
}

impl<'a> Localities<'a> {
    fn new(features: &'a [Feature]) -> Self {
        let mut cells: HashMap<(i32, i32), Vec<&Feature>> = HashMap::new();
        for f in features.iter().filter(|f| f.kind == Kind::Place) {
            cells.entry(Self::cell(f.at)).or_default().push(f);
        }
        Localities { cells }
    }

    fn cell(p: Point) -> (i32, i32) {
        (
            p.lat.div_euclid(LOCALITY_REACH),
            p.lon.div_euclid(LOCALITY_REACH),
        )
    }

    fn nearest(&self, at: Point) -> Option<&'a str> {
        let (row, column) = Self::cell(at);
        (-1..=1)
            .flat_map(|r| (-1..=1).map(move |c| (row + r, column + c)))
            .filter_map(|cell| self.cells.get(&cell))
            .flatten()
            .min_by_key(|f| (distance_mm(f.at, at), &f.name))
            .map(|f| f.name.as_str())
    }
}

/// Short forms riders type for the kind of street, in Portuguese.
const ABBREVIATIONS: [(&str, &str); 9] = [
    ("av", "avenida"),
    ("r", "rua"),
    ("al", "alameda"),
    ("pc", "praca"),
    ("pca", "praca"),
    ("rod", "rodovia"),
    ("tv", "travessa"),
    ("est", "estrada"),
    ("lgo", "largo"),
];

/// A map pack's search database, open for reading.
pub struct Search {
    db: Connection,
}

impl Search {
    pub fn open(path: &Path) -> Result<Self, Error> {
        let db = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        Ok(Search { db })
    }

    /// Finds places whose name or context has every word of `query`, the last one possibly unfinished. Results
    /// nearer to `near` come first among equally good matches.
    pub fn search(
        &self,
        query: &str,
        near: Option<Point>,
        limit: usize,
    ) -> Result<Vec<Hit>, Error> {
        let words: Vec<String> = query
            .split(|c: char| !c.is_alphanumeric())
            .filter(|w| !w.is_empty())
            .map(str::to_lowercase)
            .collect();
        if words.is_empty() {
            return Ok(Vec::new());
        }
        let expression = words
            .iter()
            .enumerate()
            .map(|(i, word)| {
                let full = ABBREVIATIONS
                    .iter()
                    .find(|(short, _)| i == 0 && short == word)
                    .map(|(_, full)| *full);
                match full {
                    Some(full) => format!("(\"{word}\"* OR \"{full}\")"),
                    None => format!("\"{word}\"*"),
                }
            })
            .collect::<Vec<_>>()
            .join(" AND ");
        let mut statement = self.db.prepare_cached(
            "SELECT p.kind, p.name, p.context, p.lat, p.lon, bm25(place_text, 4.0, 1.0)
             FROM place_text JOIN place p ON p.id = place_text.rowid
             WHERE place_text MATCH ?1 ORDER BY 6 LIMIT 200",
        )?;
        let mut scored = statement
            .query_map([expression], |row| {
                Ok((hit(row, near)?, row.get::<_, f64>(5)?))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        // bm25 is lower for better matches. Broader things first, then nearer ones: a kilometre costs about as
        // much as a slightly worse text match.
        let key = |(hit, score): &(Hit, f64)| {
            let breadth = match hit.kind {
                Kind::Place => 2.0,
                Kind::Street => 1.0,
                Kind::Poi => 0.5,
                Kind::Address => 0.0,
            };
            score - breadth
                + hit
                    .distance_m
                    .map_or(0.0, |m| (m as f64 / 1000.0).min(50.0) * 0.2)
        };
        scored.sort_by(|a, b| key(a).total_cmp(&key(b)));
        Ok(scored.into_iter().take(limit).map(|(hit, _)| hit).collect())
    }

    /// Names a point: the address within 30 m if there is one, otherwise the nearest street within 150 m.
    pub fn reverse(&self, at: Point) -> Result<Option<Hit>, Error> {
        // 150 m in degrees of latitude; longitude degrees are shorter, so the box is generous there.
        const REACH: i32 = 13_500;
        let mut statement = self.db.prepare_cached(
            "SELECT p.kind, p.name, p.context, p.lat, p.lon, b.min_lat, b.max_lat, b.min_lon, b.max_lon
             FROM place_box b JOIN place p ON p.id = b.place
             WHERE b.max_lat >= ?1 AND b.min_lat <= ?2 AND b.max_lon >= ?3 AND b.min_lon <= ?4 AND p.kind IN (0, 1)",
        )?;
        let rows = statement.query_map(
            params![
                at.lat - REACH,
                at.lat + REACH,
                at.lon - REACH * 2,
                at.lon + REACH * 2
            ],
            |row| {
                let clamp = |v: i32, lo: usize, hi: usize| {
                    Ok::<_, rusqlite::Error>(v.clamp(row.get(lo)?, row.get(hi)?))
                };
                let nearest = Point {
                    lat: clamp(at.lat, 5, 6)?,
                    lon: clamp(at.lon, 7, 8)?,
                };
                Ok((hit(row, None)?, (distance_mm(at, nearest) / 1000) as u32))
            },
        )?;
        let mut best: Option<(Hit, u32)> = None;
        for row in rows {
            let (hit, metres) = row?;
            let usable = metres <= if hit.kind == Kind::Address { 30 } else { 150 };
            let better = best.as_ref().is_none_or(|(b, d)| {
                (hit.kind == Kind::Address, std::cmp::Reverse(metres))
                    > (b.kind == Kind::Address, std::cmp::Reverse(*d))
            });
            if usable && better {
                best = Some((hit, metres));
            }
        }
        Ok(best.map(|(hit, metres)| Hit {
            distance_m: Some(metres),
            ..hit
        }))
    }
}

fn hit(row: &rusqlite::Row, near: Option<Point>) -> rusqlite::Result<Hit> {
    let at = Point {
        lat: row.get(3)?,
        lon: row.get(4)?,
    };
    Ok(Hit {
        kind: match row.get::<_, u8>(0)? {
            0 => Kind::Address,
            1 => Kind::Street,
            2 => Kind::Place,
            _ => Kind::Poi,
        },
        name: row.get(1)?,
        context: row.get(2)?,
        at,
        distance_m: near.map(|n| (distance_mm(n, at) / 1000).min(u32::MAX as u64) as u32),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(lat: i32, lon: i32) -> Point {
        Point { lat, lon }
    }

    fn database(name: &str) -> Search {
        let path = std::env::temp_dir().join(format!(
            "klippelgo-search-{name}-{}.sqlite",
            std::process::id()
        ));
        let feature = |kind, name: &str, context: &str, at| Feature {
            kind,
            name: name.into(),
            context: context.into(),
            at,
        };
        let street = |name: &str, nodes: &[(u64, Point)]| Street {
            name: name.into(),
            nodes: nodes.to_vec(),
        };
        let counts = build(
            &path,
            vec![
                // Avenida São João in two touching pieces, and another street of the same name far away.
                street(
                    "Avenida São João",
                    &[
                        (1, p(-235_400_000, -466_400_000)),
                        (2, p(-235_390_000, -466_420_000)),
                    ],
                ),
                street(
                    "Avenida São João",
                    &[
                        (2, p(-235_390_000, -466_420_000)),
                        (3, p(-235_380_000, -466_440_000)),
                    ],
                ),
                street(
                    "Avenida São João",
                    &[
                        (9, p(-236_600_000, -467_000_000)),
                        (10, p(-236_610_000, -467_010_000)),
                    ],
                ),
                street(
                    "Rua Augusta",
                    &[
                        (20, p(-235_550_000, -466_600_000)),
                        (21, p(-235_600_000, -466_650_000)),
                    ],
                ),
            ],
            vec![
                feature(Kind::Place, "República", "", p(-235_440_000, -466_420_000)),
                feature(
                    Kind::Place,
                    "Santo Amaro",
                    "",
                    p(-236_550_000, -467_050_000),
                ),
                feature(
                    Kind::Address,
                    "Avenida Paulista, 1578",
                    "Bela Vista, São Paulo",
                    p(-235_614_000, -466_559_000),
                ),
                feature(Kind::Poi, "Praça da Sé", "", p(-235_505_000, -466_333_000)),
            ],
        )
        .unwrap();
        assert_eq!(
            counts,
            Counts {
                streets: 3,
                addresses: 1,
                places: 2,
                pois: 1
            }
        );
        let search = Search::open(&path).unwrap();
        let _ = std::fs::remove_file(&path);
        search
    }

    #[test]
    fn finds_places_without_accents_and_from_unfinished_words() {
        let search = database("text");
        let names = |query: &str| {
            search
                .search(query, None, 10)
                .unwrap()
                .into_iter()
                .map(|h| h.name)
                .collect::<Vec<_>>()
        };
        assert_eq!(names("sao joao"), ["Avenida São João", "Avenida São João"]);
        assert_eq!(names("av sao jo"), ["Avenida São João", "Avenida São João"]);
        assert_eq!(names("praca da se"), ["Praça da Sé"]);
        assert_eq!(names("paulista 1578"), ["Avenida Paulista, 1578"]);
        assert_eq!(names("r augusta"), ["Rua Augusta"]);
        assert_eq!(names("bela vista"), ["Avenida Paulista, 1578"]);
        assert!(names("\"; DROP TABLE place").is_empty());
        assert!(names("   ").is_empty());
    }

    #[test]
    fn nearer_streets_of_the_same_name_come_first_with_their_neighbourhood() {
        let search = database("near");
        let downtown = search
            .search("avenida sao joao", Some(p(-235_500_000, -466_300_000)), 10)
            .unwrap();
        assert_eq!(downtown[0].context, "República");
        assert_eq!(downtown[0].at, p(-235_390_000, -466_420_000)); // the joined street's middle node
        assert!(downtown[0].distance_m.unwrap() < 2_000);
        let south = search
            .search("avenida sao joao", Some(p(-236_600_000, -467_000_000)), 10)
            .unwrap();
        assert_eq!(south[0].context, "Santo Amaro");
    }

    #[test]
    fn names_a_point_by_address_then_by_street() {
        let search = database("reverse");
        let at_door = search
            .reverse(p(-235_614_500, -466_559_500))
            .unwrap()
            .unwrap();
        assert_eq!(at_door.name, "Avenida Paulista, 1578");
        let on_street = search
            .reverse(p(-235_575_000, -466_625_000))
            .unwrap()
            .unwrap();
        assert_eq!(
            (on_street.kind, on_street.name.as_str()),
            (Kind::Street, "Rua Augusta")
        );
        assert_eq!(search.reverse(p(-230_000_000, -460_000_000)).unwrap(), None);
    }
}
