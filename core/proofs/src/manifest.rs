//! `manifest.json`: what a map pack holds and what it was built from. Its keccak-256 is the pack id that
//! `MapRegistry` records and every quote names.

use crate::{hex, io, Error};
use klippelgo_verify::{keccak256, Hash};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::io::Read;
use std::path::Path;

pub const FILE_NAME: &str = "manifest.json";
pub const FORMAT: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub format: u32,
    /// e.g. `br-pr-cascavel`
    pub region: String,
    /// Year and month, e.g. 202611.
    pub version: u32,
    pub osm: OsmSnapshot,
    /// Merkle root of the proof index.
    #[serde(with = "hex::array")]
    pub proof_root: Hash,
    /// Name and version or image digest of each tool the pack was built with.
    pub tools: BTreeMap<String, String>,
    /// Every other file of the pack, by its path inside it.
    pub files: BTreeMap<String, FileEntry>,
}

/// The OpenStreetMap data the pack was built from. Anyone can fetch the same file and rebuild the proof index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OsmSnapshot {
    /// Where the extract came from, e.g. a Geofabrik URL.
    pub source: String,
    /// The extract's replication sequence number.
    pub sequence: u64,
    /// SHA-256 of the `.osm.pbf` the proof index was built from.
    #[serde(with = "hex::array")]
    pub sha256: [u8; 32],
    /// West, south, east, north of the region cut from the source, in degrees × 10⁷; absent when the whole
    /// extract was used.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bbox: Option<[i32; 4]>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileEntry {
    #[serde(with = "hex::array")]
    pub sha256: [u8; 32],
    pub bytes: u64,
}

impl Manifest {
    /// Parses a manifest and returns it with the pack id, the keccak-256 of exactly these bytes.
    pub fn parse(bytes: &[u8]) -> Result<(Manifest, Hash), Error> {
        let manifest: Manifest =
            serde_json::from_slice(bytes).map_err(|e| Error::Manifest(e.to_string()))?;
        if manifest.format != FORMAT {
            return Err(Error::Manifest(format!(
                "format {} is not supported",
                manifest.format
            )));
        }
        if manifest.files.keys().any(|name| !is_plain(name)) {
            return Err(Error::Manifest("a file path leaves the pack".into()));
        }
        Ok((manifest, keccak256(&[bytes])))
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut bytes = serde_json::to_vec_pretty(self).expect("a manifest always serialises");
        bytes.push(b'\n');
        bytes
    }

    /// Lists and hashes every file under `dir` except the manifest itself.
    pub fn hash_files(dir: &Path) -> Result<BTreeMap<String, FileEntry>, Error> {
        let mut files = BTreeMap::new();
        let mut pending = vec![dir.to_path_buf()];
        while let Some(folder) = pending.pop() {
            for entry in std::fs::read_dir(&folder).map_err(io(&folder))? {
                let path = entry.map_err(io(&folder))?.path();
                if path.is_dir() {
                    pending.push(path);
                    continue;
                }
                let name = path
                    .strip_prefix(dir)
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/");
                if name != FILE_NAME {
                    files.insert(name, hash_file(&path)?);
                }
            }
        }
        Ok(files)
    }

    /// Checks one file of the pack against its entry.
    pub fn check_file(&self, dir: &Path, name: &str) -> Result<(), Error> {
        let expected = self
            .files
            .get(name)
            .ok_or_else(|| Error::MissingFile(name.into()))?;
        let path = dir.join(name);
        if !path.is_file() {
            return Err(Error::MissingFile(name.into()));
        }
        if hash_file(&path)? != *expected {
            return Err(Error::WrongFile(name.into()));
        }
        Ok(())
    }
}

/// A relative path with no `..`, so a manifest cannot point outside its pack.
fn is_plain(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('/')
        && name
            .split('/')
            .all(|part| !part.is_empty() && part != ".." && part != ".")
}

pub fn hash_file(path: &Path) -> Result<FileEntry, Error> {
    let mut file = std::fs::File::open(path).map_err(io(path))?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 1 << 20];
    let mut bytes = 0u64;
    loop {
        let n = file.read(&mut buffer).map_err(io(path))?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
        bytes += n as u64;
    }
    Ok(FileEntry {
        sha256: hasher.finalize().into(),
        bytes,
    })
}
