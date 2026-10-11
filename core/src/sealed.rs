//! The route, sealed for one driver.
//!
//! A request tells every driver in the cell how long the trip is and nothing about where. Once the rider has
//! picked an offer, the chosen driver needs the route itself: to check it on their own map before accepting, and
//! to drive it. The rider's phone seals the quote to a key the driver put in their offer, so it can travel over
//! gossip and only that driver can read it.
//!
//! This stands in for the encrypted ride channel until phones can talk directly. It is one message one way:
//! X25519 between a key made for this message and the driver's offer key, then ChaCha20-Poly1305.
//!
//! ```text
//! sealed = ephemeral public key (32 bytes) ‖ ciphertext with tag
//! key    = keccak256("KlippelGo sealed route v1" ‖ shared secret ‖ ephemeral public ‖ driver public ‖ quote hash)
//! ```

use crate::maps::MapError;
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use klippelgo_verify::keccak256;
use x25519_dalek::{PublicKey, StaticSecret};

const TAG: &[u8] = b"KlippelGo sealed route v1";

/// A key pair a driver makes for one offer. The public half goes in the offer; the secret never leaves the phone.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OfferKey {
    pub secret: Vec<u8>,
    pub public: Vec<u8>,
}

fn key32(bytes: &[u8]) -> Result<[u8; 32], MapError> {
    bytes
        .try_into()
        .map_err(|_| MapError::Invalid("a key or hash of 32 bytes was expected".into()))
}

fn random() -> Result<[u8; 32], MapError> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes)
        .map_err(|e| MapError::Invalid(format!("no randomness: {e}")))?;
    Ok(bytes)
}

fn cipher(
    shared: &[u8; 32],
    ephemeral: &PublicKey,
    driver: &PublicKey,
    quote_hash: &[u8; 32],
) -> ChaCha20Poly1305 {
    let key = keccak256(&[
        TAG,
        shared,
        ephemeral.as_bytes(),
        driver.as_bytes(),
        quote_hash,
    ]);
    ChaCha20Poly1305::new((&key).into())
}

/// Makes the key pair for one offer.
#[uniffi::export]
pub fn new_offer_key() -> Result<OfferKey, MapError> {
    let secret = StaticSecret::from(random()?);
    Ok(OfferKey {
        public: PublicKey::from(&secret).as_bytes().to_vec(),
        secret: secret.to_bytes().to_vec(),
    })
}

/// Seals a quote (the `quote` part of an evidence file) so that only the holder of the offer key can read it.
/// `quote_hash` is the hash that offer signed; the sealed route opens only for that quote.
#[uniffi::export]
pub fn seal_route(
    offer_public: Vec<u8>,
    quote_hash: Vec<u8>,
    quote_json: String,
) -> Result<Vec<u8>, MapError> {
    let driver = PublicKey::from(key32(&offer_public)?);
    let quote_hash = key32(&quote_hash)?;
    // A fresh key for every message, so the all-zero nonce is used once per key.
    let ephemeral = StaticSecret::from(random()?);
    let ephemeral_public = PublicKey::from(&ephemeral);
    let shared = ephemeral.diffie_hellman(&driver);
    let sealed = cipher(shared.as_bytes(), &ephemeral_public, &driver, &quote_hash)
        .encrypt(
            &Nonce::default(),
            Payload {
                msg: quote_json.as_bytes(),
                aad: &quote_hash,
            },
        )
        .map_err(|_| MapError::Invalid("the route could not be sealed".into()))?;
    Ok([ephemeral_public.as_bytes().as_slice(), &sealed].concat())
}

/// Opens a route sealed to this offer key. Fails for anything not sealed to this key for this quote. What
/// comes out is still only the rider's claim: check it with `MapPack::check_quote` before believing it.
#[uniffi::export]
pub fn open_route(
    offer_secret: Vec<u8>,
    quote_hash: Vec<u8>,
    sealed: Vec<u8>,
) -> Result<String, MapError> {
    let refused = || MapError::Invalid("this route was not sealed for this offer".into());
    let secret = StaticSecret::from(key32(&offer_secret)?);
    let quote_hash = key32(&quote_hash)?;
    let (ephemeral, ciphertext) = sealed.split_first_chunk::<32>().ok_or_else(refused)?;
    let ephemeral = PublicKey::from(*ephemeral);
    let shared = secret.diffie_hellman(&ephemeral);
    let plain = cipher(
        shared.as_bytes(),
        &ephemeral,
        &PublicKey::from(&secret),
        &quote_hash,
    )
    .decrypt(
        &Nonce::default(),
        Payload {
            msg: ciphertext,
            aad: &quote_hash,
        },
    )
    .map_err(|_| refused())?;
    String::from_utf8(plain).map_err(|_| refused())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_offer_key_opens_the_route_and_only_for_its_quote() {
        let driver = new_offer_key().unwrap();
        let other = new_offer_key().unwrap();
        let quote = r#"{"pickup":[-249555000,-534552000]}"#.to_string();
        let hash = vec![7u8; 32];
        let sealed = seal_route(driver.public.clone(), hash.clone(), quote.clone()).unwrap();
        assert!(!sealed.windows(6).any(|w| w == b"pickup"));
        assert_eq!(
            open_route(driver.secret.clone(), hash.clone(), sealed.clone()).unwrap(),
            quote
        );

        assert!(open_route(other.secret, hash.clone(), sealed.clone()).is_err());
        assert!(open_route(driver.secret.clone(), vec![8u8; 32], sealed.clone()).is_err());
        let mut tampered = sealed.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(open_route(driver.secret.clone(), hash.clone(), tampered).is_err());
        assert!(open_route(driver.secret.clone(), hash.clone(), sealed[..20].to_vec()).is_err());
        // Sealing twice gives different bytes: nothing links two messages about the same route.
        assert_ne!(seal_route(driver.public, hash, quote).unwrap(), sealed);
    }
}
