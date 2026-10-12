//! The GPS log each phone keeps during a ride.
//!
//! Every fix is hashed into a running head, so the log can only grow. Every 30 seconds the phone signs the head
//! with a trace key made for this ride and certified by its wallet, and the other phone countersigns it. After
//! that, neither side can rewrite its own log without the other holding proof.
//!
//! ```text
//! fix       = t ms u64 ‖ lat i32 ‖ lon i32 ‖ accuracy cm u32 ‖ speed cm/s u32 ‖ mock u8
//! head[0]   = keccak256(keccak256("KlippelGo trace v1") ‖ ride ‖ role u8 ‖ trace key)
//! head[n]   = keccak256(head[n-1] ‖ fix[n])
//! ride      = chain id u64 ‖ escrow address ‖ ride id u256 ‖ quote hash
//! ```

use crate::geo::distance_mm;
use crate::sig::{trace_key_signed, wallet_signer, TraceKey};
use crate::{keccak256, Address, Domain, Error, Hash, Params, Point};
use alloc::vec::Vec;

const TRACE_TAG: &[u8] = b"KlippelGo trace v1";
const TRACE_KEY_TAG: &[u8] = b"KlippelGo trace key v1";
const TRACE_HEAD_TAG: &[u8] = b"KlippelGo trace head v1";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Role {
    Rider = 0,
    Driver = 1,
}

/// One GNSS reading.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Fix {
    /// Unix milliseconds.
    pub t_ms: u64,
    pub at: Point,
    /// The radius the phone reports for the fix, in centimetres.
    pub accuracy_cm: u32,
    pub speed_cm_s: u32,
    /// The platform flagged the location as coming from a mock provider.
    pub mock: bool,
}

impl Fix {
    pub fn encode(&self) -> [u8; 25] {
        let mut out = [0u8; 25];
        out[..8].copy_from_slice(&self.t_ms.to_be_bytes());
        out[8..12].copy_from_slice(&self.at.lat.to_be_bytes());
        out[12..16].copy_from_slice(&self.at.lon.to_be_bytes());
        out[16..20].copy_from_slice(&self.accuracy_cm.to_be_bytes());
        out[20..24].copy_from_slice(&self.speed_cm_s.to_be_bytes());
        out[24] = self.mock as u8;
        out
    }
}

/// The ride a trace was recorded for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RideRef {
    pub domain: Domain,
    pub ride_id: u64,
    pub quote_hash: Hash,
}

impl RideRef {
    fn encode(&self) -> [u8; 92] {
        let mut out = [0u8; 92];
        out[..8].copy_from_slice(&self.domain.chain_id.to_be_bytes());
        out[8..28].copy_from_slice(&self.domain.escrow);
        out[52..60].copy_from_slice(&self.ride_id.to_be_bytes());
        out[60..].copy_from_slice(&self.quote_hash);
        out
    }
}

/// The head of an empty log.
pub fn genesis(ride: &RideRef, role: Role, key: &TraceKey) -> Hash {
    keccak256(&[&keccak256(&[TRACE_TAG]), &ride.encode(), &[role as u8], key])
}

/// The head after one more fix.
pub fn advance(head: &Hash, fix: &Fix) -> Hash {
    keccak256(&[head, &fix.encode()])
}

/// What a wallet signs to certify a trace key for one ride and role.
pub fn key_digest(ride: &RideRef, role: Role, key: &TraceKey) -> Hash {
    keccak256(&[
        &keccak256(&[TRACE_KEY_TAG]),
        &ride.encode(),
        &[role as u8],
        key,
    ])
}

