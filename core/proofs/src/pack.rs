//! A map pack on disk.
//!
//! ```text
//! manifest.json            what is in the pack, and its proof root
//! proof-index/ways.bin     the proof index's chunks, one after another
//! proof-index/index.bin    where each chunk is, and its Merkle leaf
//! basemap.pmtiles          vector tiles (MapLibre)
//! valhalla_tiles.tar       routing graph (Valhalla)
//! search.sqlite            addresses and places
//! ```

use crate::manifest::{self, Manifest};
use crate::{io, Error};
use klippelgo_verify::index::{decode_chunk, leaf, Directory, Witness};
use klippelgo_verify::{Hash, Pack, Way};
use std::collections::BTreeSet;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

pub const WAYS_FILE: &str = "proof-index/ways.bin";
pub const INDEX_FILE: &str = "proof-index/index.bin";
pub const BASEMAP_FILE: &str = "basemap.pmtiles";
pub const ROUTING_FILE: &str = "valhalla_tiles.tar";
pub const SEARCH_FILE: &str = "search.sqlite";

#[derive(Debug)]
pub struct MapPack {
    dir: PathBuf,
    pub manifest: Manifest,
    /// keccak-256 of `manifest.json`: the id `MapRegistry` knows the pack by.
    pub id: Hash,
    directory: Directory,
}

impl MapPack {
    /// Opens a pack and checks the proof index's directory against the manifest's root. Chunks are checked as
    /// they are read. Whether the manifest itself is one governance activated is for the caller to ask
    /// `MapRegistry`, with [`MapPack::id`].
    pub fn open(dir: impl Into<PathBuf>) -> Result<Self, Error> {
        let dir = dir.into();
        let manifest_path = dir.join(manifest::FILE_NAME);
        let (manifest, id) =
            Manifest::parse(&std::fs::read(&manifest_path).map_err(io(&manifest_path))?)?;
        let index_path = dir.join(INDEX_FILE);
        let directory = Directory::decode(&std::fs::read(&index_path).map_err(io(&index_path))?)
            .ok_or(Error::ProofIndex("index.bin is not a chunk directory"))?;
        if directory.root() != manifest.proof_root {
            return Err(Error::WrongRoot);
        }
        Ok(MapPack {
            dir,
            manifest,
            id,
            directory,
        })
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn file(&self, name: &str) -> PathBuf {
        self.dir.join(name)
    }

    /// The pack as the verifier names it.
    pub fn pack(&self) -> Pack {
        Pack {
            id: self.id,
            proof_root: self.manifest.proof_root,
        }
    }

    pub fn chunks(&self) -> usize {
        self.directory.entries.len()
    }

    /// Hashes every file the manifest lists. Slow on a full pack: run it once after a download.
    pub fn check_files(&self) -> Result<(), Error> {
        self.manifest
            .files
            .keys()
            .try_for_each(|name| self.manifest.check_file(&self.dir, name))
    }

    /// The chunks holding the given ways, each with its Merkle proof. Ways the pack does not have are left
    /// out; the verifier reports them.
    pub fn witness(&self, ways: impl IntoIterator<Item = u64>) -> Result<Witness, Error> {
        let chunks: BTreeSet<usize> = ways
            .into_iter()
            .filter_map(|w| self.directory.locate(w))
            .collect();
        let mut file = self.ways_file()?;
        let mut witness = Witness::default();
        for chunk in chunks {
            let bytes = self.read_chunk(&mut file, chunk)?;
            witness.chunks.push(self.directory.prove(chunk, bytes));
        }
        Ok(witness)
    }

    /// One way of the map, or `None` when the pack does not have it.
    pub fn way(&self, id: u64) -> Result<Option<Way>, Error> {
        let Some(chunk) = self.directory.locate(id) else {
            return Ok(None);
        };
        let bytes = self.read_chunk(&mut self.ways_file()?, chunk)?;
        let ways =
            decode_chunk(&bytes).ok_or(Error::ProofIndex("a chunk is not in canonical form"))?;
        Ok(ways.into_iter().find(|w| w.id == id))
    }

    fn ways_file(&self) -> Result<File, Error> {
        let path = self.dir.join(WAYS_FILE);
        File::open(&path).map_err(io(&path))
    }

    fn read_chunk(&self, file: &mut File, chunk: usize) -> Result<Vec<u8>, Error> {
        let entry = &self.directory.entries[chunk];
        let path = self.dir.join(WAYS_FILE);
        let mut bytes = vec![0u8; entry.length as usize];
        file.seek(SeekFrom::Start(entry.offset))
            .map_err(io(&path))?;
        file.read_exact(&mut bytes).map_err(io(&path))?;
        if leaf(chunk as u32, entry.first_way, entry.last_way, &bytes) != entry.leaf {
            return Err(Error::ProofIndex("a chunk does not match its leaf"));
        }
        Ok(bytes)
    }
}
