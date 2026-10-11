//! Turns what Valhalla returns into the `(way, node)` steps the verifier checks.
//!
//! Valhalla describes a route or a map-matched trace as edges, each with the id of the OpenStreetMap way it
//! lies on and a shape rounded to six decimals. The verifier wants the exact nodes passed. For every edge this
//! module finds the run of the way's nodes that the shape follows, in the map pack's own proof index, and
//! strings the runs together. The first and last points of a trip are usually mid-block, where Valhalla snapped
//! the pickup and drop-off onto the road; there the run is widened to the whole road segment, and the verifier
//! trims it back when it measures.
//!
//! Nothing here is trusted. A wrong translation yields a path the verifier rejects.

use crate::Error;
use klippelgo_verify::geo::project;
use klippelgo_verify::{Point, Step, Way};
use serde_json::Value;

/// How far a shape point may be from the node it stands for, in 10⁻⁷ degrees. Six-decimal rounding moves a
/// point by at most 5.
const NEAR: i32 = 12;

/// How many nodes back from the end of an edge the edge after a gap may take over.
const GAP_REACH: usize = 16;

/// How many of a way's nodes one shape point may skip. Valhalla drops repeated points.
const MAX_SKIP: usize = 8;

/// One edge of Valhalla's answer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Edge {
    pub way: u64,
    pub shape: Vec<Point>,
    /// The shape does not continue from the edge before: map-matching lost the thread between two fixes and
    /// picked it up again, here, possibly mid-block. Always false for a route.
    pub gap_before: bool,
}

/// The parts of a `route` response the app uses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Route {
    /// Valhalla's own figures. The quoted distance comes from the verifier, not from here.
    pub length_m: u32,
    pub time_s: u32,
    /// The route's shape, exactly as Valhalla encoded it, to hand back for `trace_attributes`.
    pub shape: String,
    pub points: Vec<Point>,
}

/// Reads a `route` response with one leg.
pub fn parse_route(json: &str) -> Result<Route, Error> {
    let root = parse(json)?;
    let leg = root["trip"]["legs"]
        .get(0)
        .ok_or_else(|| bad("no route leg"))?;
    let shape = leg["shape"].as_str().ok_or_else(|| bad("no shape"))?;
    let summary = &leg["summary"];
    let length = summary["length"].as_f64().ok_or_else(|| bad("no length"))?;
    let per_unit = match root["trip"]["units"].as_str() {
        Some("miles") => 1609.344,
        _ => 1000.0,
    };
    Ok(Route {
        length_m: (length * per_unit).round() as u32,
        time_s: summary["time"]
            .as_f64()
            .ok_or_else(|| bad("no time"))?
            .round() as u32,
        shape: shape.to_string(),
        points: decode_polyline6(shape)?,
    })
}

/// Reads a `trace_attributes` response into its edges. The request must ask for `edge.way_id`,
/// `edge.begin_shape_index`, `edge.end_shape_index` and `shape`.
pub fn parse_edges(json: &str) -> Result<Vec<Edge>, Error> {
    let root = parse(json)?;
    let shape = decode_polyline6(root["shape"].as_str().ok_or_else(|| bad("no shape"))?)?;
    let edges = root["edges"].as_array().ok_or_else(|| bad("no edges"))?;
    let mut out: Vec<Edge> = Vec::with_capacity(edges.len());
    let mut previous_end = None;
    for edge in edges {
        let field = |name: &str| {
            edge[name]
                .as_u64()
                .ok_or_else(|| bad(&format!("an edge has no {name}")))
        };
        let (begin, end) = (
            field("begin_shape_index")? as usize,
            field("end_shape_index")? as usize,
        );
        let points = shape
            .get(begin..=end)
            .ok_or_else(|| bad("an edge points outside the shape"))?;
        out.push(Edge {
            way: field("way_id")?,
            shape: points.to_vec(),
            gap_before: previous_end.is_some_and(|previous| previous != begin),
        });
        previous_end = Some(end);
    }
    Ok(out)
}