/// What a trace key signs, and the other phone's trace key countersigns, for the head after `fixes` fixes. The
/// head already commits to the ride, the role and the key through [`genesis`].
pub fn head_digest(fixes: u32, head: &Hash) -> Hash {
    keccak256(&[&keccak256(&[TRACE_HEAD_TAG]), &fixes.to_be_bytes(), head])
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedHead {
    /// How many fixes the head covers.
    pub fixes: u32,
    pub head: Hash,
    /// By the log's own trace key.
    pub signature: [u8; 64],
    /// By the other phone's trace key, when the encrypted channel was up.
    pub countersignature: Option<[u8; 64]>,
}

/// One phone's log with everything needed to check it without the other phone.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Trace {
    pub role: Role,
    pub key: TraceKey,
    /// The owner's wallet signature over [`key_digest`].
    pub certificate: [u8; 65],
    /// The other phone's trace key and its wallet's certificate, received at ride start.
    pub peer_key: TraceKey,
    pub peer_certificate: [u8; 65],
    pub fixes: Vec<Fix>,
    pub heads: Vec<SignedHead>,
}

/// A log while it is being recorded. The phone pushes fixes, asks what to sign, and hands back the signatures
/// its hardware key and the other phone made.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Log {
    role: Role,
    key: TraceKey,
    head: Hash,
    fixes: Vec<Fix>,
    heads: Vec<SignedHead>,
}

impl Log {
    pub fn new(ride: &RideRef, role: Role, key: TraceKey) -> Self {
        Log {
            role,
            key,
            head: genesis(ride, role, &key),
            fixes: Vec::new(),
            heads: Vec::new(),
        }
    }

    /// Appends a fix. Returns false, leaving the log as it was, when the fix is not later than the last one.
    pub fn push(&mut self, fix: Fix) -> bool {
        if self.fixes.last().is_some_and(|last| fix.t_ms <= last.t_ms) {
            return false;
        }
        self.head = advance(&self.head, &fix);
        self.fixes.push(fix);
        true
    }

    pub fn fixes(&self) -> &[Fix] {
        &self.fixes
    }

    /// The current head.
    pub fn head(&self) -> Hash {
        self.head
    }

    /// What to sign now: how many fixes the head covers, and the digest for the trace key. `None` while
    /// nothing was recorded since the last signed head.
    pub fn to_sign(&self) -> Option<(u32, Hash)> {
        let fixes = self.fixes.len() as u32;
        (fixes > self.heads.last().map_or(0, |h| h.fixes))
            .then(|| (fixes, head_digest(fixes, &self.head)))
    }

    /// Records this phone's signature over the current head. Returns false if fixes arrived since
    /// [`Log::to_sign`] was asked, in which case ask again.
    pub fn signed(&mut self, fixes: u32, signature: [u8; 64]) -> bool {
        if self.to_sign().is_none_or(|(now, _)| now != fixes) {
            return false;
        }
        self.heads.push(SignedHead {
            fixes,
            head: self.head,
            signature,
            countersignature: None,
        });
        true
    }

    /// Records the other phone's signature over one of this log's signed heads.
    pub fn countersigned(&mut self, fixes: u32, countersignature: [u8; 64]) -> bool {
        match self.heads.iter_mut().find(|h| h.fixes == fixes) {
            Some(head) => {
                head.countersignature = Some(countersignature);
                true
            }
            None => false,
        }
    }

    /// The log as evidence, with the certificates exchanged at ride start.
    pub fn finish(
        self,
        certificate: [u8; 65],
        peer_key: TraceKey,
        peer_certificate: [u8; 65],
    ) -> Trace {
        Trace {
            role: self.role,
            key: self.key,
            certificate,
            peer_key,
            peer_certificate,
            fixes: self.fixes,
            heads: self.heads,
        }
    }
}

