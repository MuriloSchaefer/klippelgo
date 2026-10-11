//! The file-system side of KlippelGo's trip proofs (docs/maps-and-routing.md).
//!
//! `klippelgo-verify` decides whether a quote or receipt holds, from bytes it is handed. This crate finds those
//! bytes: it opens a map pack and checks it against its manifest, cuts the witness a check needs out of the
//! pack's proof index, turns what Valhalla returns into the `(way, node)` steps the verifier understands, and
//! reads and writes the evidence of a ride as JSON. With the `search` feature it also builds and queries the
//! pack's address search.

pub mod evidence;
pub mod explain;
pub mod hex;
pub mod manifest;
pub mod pack;
#[cfg(feature = "search")]
pub mod search;
pub mod valhalla;

pub use manifest::Manifest;
pub use pack::MapPack;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}: {1}")]
    Io(String, std::io::Error),
    #[error("the manifest is not valid: {0}")]
    Manifest(String),
    #[error("{0} is missing from the pack")]
    MissingFile(String),
    #[error("{0} does not match the manifest")]
    WrongFile(String),
    #[error("the proof index is damaged: {0}")]
    ProofIndex(&'static str),
    #[error("the proof index hashes to another root than the manifest's")]
    WrongRoot,
    #[error("Valhalla's answer could not be read: {0}")]
    Valhalla(String),
    #[error("edge {edge} of Valhalla's answer does not follow way {way} of the map pack")]
    OffTheMap { edge: usize, way: u64 },
    #[error("way {0} is not in the map pack")]
    UnknownWay(u64),
    #[error("the evidence is not valid: {0}")]
    Evidence(String),
}

pub(crate) fn io(path: &std::path::Path) -> impl FnOnce(std::io::Error) -> Error + '_ {
    move |e| Error::Io(path.display().to_string(), e)
}