/// Parses an answer, turning Valhalla's own error reports into errors: `{"error": ...}` from its HTTP service
/// and `{"code": ..., "message": ...}` from the phone libraries.
fn parse(json: &str) -> Result<Value, Error> {
    let root: Value = serde_json::from_str(json).map_err(|e| bad(&e.to_string()))?;
    let failure = root
        .get("error")
        .or_else(|| root.get("message").filter(|_| root.get("code").is_some()));
    match failure.and_then(Value::as_str) {
        Some(error) => Err(bad(error)),
        None => Ok(root),
    }
}

fn bad(what: &str) -> Error {
    Error::Valhalla(what.to_string())
}

/// Decodes an encoded polyline with six decimals into points in degrees × 10⁷.
pub fn decode_polyline6(text: &str) -> Result<Vec<Point>, Error> {
    let mut points = Vec::new();
    let (mut lat, mut lon) = (0i64, 0i64);
    let mut bytes = text.bytes();
    let next = |bytes: &mut std::str::Bytes| -> Result<Option<i64>, Error> {
        let (mut value, mut shift) = (0i64, 0u32);
        loop {
            let Some(byte) = bytes.next() else {
                return if shift == 0 {
                    Ok(None)
                } else {
                    Err(bad("the shape is cut short"))
                };
            };
            let chunk = (byte as i64) - 63;
            if !(0..64).contains(&chunk) || shift > 35 {
                return Err(bad("the shape is not a polyline"));
            }
            value |= (chunk & 0x1f) << shift;
            shift += 5;
            if chunk < 0x20 {
                return Ok(Some(if value & 1 == 1 {
                    !(value >> 1)
                } else {
                    value >> 1
                }));
            }
        }
    };
    while let Some(dlat) = next(&mut bytes)? {
        let dlon = next(&mut bytes)?.ok_or_else(|| bad("the shape is cut short"))?;
        lat += dlat;
        lon += dlon;
        let e7 = |v: i64| i32::try_from(v * 10).map_err(|_| bad("the shape leaves the globe"));
        points.push(Point {
            lat: e7(lat)?,
            lon: e7(lon)?,
        });
    }
    Ok(points)
}

/// Encodes points with six decimals, for a `trace_attributes` request built from GPS fixes.
pub fn encode_polyline6(points: &[Point]) -> String {
    let mut out = String::new();
    let (mut lat, mut lon) = (0i64, 0i64);
    let mut put = |delta: i64| {
        let mut value = if delta < 0 { !(delta << 1) } else { delta << 1 };
        while value >= 0x20 {
            out.push((((value & 0x1f) | 0x20) as u8 + 63) as char);
            value >>= 5;
        }
        out.push((value as u8 + 63) as char);
    };
    for p in points {
        let round = |v: i32| (v as i64 + if v < 0 { -5 } else { 5 }) / 10;
        let (la, lo) = (round(p.lat), round(p.lon));
        put(la - lat);
        put(lo - lon);
        (lat, lon) = (la, lo);
    }
    out
}

