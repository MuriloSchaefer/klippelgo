//! The evidence of a ride as one JSON file: the quote, and after the trip the receipt and the phones' GPS logs.
//! This is what a party reveals to jurors in a dispute, and what `klippelgo-juror` reads.
//!
//! Hashes, addresses, keys and signatures are `0x` hex. The fare is a decimal string in the token's base units.
//! Coordinates are degrees × 10⁷.

use crate::hex;
use klippelgo_verify::trace::SignedHead;
use klippelgo_verify::{Domain, Fix, Point, Role, RouteQuote, Step, Trace, TripReceipt, Vehicle};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Evidence {
    pub chain_id: u64,
    #[serde(with = "hex::array")]
    pub escrow: [u8; 20],
    pub quote: QuoteFile,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub receipt: Option<ReceiptFile>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub traces: Vec<TraceFile>,
}

impl Evidence {
    pub fn parse(json: &str) -> Result<Self, crate::Error> {
        serde_json::from_str(json).map_err(|e| crate::Error::Evidence(e.to_string()))
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string_pretty(self).expect("evidence always serialises")
    }

    pub fn domain(&self) -> Domain {
        Domain {
            chain_id: self.chain_id,
            escrow: self.escrow,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuoteFile {
    #[serde(with = "hex::array")]
    pub pack_id: [u8; 32],
    pub pickup: [i32; 2],
    pub dropoff: [i32; 2],
    /// `car` or `motorcycle`.
    pub vehicle: String,
    /// `[way id, node id]` steps.
    pub path: Vec<[u64; 2]>,
    pub distance_m: u32,
    pub duration_s: u32,
    #[serde(with = "hex::array")]
    pub salt: [u8; 32],
    pub fare: String,
    #[serde(with = "hex::array")]
    pub token: [u8; 20],
    #[serde(with = "hex::array")]
    pub rider: [u8; 20],
    #[serde(with = "hex::array")]
    pub driver: [u8; 20],
    pub expiry: u64,
    #[serde(with = "hex::array")]
    pub previous: [u8; 32],
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiptFile {
    pub ride_id: u64,
    #[serde(with = "hex::array")]
    pub quote_hash: [u8; 32],
    #[serde(with = "hex::array")]
    pub rider_head: [u8; 32],
    #[serde(with = "hex::array")]
    pub driver_head: [u8; 32],
    pub driven_path: Vec<[u64; 2]>,
    pub driven_distance_m: u32,
    pub started_at: u64,
    pub ended_at: u64,
    pub fare: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TraceFile {
    /// `rider` or `driver`.
    pub role: String,
    #[serde(with = "hex::array")]
    pub key: [u8; 33],
    #[serde(with = "hex::array")]
    pub certificate: [u8; 65],
    #[serde(with = "hex::array")]
    pub peer_key: [u8; 33],
    #[serde(with = "hex::array")]
    pub peer_certificate: [u8; 65],
    pub fixes: Vec<FixFile>,
    pub heads: Vec<HeadFile>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FixFile {
    pub t: u64,
    pub lat: i32,
    pub lon: i32,
    pub accuracy_cm: u32,
    pub speed_cm_s: u32,
    #[serde(default)]
    pub mock: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HeadFile {
    pub fixes: u32,
    #[serde(with = "hex::array")]
    pub head: [u8; 32],
    #[serde(with = "hex::array")]
    pub signature: [u8; 64],
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub countersignature: Option<String>,
}

fn invalid(what: &str) -> crate::Error {
    crate::Error::Evidence(what.to_string())
}

fn point(p: [i32; 2]) -> Point {
    Point {
        lat: p[0],
        lon: p[1],
    }
}

fn steps(path: &[[u64; 2]]) -> Vec<Step> {
    path.iter()
        .map(|s| Step {
            way: s[0],
            node: s[1],
        })
        .collect()
}

fn path(steps: &[Step]) -> Vec<[u64; 2]> {
    steps.iter().map(|s| [s.way, s.node]).collect()
}

/// A decimal amount as the 256-bit big-endian number the hashes use.
pub fn fare_bytes(decimal: &str) -> Option<[u8; 32]> {
    let mut out = [0u8; 32];
    if decimal.is_empty() {
        return None;
    }
    for digit in decimal.bytes() {
        let mut carry = digit.checked_sub(b'0').filter(|d| *d < 10)? as u32;
        for byte in out.iter_mut().rev() {
            let value = *byte as u32 * 10 + carry;
            *byte = value as u8;
            carry = value >> 8;
        }
        if carry != 0 {
            return None;
        }
    }
    Some(out)
}

pub fn fare_decimal(bytes: &[u8; 32]) -> String {
    let mut digits = Vec::new();
    let mut value = *bytes;
    while value.iter().any(|b| *b != 0) {
        let mut remainder = 0u32;
        for byte in value.iter_mut() {
            let current = remainder * 256 + *byte as u32;
            *byte = (current / 10) as u8;
            remainder = current % 10;
        }
        digits.push(b'0' + remainder as u8);
    }
    if digits.is_empty() {
        digits.push(b'0');
    }
    digits.reverse();
    String::from_utf8(digits).unwrap()
}

impl TryFrom<&QuoteFile> for RouteQuote {
    type Error = crate::Error;

    fn try_from(q: &QuoteFile) -> Result<Self, Self::Error> {
        Ok(RouteQuote {
            pack_id: q.pack_id,
            pickup: point(q.pickup),
            dropoff: point(q.dropoff),
            vehicle: match q.vehicle.as_str() {
                "car" => Vehicle::Car,
                "motorcycle" => Vehicle::Motorcycle,
                _ => return Err(invalid("vehicle must be car or motorcycle")),
            },
            path: steps(&q.path),
            distance_m: q.distance_m,
            duration_s: q.duration_s,
            salt: q.salt,
            fare: fare_bytes(&q.fare).ok_or_else(|| invalid("fare must be a decimal number"))?,
            token: q.token,
            rider: q.rider,
            driver: q.driver,
            expiry: q.expiry,
            previous: q.previous,
        })
    }
}

impl From<&RouteQuote> for QuoteFile {
    fn from(q: &RouteQuote) -> Self {
        QuoteFile {
            pack_id: q.pack_id,
            pickup: [q.pickup.lat, q.pickup.lon],
            dropoff: [q.dropoff.lat, q.dropoff.lon],
            vehicle: match q.vehicle {
                Vehicle::Car => "car",
                Vehicle::Motorcycle => "motorcycle",
            }
            .into(),
            path: path(&q.path),
            distance_m: q.distance_m,
            duration_s: q.duration_s,
            salt: q.salt,
            fare: fare_decimal(&q.fare),
            token: q.token,
            rider: q.rider,
            driver: q.driver,
            expiry: q.expiry,
            previous: q.previous,
        }
    }
}

impl TryFrom<&ReceiptFile> for TripReceipt {
    type Error = crate::Error;

    fn try_from(r: &ReceiptFile) -> Result<Self, Self::Error> {
        Ok(TripReceipt {
            ride_id: r.ride_id,
            quote_hash: r.quote_hash,
            rider_head: r.rider_head,
            driver_head: r.driver_head,
            driven_path: steps(&r.driven_path),
            driven_distance_m: r.driven_distance_m,
            started_at: r.started_at,
            ended_at: r.ended_at,
            fare: fare_bytes(&r.fare).ok_or_else(|| invalid("fare must be a decimal number"))?,
        })
    }
}

impl From<&TripReceipt> for ReceiptFile {
    fn from(r: &TripReceipt) -> Self {
        ReceiptFile {
            ride_id: r.ride_id,
            quote_hash: r.quote_hash,
            rider_head: r.rider_head,
            driver_head: r.driver_head,
            driven_path: path(&r.driven_path),
            driven_distance_m: r.driven_distance_m,
            started_at: r.started_at,
            ended_at: r.ended_at,
            fare: fare_decimal(&r.fare),
        }
    }
}

impl TryFrom<&TraceFile> for Trace {
    type Error = crate::Error;

    fn try_from(t: &TraceFile) -> Result<Self, Self::Error> {
        let heads = t.heads.iter().map(|h| {
            let countersignature = match &h.countersignature {
                Some(text) => Some(
                    hex::decode_array(text)
                        .ok_or_else(|| invalid("a countersignature is not 64 bytes"))?,
                ),
                None => None,
            };
            Ok(SignedHead {
                fixes: h.fixes,
                head: h.head,
                signature: h.signature,
                countersignature,
            })
        });
        Ok(Trace {
            role: match t.role.as_str() {
                "rider" => Role::Rider,
                "driver" => Role::Driver,
                _ => return Err(invalid("role must be rider or driver")),
            },
            key: t.key,
            certificate: t.certificate,
            peer_key: t.peer_key,
            peer_certificate: t.peer_certificate,
            fixes: t
                .fixes
                .iter()
                .map(|f| Fix {
                    t_ms: f.t,
                    at: Point {
                        lat: f.lat,
                        lon: f.lon,
                    },
                    accuracy_cm: f.accuracy_cm,
                    speed_cm_s: f.speed_cm_s,
                    mock: f.mock,
                })
                .collect(),
            heads: heads.collect::<Result<_, crate::Error>>()?,
        })
    }
}

impl From<&Trace> for TraceFile {
    fn from(t: &Trace) -> Self {
        TraceFile {
            role: match t.role {
                Role::Rider => "rider",
                Role::Driver => "driver",
            }
            .into(),
            key: t.key,
            certificate: t.certificate,
            peer_key: t.peer_key,
            peer_certificate: t.peer_certificate,
            fixes: t
                .fixes
                .iter()
                .map(|f| FixFile {
                    t: f.t_ms,
                    lat: f.at.lat,
                    lon: f.at.lon,
                    accuracy_cm: f.accuracy_cm,
                    speed_cm_s: f.speed_cm_s,
                    mock: f.mock,
                })
                .collect(),
            heads: t
                .heads
                .iter()
                .map(|h| HeadFile {
                    fixes: h.fixes,
                    head: h.head,
                    signature: h.signature,
                    countersignature: h.countersignature.map(|c| hex::encode(&c)),
                })
                .collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fares_convert_between_decimal_and_bytes() {
        let bytes = fare_bytes("234000").unwrap();
        assert_eq!(bytes[29..], [0x03, 0x92, 0x10]);
        assert_eq!(fare_decimal(&bytes), "234000");
        let brla = "23400000000000000000";
        assert_eq!(fare_decimal(&fare_bytes(brla).unwrap()), brla);
        assert_eq!(fare_decimal(&[0; 32]), "0");
        let max = "115792089237316195423570985008687907853269984665640564039457584007913129639935";
        assert_eq!(fare_bytes(max), Some([0xff; 32]));
        assert_eq!(
            fare_bytes(
                "115792089237316195423570985008687907853269984665640564039457584007913129639936"
            ),
            None
        );
        assert_eq!(fare_bytes("12a"), None);
        assert_eq!(fare_bytes(""), None);
    }
}
