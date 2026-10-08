//! Shared core for the KlippelGo native apps.
//!
//! The Swift (iOS) and Kotlin (Android) shells will call into this crate through UniFFI. It will hold libp2p,
//! the Noise channels, transaction building, credential handling and the encrypted database. This first cut
//! has the pieces that need no network: BRL amounts and the R$ 1,00 fee, geohash discovery cells, and the
//! chain settings for each network.

pub mod chain;
pub mod geo;
pub mod money;
