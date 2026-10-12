//! Geohash cells for P2P discovery. Drivers announce presence in a geohash-5 cell (about 5 km) and riders post
//! requests to the pickup cell and its neighbours, so exact positions never reach the gossip network.

const BASE32: &[u8; 32] = b"0123456789bcdefghjkmnpqrstuvwxyz";

/// Precision used for gossip topics.
pub const DISCOVERY_PRECISION: usize = 5;

/// Precision of the pickup and drop-off regions a request shows to drivers: cells of about 1.2 km by 0.6 km.
/// Small enough for a driver to judge the trip and price it, large enough to hold hundreds of addresses in a
/// city, so the request does not point at anyone's door.
pub const REGION_PRECISION: usize = 6;

/// The south-west and north-east corners of a geohash cell, as (lat, lon) in degrees. `None` for text that is
/// not a geohash.
pub fn bounds(cell: &str) -> Option<((f64, f64), (f64, f64))> {
    let (mut lat, mut lon) = ((-90.0, 90.0), (-180.0, 180.0));
    let mut even = true;
    if cell.is_empty() {
        return None;
    }
    for c in cell.bytes() {
        let value = BASE32.iter().position(|b| *b == c)?;
        for bit in (0..5).rev() {
            let range = if even { &mut lon } else { &mut lat };
            let mid = (range.0 + range.1) / 2.0;
            if value >> bit & 1 == 1 {
                range.0 = mid;
            } else {
                range.1 = mid;
            }
            even = !even;
        }
    }
    Some(((lat.0, lon.0), (lat.1, lon.1)))
}

pub fn encode(lat: f64, lon: f64, precision: usize) -> String {
    let (mut lat_lo, mut lat_hi) = (-90.0, 90.0);
    let (mut lon_lo, mut lon_hi) = (-180.0, 180.0);
    let mut out = String::with_capacity(precision);
    let (mut bits, mut ch, mut even) = (0, 0usize, true);
    while out.len() < precision {
        if even {
            let mid = (lon_lo + lon_hi) / 2.0;
            if lon >= mid {
                ch = ch << 1 | 1;
                lon_lo = mid
            } else {
                ch <<= 1;
                lon_hi = mid
            }
        } else {
            let mid = (lat_lo + lat_hi) / 2.0;
            if lat >= mid {
                ch = ch << 1 | 1;
                lat_lo = mid
            } else {
                ch <<= 1;
                lat_hi = mid
            }
        }
        even = !even;
        bits += 1;
        if bits == 5 {
            out.push(BASE32[ch] as char);
            bits = 0;
            ch = 0;
        }
    }
    out
}

/// Gossip topic a driver subscribes to for a cell.
pub fn topic(cell: &str) -> String {
    format!("/klippelgo/presence/1/{cell}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_known_cells() {
        // Reference vector from the geohash spec (Jutland, Denmark).
        assert_eq!(encode(57.64911, 10.40744, 11), "u4pruydqqvj");
        // The centre of Cascavel, Paraná, the pilot city: a 5-character cell is all the gossip network sees.
        assert_eq!(encode(-24.9555, -53.4552, DISCOVERY_PRECISION), "6g9fp");
        assert_eq!(topic("6gycf"), "/klippelgo/presence/1/6gycf");
    }

    #[test]
    fn a_region_cell_holds_its_point_and_is_about_a_kilometre() {
        let (lat, lon) = (-24.9555, -53.4552);
        let cell = encode(lat, lon, REGION_PRECISION);
        assert_eq!(cell, "6g9fp8");
        let ((south, west), (north, east)) = bounds(&cell).unwrap();
        assert!(south <= lat && lat < north && west <= lon && lon < east);
        // 0.0055 by 0.011 degrees: about 610 m by 1 110 m here.
        assert!(
            (north - south - 0.0054931640625).abs() < 1e-12
                && (east - west - 0.010986328125).abs() < 1e-12
        );
        assert_eq!(bounds("6g9a"), None); // 'a' is not in the geohash alphabet
        assert_eq!(bounds(""), None);
    }
}
