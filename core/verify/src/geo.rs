//! Distances in fixed point. Coordinates are degrees × 10⁷ as `i32`, exactly as OpenStreetMap stores them.
//!
//! Every length is equirectangular: a degree of latitude is a fixed number of metres, and a degree of longitude
//! is that times the cosine of the latitude. Over one road segment this is accurate to well under 0.1%. The
//! result depends only on integer operations, so it is the same on every machine.

use crate::Error;

/// Latitudes beyond this are rejected: the formula degrades near the poles, where nobody hails a ride.
pub const MAX_LAT_E7: i32 = 850_000_000;

/// Millimetres per 10⁻⁷ degree of latitude, as a fraction, for a sphere of radius 6 371 008.8 m.
const MM_NUM: u128 = 111_195_080_234;
const MM_DEN: u128 = 10_000_000_000;

/// Lengths are carried in 10⁻⁷ degrees of latitude with this many fractional bits.
const FRAC: u32 = 30;

/// π / (360 × 10⁷) × 2⁹⁰: turns the sum of two latitudes (twice their mean) into radians × 2⁶⁰.
const HALF_E7_TO_RAD_Q90: u128 = 1_080_306_481_389_891_863;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Point {
    pub lat: i32,
    pub lon: i32,
}

impl Point {
    pub fn check(self) -> Result<Self, Error> {
        if self.lat.abs() > MAX_LAT_E7 || self.lon.abs() > 1_800_000_000 {
            return Err(Error::OutOfRange);
        }
        Ok(self)
    }
}

/// Where a point falls on a segment.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Projection {
    /// Distance from the point to the nearest point of the segment, in millimetres.
    pub distance_mm: u64,
    /// Distance from the segment's start to that nearest point, in millimetres.
    pub along_mm: u64,
}

/// Length of the segment from `a` to `b`, in millimetres, rounded down.
pub fn segment_mm(a: Point, b: Point) -> u64 {
    let (x, y) = offset(a, b, cos_q30(a.lat as i64 + b.lat as i64));
    to_mm(hypot(x, y))
}

/// Projects `p` on the segment from `a` to `b`, clamped to its ends.
pub fn project(p: Point, a: Point, b: Point) -> Projection {
    let cos = cos_q30(a.lat as i64 + b.lat as i64);
    let (bx, by) = offset(a, b, cos);
    let (px, py) = offset(a, p, cos);
    let length = hypot(bx, by);
    let dot = px * bx + py * by;
    if length == 0 || dot <= 0 {
        return Projection {
            distance_mm: to_mm(hypot(px, py)),
            along_mm: 0,
        };
    }
    let along = dot as u128 / length;
    if along >= length {
        return Projection {
            distance_mm: to_mm(hypot(px - bx, py - by)),
            along_mm: to_mm(length),
        };
    }
    let cross = (px * by - py * bx).unsigned_abs();
    Projection {
        distance_mm: to_mm(cross / length),
        along_mm: to_mm(along),
    }
}

/// Straight-line distance between two points, in millimetres.
pub fn distance_mm(a: Point, b: Point) -> u64 {
    segment_mm(a, b)
}

/// Half-sizes, in 10⁻⁷ degrees of latitude and longitude, of a box around `p` that holds every point within
/// `mm` of it. Used to skip segments that cannot be near a fix.
pub fn reach(p: Point, mm: u64) -> (i64, i64) {
    let lat = (mm as u128 * MM_DEN / MM_NUM) as i64 + 1;
    let cos = cos_q30(2 * p.lat as i64).max(1);
    let lon = ((lat as i128) << FRAC) / cos as i128 + 1;
    (lat, lon as i64)
}

/// Offset from `a` to `b` in fixed-point units: east scaled by `cos`, then north.
fn offset(a: Point, b: Point, cos: i64) -> (i128, i128) {
    let mut dlon = b.lon as i64 - a.lon as i64;
    // The short way round the antimeridian.
    if dlon > 1_800_000_000 {
        dlon -= 3_600_000_000;
    } else if dlon < -1_800_000_000 {
        dlon += 3_600_000_000;
    }
    let dlat = b.lat as i64 - a.lat as i64;
    (dlon as i128 * cos as i128, (dlat as i128) << FRAC)
}

fn hypot(x: i128, y: i128) -> u128 {
    isqrt((x * x + y * y) as u128)
}

fn to_mm(length: u128) -> u64 {
    (length * MM_NUM / (MM_DEN << FRAC)) as u64
}

/// Cosine of the mean of two latitudes, given their sum in 10⁻⁷ degrees, as a fraction of 2³⁰.
pub fn cos_q30(lat_sum_e7: i64) -> i64 {
    const ONE: u128 = 1 << 60;
    let x = (lat_sum_e7.unsigned_abs() as u128 * HALF_E7_TO_RAD_Q90) >> 30;
    let x2 = (x * x) >> 60;
    // Taylor series 1 - x²/2! + x⁴/4! - ..., to x¹⁸. For |x| ≤ π/2 the next term is below 10⁻¹².
    let (mut plus, mut minus, mut term) = (ONE, 0u128, ONE);
    for k in 1..=9u128 {
        term = ((term * x2) >> 60) / ((2 * k - 1) * (2 * k));
        if k % 2 == 1 {
            minus += term;
        } else {
            plus += term;
        }
    }
    (plus.saturating_sub(minus) >> 30) as i64
}