/// Finds the steps along the map pack's ways that Valhalla's edges follow. `way` looks a way up in the pack.
///
/// An edge's shape can fit its way in more than one way: two nodes a few centimetres apart round to the same
/// point, and a closed way passes its first node twice. So every fit of every edge is kept, and the fits are
/// chained so that each edge starts at the node the one before it ended on.
pub fn steps(
    edges: &[Edge],
    mut way: impl FnMut(u64) -> Result<Option<Way>, Error>,
) -> Result<Vec<Step>, Error> {
    // For each edge, its fits as node ids, with the cost of the best chain ending in that fit and the fit of
    // the previous edge it continues. The cost is the total misfit, then the number of nodes: of two chains
    // that fit equally well, the one that does not double back between two nodes at the same spot wins.
    type Fit = (Vec<u64>, (u64, usize), usize);
    let mut layers: Vec<Vec<Fit>> = Vec::with_capacity(edges.len());
    for (i, edge) in edges.iter().enumerate() {
        let on = way(edge.way)?.ok_or(Error::UnknownWay(edge.way))?;
        let mut layer = Vec::new();
        // An end of the shape is a snapped point, not a node, at the ends of the trip and on either side of a gap.
        let open_start = i == 0 || edge.gap_before;
        let open_end = edges.get(i + 1).is_none_or(|next| next.gap_before);
        for (misfit, run) in fits(&on, &edge.shape, open_start, open_end) {
            let nodes: Vec<u64> = run.iter().map(|&n| on.nodes[n].id).collect();
            let before = match layers.last() {
                None => Some(((0, 0), 0)),
                Some(previous) => previous
                    .iter()
                    .enumerate()
                    .filter(|(_, (ids, ..))| joint(ids, &nodes, edge.gap_before).is_some())
                    .map(|(at, (_, total, _))| (*total, at))
                    .min(),
            };
            if let Some((total, at)) = before {
                let cost = (total.0 + misfit, total.1 + nodes.len());
                layer.push((nodes, cost, at));
            }
        }
        if layer.is_empty() {
            return Err(Error::OffTheMap {
                edge: i,
                way: edge.way,
            });
        }
        layers.push(layer);
    }

    let Some(last) = layers.last() else {
        return Ok(Vec::new());
    };
    let mut at = (0..last.len()).min_by_key(|&c| last[c].1).unwrap();
    let mut chain = Vec::with_capacity(layers.len());
    for layer in layers.iter().rev() {
        chain.push(&layer[at].0);
        at = layer[at].2;
    }
    let mut steps: Vec<Step> = Vec::new();
    // Where the previous edge's nodes begin in `steps`, and those nodes.
    let mut previous: Option<(usize, &Vec<u64>)> = None;
    for (edge, nodes) in edges.iter().zip(chain.into_iter().rev()) {
        if let Some((begins, before)) = previous {
            // Keep the previous edge up to the node this one starts on, which both list.
            let at =
                joint(before, nodes, edge.gap_before).expect("the chain was built from joints");
            steps.truncate(begins + at + 1);
        }
        let begins = steps.len().saturating_sub(1);
        let skip = usize::from(!steps.is_empty());
        steps.extend(nodes[skip..].iter().map(|&node| Step {
            way: edge.way,
            node,
        }));
        previous = Some((begins, nodes));
    }
    Ok(steps)
}

/// Where in `before` the nodes of the next edge take over: the index of the node both share.
///
/// Normally that is the last node of `before`. Across a gap both edges were widened to whole road segments
/// around the points where the thread was lost and found, so they overlap, and the next edge may even start a
/// little behind where the last one ended, when GPS noise made the vehicle seem to back up. Then the next edge
/// takes over from the latest node of `before` from which the two agree.
fn joint(before: &[u64], next: &[u64], gap: bool) -> Option<usize> {
    if !gap {
        return (before.last() == next.first()).then(|| before.len() - 1);
    }
    (before.len().saturating_sub(GAP_REACH)..before.len())
        .rev()
        .find(|&at| before[at..].iter().zip(next).all(|(a, b)| a == b))
}

/// Drops out-and-back spurs from a map-matched path until `holds` accepts it, and returns what `holds` returned.
///
/// Map-matching a noisy log sometimes sends the path a few metres up a side street and straight back. Nobody
/// drove that, and the verifier rightly refuses a receipt that claims it. A real dead-end detour looks the same
/// in the path, so spurs are not dropped blindly: one is removed only while the verifier still objects, and
/// only the one whose removal lets the log be followed furthest. `holds` is the verifier's check of a candidate
/// path against the log, and its error names the first fix that does not fit.
pub fn without_false_spurs<T>(
    mut path: Vec<Step>,
    mut holds: impl FnMut(&[Step]) -> Result<T, klippelgo_verify::Error>,
) -> Result<(Vec<Step>, T), klippelgo_verify::Error> {
    use klippelgo_verify::Error as Refusal;
    let reached = |refusal: &Refusal| match refusal {
        Refusal::OffPath { fix } | Refusal::TooFast { fix } => Some(*fix),
        _ => None,
    };
    loop {
        let refusal = match holds(&path) {
            Ok(found) => return Ok((path, found)),
            Err(refusal) => refusal,
        };
        let Some(stuck_at) = reached(&refusal) else {
            return Err(refusal);
        };
        // Try the path without each spur in turn and keep the removal that gets furthest.
        let mut best: Option<(usize, Vec<Step>)> = None;
        for (from, to) in spurs(&path) {
            let mut shorter = path[..=from].to_vec();
            shorter.extend_from_slice(&path[to + 1..]);
            let gets_to = match holds(&shorter) {
                Ok(found) => return Ok((shorter, found)),
                Err(refusal) => reached(&refusal),
            };
            if let Some(fix) = gets_to.filter(|fix| *fix > stuck_at) {
                if best.as_ref().is_none_or(|(furthest, _)| fix > *furthest) {
                    best = Some((fix, shorter));
                }
            }
        }
        match best {
            Some((_, shorter)) => path = shorter,
            None => return Err(refusal),
        }
    }
}

