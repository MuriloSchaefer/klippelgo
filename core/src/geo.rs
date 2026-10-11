//! Geohash cells for P2P discovery. Drivers announce presence in a geohash-5 cell (about 5 km) and riders post
//! requests to the pickup cell and its neighbours, so exact positions never reach the gossip network.

const BASE32: &[u8; 32] = b"0123456789bcdefghjkmnpqrstuvwxyz";

/// Precision used for gossip topics.
pub const DISCOVERY_PRECISION: usize = 5;

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
}
