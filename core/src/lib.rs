//! Shared core for the KlippelGo native apps.
//!
//! The Swift (iOS) and Kotlin (Android) shells call into this crate through UniFFI. It will hold the Noise
//! channels, transaction building, credential handling and the encrypted database. So far it has the gossip
//! network phones match through, map packs with on-device search, routes and trip proofs, BRL amounts and the
//! R$ 1,00 fee, geohash discovery cells, and the chain settings for each network.
//!
//! The proofs themselves live in two crates of this workspace: `verify` (the deterministic checker) and
//! `proofs` (map packs on disk, Valhalla results, evidence files).

pub mod chain;
pub mod geo;
pub mod gossip;
pub mod maps;
pub mod money;
pub mod sealed;

uniffi::setup_scaffolding!();
