//! The two kinds of signature a trip proof carries.
//!
//! Wallet keys sign 32-byte digests as Ethereum signed messages (secp256k1), the same way the gossip layer signs
//! requests and offers. Trace keys are P-256 keys held in the phone's Secure Enclave or StrongBox; they sign a
//! digest with ECDSA over its SHA-256, which is what both platforms' hardware offers.

use crate::{keccak256, Address, Hash};

/// A compressed P-256 public key (SEC1, 33 bytes).
pub type TraceKey = [u8; 33];

/// The address whose wallet key signed `digest`. `signature` is r ‖ s ‖ v, with v 27 or 28.
pub fn wallet_signer(digest: &Hash, signature: &[u8; 65]) -> Option<Address> {
    use k256::ecdsa::{RecoveryId, Signature, VerifyingKey};
    let prefixed = keccak256(&[b"\x19Ethereum Signed Message:\n32", digest]);
    let signature_rs = Signature::from_slice(&signature[..64]).ok()?;
    let recovery = RecoveryId::from_byte(signature[64].checked_sub(27)?)?;
    let key = VerifyingKey::recover_from_prehash(&prefixed, &signature_rs, recovery).ok()?;
    let point = key.to_encoded_point(false);
    let mut address = [0u8; 20];
    address.copy_from_slice(&keccak256(&[&point.as_bytes()[1..]])[12..]);
    Some(address)
}

/// Whether the trace key signed `digest`. `signature` is r ‖ s.
pub fn trace_key_signed(key: &TraceKey, digest: &Hash, signature: &[u8; 64]) -> bool {
    use p256::ecdsa::signature::Verifier;
    use p256::ecdsa::{Signature, VerifyingKey};
    let (Ok(key), Ok(signature)) = (
        VerifyingKey::from_sec1_bytes(key),
        Signature::from_slice(signature),
    ) else {
        return false;
    };
    key.verify(digest, &signature).is_ok()
}
