//! Shared core for the KlippelGo native apps.
//!
//! The Swift (iOS) and Kotlin (Android) shells call into this crate through UniFFI. It will hold the Noise
//! channels, transaction building, credential handling and the encrypted database. So far it has the gossip
//! network phones match through, BRL amounts and the R$ 1,00 fee, geohash discovery cells, and the chain
//! settings for each network.

pub mod chain;
pub mod geo;
pub mod gossip;
pub mod money;

uniffi::setup_scaffolding!();