/// Stretches where the path turns round on a node and retraces its own steps: `(from, to)` such that the nodes
/// after `from` up to `to` go out and come back to the node at `from`.
fn spurs(path: &[Step]) -> Vec<(usize, usize)> {
    let mut found = Vec::new();
    let mut pivot = 1;
    while pivot + 1 < path.len() {
        let mut reach = 0;
        while pivot > reach
            && pivot + reach + 1 < path.len()
            && path[pivot - reach - 1].node == path[pivot + reach + 1].node
        {
            reach += 1;
        }
        if reach > 0 {
            found.push((pivot - reach, pivot + reach));
            pivot += reach;
        }
        pivot += 1;
    }
    found
}

/// Every way `shape` can run along `way`, as indices of the way's nodes with a misfit (how far the snapped
/// ends are from the segments chosen for them, in millimetres). Empty when the shape does not follow the way.
/// When `open_start` or `open_end` is set, that end of the shape is a snapped point between two nodes, and the
/// run reaches out to the node beyond it.
fn fits(way: &Way, shape: &[Point], open_start: bool, open_end: bool) -> Vec<(u64, Vec<usize>)> {
    let nodes = &way.nodes;
    let closed = nodes.len() > 2 && nodes[0].id == nodes[nodes.len() - 1].id;
    // On a closed way the last node is the first again, so stepping wraps around past it.
    let step = |i: usize, forward: bool| -> Option<usize> {
        match (forward, closed) {
            (true, _) if i + 1 < nodes.len() => Some(i + 1),
            (true, true) => Some(1),
            (false, _) if i > 0 => Some(i - 1),
            (false, true) => Some(nodes.len() - 2),
            _ => None,
        }
    };
    let near = |i: usize, p: Point| {
        (nodes[i].at.lat - p.lat).abs() <= NEAR && (nodes[i].at.lon - p.lon).abs() <= NEAR
    };
    let gap = |p: Point, a: usize, b: usize| project(p, nodes[a].at, nodes[b].at).distance_mm;

    let start = shape.first().copied().filter(|_| open_start);
    let end = shape
        .last()
        .copied()
        .filter(|_| open_end && (shape.len() > 1 || !open_start));
    let inner = &shape[start.is_some() as usize..shape.len() - end.is_some() as usize];

    let Some((&first, rest)) = inner.split_first() else {
        // Both ends are mid-block and no node lies between them in the shape: take the segment nearest each
        // end and everything between the two.
        let (Some(a), Some(b)) = (start, end) else {
            return Vec::new();
        };
        let nearest = |p: Point| (0..nodes.len() - 1).min_by_key(|&s| gap(p, s, s + 1));
        let (Some(from), Some(to)) = (nearest(a), nearest(b)) else {
            return Vec::new();
        };
        let along = |p: Point, s: usize| project(p, nodes[s].at, nodes[s + 1].at).along_mm;
        let forward = if from == to {
            along(a, from) <= along(b, to)
        } else {
            from < to
        };
        let run: Vec<usize> = if forward {
            (from..=to + 1).collect()
        } else {
            (to..=from + 1).rev().collect()
        };
        return vec![(gap(a, from, from + 1) + gap(b, to, to + 1), run)];
    };

    let mut found = Vec::new();
    for from in (0..nodes.len()).filter(|&i| near(i, first)) {
        'direction: for forward in [true, false] {
            let mut run = vec![from];
            for &point in rest {
                let mut reached = false;
                for _ in 0..MAX_SKIP {
                    let Some(next) = step(*run.last().unwrap(), forward) else {
                        break;
                    };
                    run.push(next);
                    if near(next, point) {
                        reached = true;
                        break;
                    }
                }
                if !reached {
                    continue 'direction;
                }
            }
            // The shape's last point may stand for the node reached or for one right after it at the same
            // spot. Offer each, so the next edge can start from whichever it shares.
            let mut ends = vec![run.clone()];
            if end.is_none() {
                let last_point = *inner.last().unwrap();
                while ends.len() <= MAX_SKIP {
                    let reach = ends.last().unwrap();
                    match step(*reach.last().unwrap(), forward) {
                        Some(next) if near(next, last_point) => {
                            let mut longer = reach.clone();
                            longer.push(next);
                            ends.push(longer);
                        }
                        _ => break,
                    }
                }
            }
            for mut run in ends {
                // Reach out to the nodes around the snapped ends, and score how well they fit.
                let mut misfit = 0;
                if let Some(p) = start {
                    match step(run[0], !forward) {
                        Some(before) => {
                            misfit += gap(p, before, run[0]);
                            run.insert(0, before);
                        }
                        None => misfit += gap(p, run[0], run[0]),
                    }
                }
                if let Some(p) = end {
                    let last = *run.last().unwrap();
                    match step(last, forward) {
                        Some(after) => {
                            misfit += gap(p, last, after);
                            run.push(after);
                        }
                        None => misfit += gap(p, last, last),
                    }
                }
                found.push((misfit, run));
            }
        }
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use klippelgo_verify::WayNode;

    fn way(id: u64, coords: &[(i32, i32)]) -> Way {
        Way {
            id,
            flags: 0x0f,
            nodes: coords
                .iter()
                .enumerate()
                .map(|(i, &(lat, lon))| WayNode {
                    id: id * 100 + i as u64,
                    at: Point { lat, lon },
                })
                .collect(),
        }
    }

    fn p(lat: i32, lon: i32) -> Point {
        Point { lat, lon }
    }

    /// Row street 1 runs east along lat 0, column street 2 runs north along lon 3000 from its end.
    fn map(id: u64) -> Result<Option<Way>, Error> {
        Ok(match id {
            1 => Some(way(1, &[(0, 0), (0, 1000), (0, 2000), (0, 3000)])),
            2 => {
                let mut w = way(2, &[(0, 3000), (1000, 3000), (2000, 3000)]);
                w.nodes[0].id = 103; // shares the corner node with way 1
                Some(w)
            }
            // A roundabout: closed, its last node the first again.
            3 => {
                let mut w = way(3, &[(0, 0), (0, 1000), (1000, 1000), (1000, 0), (0, 0)]);
                w.nodes[4].id = 300;
                Some(w)
            }
            _ => None,
        })
    }

    #[test]
    fn polylines_round_trip() {
        // The example from the polyline format's documentation, at six decimals.
        let points = [
            p(385_000_000, -1_202_000_000),
            p(407_000_000, -1_209_500_000),
            p(432_520_000, -1_264_530_000),
        ];
        let text = encode_polyline6(&points);
        assert_eq!(decode_polyline6(&text).unwrap(), points);
        assert_eq!(text, "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI");
        assert!(decode_polyline6("_izlhA~rlgdF_{geC").is_err());
    }

    #[test]
    fn follows_edges_around_a_corner_from_mid_block_to_mid_block() {
        let edges = [
            // Snapped mid-block between the first two nodes, rounded as Valhalla would.
            Edge {
                way: 1,
                shape: vec![p(0, 400), p(0, 1000), p(0, 2000), p(0, 3000)],
                gap_before: false,
            },
            Edge {
                way: 2,
                shape: vec![p(0, 3000), p(1000, 3000), p(1600, 3000)],
                gap_before: false,
            },
        ];
        let steps = steps(&edges, map).unwrap();
        let nodes: Vec<u64> = steps.iter().map(|s| s.node).collect();
        assert_eq!(nodes, [100, 101, 102, 103, 201, 202]);
        assert_eq!(steps[3].way, 1);
        assert_eq!(steps[4].way, 2);
    }

    #[test]
    fn follows_a_way_against_its_node_order_and_within_one_block() {
        let back = [Edge {
            way: 1,
            shape: vec![p(0, 2600), p(0, 2000), p(0, 1000), p(0, 700)],
            gap_before: false,
        }];
        let nodes: Vec<u64> = steps(&back, map).unwrap().iter().map(|s| s.node).collect();
        assert_eq!(nodes, [103, 102, 101, 100]);

        let block = [Edge {
            way: 1,
            shape: vec![p(0, 1800), p(0, 1200)],
            gap_before: false,
        }];
        let nodes: Vec<u64> = steps(&block, map).unwrap().iter().map(|s| s.node).collect();
        assert_eq!(nodes, [102, 101]);
    }

    #[test]
    fn follows_a_roundabout_past_its_closing_node() {
        let edges = [
            Edge {
                way: 3,
                shape: vec![p(1000, 1000), p(1000, 0), p(0, 0)],
                gap_before: false,
            },
            Edge {
                way: 3,
                shape: vec![p(0, 0), p(0, 1000)],
                gap_before: false,
            },
        ];
        let around = |e: &[Edge]| {
            // Neither edge is a trip's first or last here, so put them between two others.
            let mut all = vec![Edge {
                way: 3,
                shape: vec![p(0, 1000), p(1000, 1000)],
                gap_before: false,
            }];
            all.extend_from_slice(e);
            all.push(Edge {
                way: 3,
                shape: vec![p(0, 1000), p(1000, 1000)],
                gap_before: false,
            });
            steps(&all, map)
                .unwrap()
                .iter()
                .map(|s| s.node)
                .collect::<Vec<_>>()
        };
        assert_eq!(around(&edges), [301, 302, 303, 300, 301, 302]);
    }

    #[test]
    fn joins_edges_across_a_gap_in_the_match() {
        let piece = |shape: Vec<Point>, gap_before| Edge {
            way: 1,
            shape,
            gap_before,
        };
        let nodes = |edges: &[Edge]| {
            steps(edges, map)
                .unwrap()
                .iter()
                .map(|s| s.node)
                .collect::<Vec<_>>()
        };
        // The thread is lost mid-block and found again further along the same block.
        assert_eq!(
            nodes(&[
                piece(vec![p(0, 100), p(0, 1400)], false),
                piece(vec![p(0, 1600), p(0, 2900)], true)
            ]),
            [100, 101, 102, 103]
        );
        // Found again a little behind where it was lost, in the block before.
        assert_eq!(
            nodes(&[
                piece(vec![p(0, 100), p(0, 1000), p(0, 1200)], false),
                piece(vec![p(0, 900), p(0, 1000), p(0, 2000), p(0, 2500)], true)
            ]),
            [100, 101, 102, 103]
        );
        // Found again on a street that does not touch where it was lost: there is no path to tell.
        let apart = [
            piece(vec![p(0, 100), p(0, 400)], false),
            Edge {
                way: 2,
                shape: vec![p(1200, 3000), p(1800, 3000)],
                gap_before: true,
            },
        ];
        assert!(matches!(
            steps(&apart, map),
            Err(Error::OffTheMap { edge: 1, .. })
        ));
    }

    #[test]
    fn rejects_shapes_that_leave_their_way() {
        let astray = [
            Edge {
                way: 1,
                shape: vec![p(0, 0), p(0, 1000)],
                gap_before: false,
            },
            Edge {
                way: 1,
                shape: vec![p(0, 1000), p(500, 1500)],
                gap_before: false,
            },
            Edge {
                way: 1,
                shape: vec![p(0, 2000), p(0, 3000)],
                gap_before: false,
            },
        ];
        assert!(matches!(
            steps(&astray, map),
            Err(Error::OffTheMap { edge: 1, way: 1 })
        ));
        let unknown = [Edge {
            way: 9,
            shape: vec![p(0, 0), p(0, 1000)],
            gap_before: false,
        }];
        assert!(matches!(steps(&unknown, map), Err(Error::UnknownWay(9))));
        // Two edges that do not meet.
        let apart = [
            Edge {
                way: 1,
                shape: vec![p(0, 0), p(0, 1000)],
                gap_before: false,
            },
            Edge {
                way: 2,
                shape: vec![p(1000, 3000), p(2000, 3000)],
                gap_before: false,
            },
        ];
        assert!(matches!(
            steps(&apart, map),
            Err(Error::OffTheMap { edge: 1, .. })
        ));
    }

    #[test]
    fn drops_only_the_spurs_the_log_does_not_back() {
        use klippelgo_verify::Error as Refusal;
        let path = |nodes: &[u64]| {
            nodes
                .iter()
                .map(|&node| Step { way: 1, node })
                .collect::<Vec<_>>()
        };
        let nodes = |path: &[Step]| path.iter().map(|s| s.node).collect::<Vec<_>>();
        // 1 2 3, up a side street to 7 8 and back, 4, into another to 9 and back, 5.
        let matched = path(&[1, 2, 3, 7, 8, 7, 3, 4, 9, 4, 5]);
        assert_eq!(spurs(&matched), [(2, 6), (7, 9)]);

        // A verifier that objects to node 8 at fix 10: only the first spur goes. The trip did visit node 9.
        let (kept, ()) = without_false_spurs(matched.clone(), |p| {
            if p.iter().any(|s| s.node == 8) {
                Err(Refusal::TooFast { fix: 10 })
            } else {
                Ok(())
            }
        })
        .unwrap();
        assert_eq!(nodes(&kept), [1, 2, 3, 4, 9, 4, 5]);

        // Objects to both, one after the other.
        let (kept, ()) = without_false_spurs(matched.clone(), |p| {
            if p.iter().any(|s| s.node == 8) {
                Err(Refusal::TooFast { fix: 10 })
            } else if p.iter().any(|s| s.node == 9) {
                Err(Refusal::OffPath { fix: 20 })
            } else {
                Ok(())
            }
        })
        .unwrap();
        assert_eq!(nodes(&kept), [1, 2, 3, 4, 5]);

        // Nothing to drop helps: the refusal stands. Other refusals are passed on untouched.
        let stuck = without_false_spurs(matched.clone(), |_| {
            Err::<(), _>(Refusal::OffPath { fix: 3 })
        });
        assert_eq!(stuck.unwrap_err(), Refusal::OffPath { fix: 3 });
        let other = without_false_spurs(matched, |_| Err::<(), _>(Refusal::WrongFare));
        assert_eq!(other.unwrap_err(), Refusal::WrongFare);
    }

    #[test]
    fn reads_valhalla_responses() {
        let shape = encode_polyline6(&[p(0, 400), p(0, 1000), p(0, 2000)]);
        let attributes = format!(
            r#"{{"shape":"{shape}","edges":[{{"way_id":1,"begin_shape_index":0,"end_shape_index":1}},
                {{"way_id":1,"begin_shape_index":1,"end_shape_index":2}}]}}"#
        );
        let edges = parse_edges(&attributes).unwrap();
        assert_eq!(edges.len(), 2);
        assert!(!edges[1].gap_before);
        let broken = attributes.replace(
            r#""begin_shape_index":1,"end_shape_index":2"#,
            r#""begin_shape_index":2,"end_shape_index":2"#,
        );
        assert!(parse_edges(&broken).unwrap()[1].gap_before);
        assert_eq!(edges[1].shape, [p(0, 1000), p(0, 2000)]);

        let route = format!(
            r#"{{"trip":{{"units":"kilometers","legs":[{{"shape":"{shape}","summary":{{"length":8.512,"time":1319.6}}}}]}}}}"#
        );
        let route = parse_route(&route).unwrap();
        assert_eq!(
            (route.length_m, route.time_s, route.points.len()),
            (8512, 1320, 3)
        );
        assert!(matches!(
            parse_route(r#"{"error":"No path could be found for input"}"#),
            Err(Error::Valhalla(_))
        ));
    }
}