/// Checks a head the other phone sent for countersigning: it must be signed by their certified trace key.
/// The countersigner cannot see the other log, only that its owner committed to this head.
pub fn peer_head_is_signed(
    peer_key: &TraceKey,
    fixes: u32,
    head: &Hash,
    signature: &[u8; 64],
) -> bool {
    trace_key_signed(peer_key, &head_digest(fixes, head), signature)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TraceReport {
    /// The head after the last fix.
    pub head: Hash,
    pub countersigned_heads: u32,
    /// Longest silence between two fixes, in milliseconds.
    pub longest_gap_ms: u64,
}

/// Checks a log on its own: both trace keys are certified by the right wallets, the fixes are in order, real
/// and physically possible, the signed heads match the fixes and cover all of them, and the other phone
/// countersigned the last one.
pub fn check(
    trace: &Trace,
    ride: &RideRef,
    owner: &Address,
    peer: &Address,
    params: &Params,
) -> Result<TraceReport, Error> {
    let peer_role = match trace.role {
        Role::Rider => Role::Driver,
        Role::Driver => Role::Rider,
    };
    let certified = |role, key: &TraceKey, certificate, wallet: &Address| {
        wallet_signer(&key_digest(ride, role, key), certificate).as_ref() == Some(wallet)
    };
    if !certified(trace.role, &trace.key, &trace.certificate, owner)
        || !certified(peer_role, &trace.peer_key, &trace.peer_certificate, peer)
    {
        return Err(Error::BadCertificate);
    }
    if trace.fixes.is_empty() {
        return Err(Error::EmptyTrace);
    }

    let mut head = genesis(ride, trace.role, &trace.key);
    let mut heads = trace.heads.iter().enumerate().peekable();
    let mut report = TraceReport {
        head,
        countersigned_heads: 0,
        longest_gap_ms: 0,
    };
    let mut signed_at = trace.fixes[0].t_ms;
    for (i, fix) in trace.fixes.iter().enumerate() {
        fix.at.check()?;
        if fix.mock {
            return Err(Error::MockLocation { fix: i });
        }
        if i > 0 {
            let gap = check_motion(&trace.fixes[i - 1], fix, i, params)?;
            report.longest_gap_ms = report.longest_gap_ms.max(gap);
        }
        head = advance(&head, fix);

        let Some((h, signed)) = heads.next_if(|(_, s)| s.fixes as usize == i + 1) else {
            continue;
        };
        if signed.head != head {
            return Err(Error::BadHead { head: h });
        }
        if fix.t_ms - signed_at > params.max_head_interval_ms as u64 {
            return Err(Error::HeadsTooFarApart { head: h });
        }
        signed_at = fix.t_ms;
        let digest = head_digest(signed.fixes, &head);
        if !trace_key_signed(&trace.key, &digest, &signed.signature) {
            return Err(Error::BadSignature { head: h });
        }
        if let Some(countersignature) = &signed.countersignature {
            if !trace_key_signed(&trace.peer_key, &digest, countersignature) {
                return Err(Error::BadSignature { head: h });
            }
            report.countersigned_heads += 1;
        }
    }
    // A head left over is out of order or beyond the log; a log whose last fix is unsigned is not covered.
    if let Some((h, _)) = heads.next() {
        return Err(Error::BadHead { head: h });
    }
    match trace.heads.last() {
        Some(last) if last.fixes as usize == trace.fixes.len() => {
            if last.countersignature.is_none() {
                return Err(Error::NotCountersigned);
            }
        }
        _ => {
            return Err(Error::BadHead {
                head: trace.heads.len(),
            })
        }
    }
    report.head = head;
    Ok(report)
}

/// Checks that a vehicle could have moved from one fix to the next. Returns the time between them.
fn check_motion(from: &Fix, to: &Fix, fix: usize, params: &Params) -> Result<u64, Error> {
    if to.t_ms <= from.t_ms {
        return Err(Error::FixesOutOfOrder { fix });
    }
    let dt_ms = to.t_ms - from.t_ms;
    // cm/s × ms is hundredths of a millimetre.
    let reachable_mm = params.max_speed_cm_s as u64 * dt_ms / 100
        + (from.accuracy_cm as u64 + to.accuracy_cm as u64) * 10;
    if to.speed_cm_s > params.max_speed_cm_s || distance_mm(from.at, to.at) > reachable_mm {
        return Err(Error::TooFast { fix });
    }
    if to.speed_cm_s.abs_diff(from.speed_cm_s) as u64 * 1000 > params.max_accel_cm_s2 as u64 * dt_ms
    {
        return Err(Error::ImpossibleAcceleration { fix });
    }
    Ok(dt_ms)
}