/// Largest integer whose square is at most `n`.
pub fn isqrt(n: u128) -> u128 {
    if n < 2 {
        return n;
    }
    // Newton's method from above: the first guess is at least the root, and each step moves down to it.
    let mut x = 1u128 << (128 - n.leading_zeros()).div_ceil(2);
    loop {
        let next = (x + n / x) >> 1;
        if next >= x {
            return x;
        }
        x = next;
    }
}

#[cfg(test)]
mod tests {
    extern crate std;
    use super::*;

    const SE: Point = Point {
        lat: -235_505_000,
        lon: -466_333_000,
    };

    #[test]
    fn cosine_matches_floating_point() {
        for deg in [0.0f64, 1.0, 23.5505, 45.0, 60.0, 84.9] {
            let got = cos_q30((deg * 2e7) as i64) as f64 / (1u64 << 30) as f64;
            assert!((got - deg.to_radians().cos()).abs() < 2e-9, "{deg}: {got}");
            assert_eq!(cos_q30((deg * 2e7) as i64), cos_q30(-(deg * 2e7) as i64));
        }
        assert_eq!(cos_q30(0), 1 << 30);
    }

    #[test]
    fn integer_square_root_is_exact() {
        for n in [0u128, 1, 2, 3, 4, 15, 16, 17, 1 << 64, u128::MAX] {
            let r = isqrt(n);
            assert!(r * r <= n);
            assert!(r == u64::MAX as u128 || (r + 1) * (r + 1) > n, "{n}");
        }
    }

    #[test]
    fn lengths_match_the_haversine_formula_at_city_scale() {
        // About 1.1 km north-east of Praça da Sé.
        let b = Point {
            lat: SE.lat + 70_000,
            lon: SE.lon + 80_000,
        };
        let haversine = {
            let (la1, la2) = (
                (SE.lat as f64 / 1e7).to_radians(),
                (b.lat as f64 / 1e7).to_radians(),
            );
            let dlo = ((b.lon - SE.lon) as f64 / 1e7).to_radians();
            let h = ((la2 - la1) / 2.0).sin().powi(2)
                + la1.cos() * la2.cos() * (dlo / 2.0).sin().powi(2);
            2.0 * 6_371_008.8 * h.sqrt().asin()
        };
        let got = segment_mm(SE, b) as f64 / 1000.0;
        assert!(
            (got - haversine).abs() / haversine < 1e-5,
            "{got} vs {haversine}"
        );
        assert_eq!(segment_mm(SE, b), segment_mm(b, SE));
        assert_eq!(segment_mm(SE, SE), 0);
        // One degree of latitude.
        let north = Point {
            lat: SE.lat + 10_000_000,
            lon: SE.lon,
        };
        assert_eq!(segment_mm(SE, north), 111_195_080);
    }

    #[test]
    fn projects_onto_a_segment_and_clamps_to_its_ends() {
        let east = Point {
            lat: SE.lat,
            lon: SE.lon + 100_000,
        };
        let length = segment_mm(SE, east);
        // 30% of the way along and a little north of it.
        let p = Point {
            lat: SE.lat + 1_000,
            lon: SE.lon + 30_000,
        };
        let on = project(p, SE, east);
        assert!(on.along_mm.abs_diff(length * 3 / 10) <= 2, "{on:?}");
        assert!(on.distance_mm.abs_diff(11_119) <= 2, "{on:?}");

        let before = Point {
            lat: SE.lat,
            lon: SE.lon - 10_000,
        };
        assert_eq!(project(before, SE, east).along_mm, 0);
        assert_eq!(
            project(before, SE, east).distance_mm,
            segment_mm(before, SE)
        );
        let after = Point {
            lat: SE.lat,
            lon: east.lon + 10_000,
        };
        assert_eq!(project(after, SE, east).along_mm, length);
        // A segment of no length.
        assert_eq!(project(p, SE, SE).distance_mm, segment_mm(SE, p));
    }

    #[test]
    fn reach_covers_the_distance_it_was_asked_for() {
        let (lat, lon) = reach(SE, 50_000);
        let corner = |dlat: i64, dlon: i64| Point {
            lat: SE.lat + dlat as i32,
            lon: SE.lon + dlon as i32,
        };
        assert!(segment_mm(SE, corner(lat, 0)) >= 50_000);
        assert!(segment_mm(SE, corner(0, lon)) >= 50_000);
        assert!(segment_mm(SE, corner(lat, 0)) < 50_100);
    }
}
