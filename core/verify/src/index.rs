//! The proof index: the canonical table of a map pack's road network that every proof rests on.
//!
//! One row per OpenStreetMap way that motor vehicles may use. Rows are sorted by way id and cut into chunks of
//! [`CHUNK_WAYS`]. Each chunk is hashed with keccak-256 and the chunk hashes form a Merkle tree whose root is the
//! pack's `proofRoot`. Siblings are hashed in sorted order, so OpenZeppelin's `MerkleProof.verify` accepts a
//! chunk's proof as is.
//!
//! All integers are big-endian.
//!
//! ```text
//! chunk = count u16, then `count` rows
//! row   = way id u64, flags u8, nodes u16, then `nodes` × (node id u64, lat i32, lon i32)
//! leaf  = keccak256(keccak256(chunk index u32 ‖ first way id u64 ‖ last way id u64 ‖ keccak256(chunk)))
//! ```

use crate::geo::Point;
use crate::path::Vehicle;
use crate::{keccak256, Error, Hash};
use alloc::collections::BTreeMap;
use alloc::vec::Vec;

/// Ways per chunk. Part of the format: changing it changes every proof root.
pub const CHUNK_WAYS: usize = 64;

/// Travel in the order the way lists its nodes, and against it, per vehicle class.
pub const CAR_FORWARD: u8 = 1;
pub const CAR_BACKWARD: u8 = 1 << 1;
pub const MOTORCYCLE_FORWARD: u8 = 1 << 2;
pub const MOTORCYCLE_BACKWARD: u8 = 1 << 3;
const KNOWN_FLAGS: u8 = 0x0f;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WayNode {
    pub id: u64,
    pub at: Point,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Way {
    pub id: u64,
    pub flags: u8,
    pub nodes: Vec<WayNode>,
}

impl Way {
    pub fn allows(&self, vehicle: Vehicle, forward: bool) -> bool {
        let flag = match (vehicle, forward) {
            (Vehicle::Car, true) => CAR_FORWARD,
            (Vehicle::Car, false) => CAR_BACKWARD,
            (Vehicle::Motorcycle, true) => MOTORCYCLE_FORWARD,
            (Vehicle::Motorcycle, false) => MOTORCYCLE_BACKWARD,
        };
        self.flags & flag != 0
    }
}

/// Encodes up to [`CHUNK_WAYS`] ways, which must be sorted by id.
pub fn encode_chunk(ways: &[Way]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(ways.len() as u16).to_be_bytes());
    for way in ways {
        out.extend_from_slice(&way.id.to_be_bytes());
        out.push(way.flags);
        out.extend_from_slice(&(way.nodes.len() as u16).to_be_bytes());
        for node in &way.nodes {
            out.extend_from_slice(&node.id.to_be_bytes());
            out.extend_from_slice(&node.at.lat.to_be_bytes());
            out.extend_from_slice(&node.at.lon.to_be_bytes());
        }
    }
    out
}

/// Decodes a chunk, accepting only the one byte string [`encode_chunk`] would produce for its ways.
pub fn decode_chunk(bytes: &[u8]) -> Option<Vec<Way>> {
    let mut r = Reader(bytes);
    let count = r.u16()? as usize;
    if count == 0 || count > CHUNK_WAYS {
        return None;
    }
    let mut ways: Vec<Way> = Vec::with_capacity(count);
    for _ in 0..count {
        let id = r.u64()?;
        let flags = r.u8()?;
        let n = r.u16()? as usize;
        if ways.last().is_some_and(|w| w.id >= id) || flags & !KNOWN_FLAGS != 0 || n < 2 {
            return None;
        }
        let mut nodes = Vec::with_capacity(n.min(r.0.len() / 16));
        for _ in 0..n {
            let id = r.u64()?;
            let (lat, lon) = (r.i32()?, r.i32()?);
            nodes.push(WayNode {
                id,
                at: Point { lat, lon },
            });
        }
        ways.push(Way { id, flags, nodes });
    }
    r.0.is_empty().then_some(ways)
}

struct Reader<'a>(&'a [u8]);

impl Reader<'_> {
    fn take<const N: usize>(&mut self) -> Option<[u8; N]> {
        let (head, rest) = self.0.split_first_chunk::<N>()?;
        self.0 = rest;
        Some(*head)
    }
    fn u8(&mut self) -> Option<u8> {
        self.take::<1>().map(|b| b[0])
    }
    fn u16(&mut self) -> Option<u16> {
        self.take().map(u16::from_be_bytes)
    }
    fn u64(&mut self) -> Option<u64> {
        self.take().map(u64::from_be_bytes)
    }
    fn i32(&mut self) -> Option<i32> {
        self.take().map(i32::from_be_bytes)
    }
}

/// The Merkle leaf for a chunk. It commits to the chunk's position and id range as well as its bytes, so a
/// proof also shows which ways the chunk could hold. Hashed twice, as OpenZeppelin's trees do, so a leaf can
/// never be passed off as an inner node.
pub fn leaf(index: u32, first_way: u64, last_way: u64, chunk: &[u8]) -> Hash {
    let inner = keccak256(&[
        &index.to_be_bytes(),
        &first_way.to_be_bytes(),
        &last_way.to_be_bytes(),
        &keccak256(&[chunk]),
    ]);
    keccak256(&[&inner])
}

fn pair(a: &Hash, b: &Hash) -> Hash {
    if a <= b {
        keccak256(&[a, b])
    } else {
        keccak256(&[b, a])
    }
}

/// Every level of the tree, leaves first. A node without a sibling moves up unchanged.
fn levels(leaves: &[Hash]) -> Vec<Vec<Hash>> {
    let mut levels = alloc::vec![leaves.to_vec()];
    while levels.last().unwrap().len() > 1 {
        let next = levels
            .last()
            .unwrap()
            .chunks(2)
            .map(|p| {
                if p.len() == 2 {
                    pair(&p[0], &p[1])
                } else {
                    p[0]
                }
            })
            .collect();
        levels.push(next);
    }
    levels
}

/// Root of the tree over `leaves`, in chunk order. An empty index has the zero root.
pub fn merkle_root(leaves: &[Hash]) -> Hash {
    if leaves.is_empty() {
        return [0; 32];
    }
    levels(leaves).pop().unwrap()[0]
}

/// The siblings from leaf `index` up to the root.
pub fn merkle_proof(leaves: &[Hash], index: usize) -> Vec<Hash> {
    let mut proof = Vec::new();
    let mut i = index;
    for level in levels(leaves) {
        if let Some(sibling) = level.get(i ^ 1) {
            proof.push(*sibling);
        }
        i /= 2;
    }
    proof
}

/// The same check as OpenZeppelin's `MerkleProof.verify`.
pub fn merkle_verify(proof: &[Hash], root: &Hash, leaf: &Hash) -> bool {
    proof
        .iter()
        .fold(*leaf, |node, sibling| pair(&node, sibling))
        == *root
}

/// One chunk of the proof index with the proof that it belongs to a pack.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChunkProof {
    pub index: u32,
    pub bytes: Vec<u8>,
    pub proof: Vec<Hash>,
}

/// The parts of a proof index that one check needs: the chunks holding the ways of a path.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Witness {
    pub chunks: Vec<ChunkProof>,
}

impl Witness {
    /// Checks every chunk against the pack's root and returns the ways they hold. Nothing downstream reads a
    /// way that did not pass through here.
    pub fn ways(&self, proof_root: &Hash) -> Result<BTreeMap<u64, Way>, Error> {
        let mut ways = BTreeMap::new();
        for chunk in &self.chunks {
            let rows =
                decode_chunk(&chunk.bytes).ok_or(Error::MalformedChunk { chunk: chunk.index })?;
            let (first, last) = (rows[0].id, rows[rows.len() - 1].id);
            if !merkle_verify(
                &chunk.proof,
                proof_root,
                &leaf(chunk.index, first, last, &chunk.bytes),
            ) {
                return Err(Error::ChunkNotInPack { chunk: chunk.index });
            }
            for way in rows {
                ways.insert(way.id, way);
            }
        }
        Ok(ways)
    }
}

/// Where each chunk sits in the pack's `proof-index/ways.bin`, and its Merkle leaf. Stored as
/// `proof-index/index.bin` so a phone can find a way's chunk and build its proof without reading the whole map.
///
/// ```text
/// "KGPI" ‖ version u32 = 1 ‖ chunks u32, then per chunk:
/// first way id u64 ‖ last way id u64 ‖ offset u64 ‖ length u32 ‖ leaf
/// ```
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Directory {
    pub entries: Vec<Entry>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry {
    pub first_way: u64,
    pub last_way: u64,
    pub offset: u64,
    pub length: u32,
    pub leaf: Hash,
}

const MAGIC: &[u8; 4] = b"KGPI";
const VERSION: u32 = 1;
const ENTRY_BYTES: usize = 60;

impl Directory {
    pub fn root(&self) -> Hash {
        merkle_root(&self.leaves())
    }

    fn leaves(&self) -> Vec<Hash> {
        self.entries.iter().map(|e| e.leaf).collect()
    }

    /// The chunk that holds `way`, if the map has it.
    pub fn locate(&self, way: u64) -> Option<usize> {
        let i = self.entries.partition_point(|e| e.last_way < way);
        (self.entries.get(i)?.first_way <= way).then_some(i)
    }

    /// Wraps a chunk read from `ways.bin` with its proof.
    pub fn prove(&self, chunk: usize, bytes: Vec<u8>) -> ChunkProof {
        ChunkProof {
            index: chunk as u32,
            bytes,
            proof: merkle_proof(&self.leaves(), chunk),
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(12 + self.entries.len() * ENTRY_BYTES);
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&VERSION.to_be_bytes());
        out.extend_from_slice(&(self.entries.len() as u32).to_be_bytes());
        for e in &self.entries {
            out.extend_from_slice(&e.first_way.to_be_bytes());
            out.extend_from_slice(&e.last_way.to_be_bytes());
            out.extend_from_slice(&e.offset.to_be_bytes());
            out.extend_from_slice(&e.length.to_be_bytes());
            out.extend_from_slice(&e.leaf);
        }
        out
    }

    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let mut r = Reader(bytes);
        if &r.take::<4>()? != MAGIC || u32::from_be_bytes(r.take()?) != VERSION {
            return None;
        }
        let count = u32::from_be_bytes(r.take()?) as usize;
        if r.0.len() != count.checked_mul(ENTRY_BYTES)? {
            return None;
        }
        let entries = (0..count)
            .map(|_| {
                Some(Entry {
                    first_way: r.u64()?,
                    last_way: r.u64()?,
                    offset: r.u64()?,
                    length: u32::from_be_bytes(r.take()?),
                    leaf: r.take()?,
                })
            })
            .collect::<Option<Vec<_>>>()?;
        let ordered = entries.windows(2).all(|p| p[0].last_way < p[1].first_way);
        (ordered && entries.iter().all(|e| e.first_way <= e.last_way))
            .then_some(Directory { entries })
    }
}

/// A whole proof index in memory: `ways.bin` as its chunks, and the directory over them.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProofIndex {
    pub chunks: Vec<Vec<u8>>,
    pub directory: Directory,
}

impl ProofIndex {
    /// Builds the index. `ways` must be sorted by id with no duplicates, and each way must have at least two
    /// nodes; otherwise there is no canonical index and this returns `None`.
    pub fn build(ways: &[Way]) -> Option<Self> {
        if ways.windows(2).any(|p| p[0].id >= p[1].id) || ways.iter().any(|w| w.nodes.len() < 2) {
            return None;
        }
        let mut index = ProofIndex::default();
        let mut offset = 0u64;
        for (i, rows) in ways.chunks(CHUNK_WAYS).enumerate() {
            let bytes = encode_chunk(rows);
            let (first_way, last_way) = (rows[0].id, rows[rows.len() - 1].id);
            index.directory.entries.push(Entry {
                first_way,
                last_way,
                offset,
                length: bytes.len() as u32,
                leaf: leaf(i as u32, first_way, last_way, &bytes),
            });
            offset += bytes.len() as u64;
            index.chunks.push(bytes);
        }
        Some(index)
    }

    /// The chunks holding the given ways, each with its proof. Ways the map does not have are left out, and
    /// the check that uses the witness reports them.
    pub fn witness(&self, ways: impl IntoIterator<Item = u64>) -> Witness {
        let mut chunks: Vec<usize> = ways
            .into_iter()
            .filter_map(|w| self.directory.locate(w))
            .collect();
        chunks.sort_unstable();
        chunks.dedup();
        Witness {
            chunks: chunks
                .into_iter()
                .map(|c| self.directory.prove(c, self.chunks[c].clone()))
                .collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::vec;

    fn way(id: u64) -> Way {
        let node = |n: u64| WayNode {
            id: id * 10 + n,
            at: Point {
                lat: -235_000_000 - n as i32,
                lon: -466_000_000 + id as i32,
            },
        };
        Way {
            id,
            flags: CAR_FORWARD | MOTORCYCLE_FORWARD,
            nodes: vec![node(0), node(1), node(2)],
        }
    }

    #[test]
    fn chunks_round_trip_and_reject_anything_else() {
        let ways = vec![way(3), way(7)];
        let bytes = encode_chunk(&ways);
        assert_eq!(decode_chunk(&bytes), Some(ways));

        let mut trailing = bytes.clone();
        trailing.push(0);
        assert_eq!(decode_chunk(&trailing), None);
        assert_eq!(decode_chunk(&bytes[..bytes.len() - 1]), None);
        assert_eq!(decode_chunk(&encode_chunk(&[way(7), way(3)])), None);
        assert_eq!(decode_chunk(&encode_chunk(&[])), None);
        let mut flagged = way(1);
        flagged.flags = 0x80;
        assert_eq!(decode_chunk(&encode_chunk(&[flagged])), None);
        let mut stub = way(1);
        stub.nodes.truncate(1);
        assert_eq!(decode_chunk(&encode_chunk(&[stub])), None);
    }

    #[test]
    fn every_leaf_proves_into_the_root() {
        for n in 1..=9u32 {
            let leaves: Vec<Hash> = (0..n)
                .map(|i| leaf(i, i as u64, i as u64, &[i as u8]))
                .collect();
            let root = merkle_root(&leaves);
            for (i, l) in leaves.iter().enumerate() {
                let proof = merkle_proof(&leaves, i);
                assert!(merkle_verify(&proof, &root, l), "{n} leaves, leaf {i}");
                assert!(!merkle_verify(&proof, &root, &leaf(99, 0, 0, &[])));
            }
        }
    }

    #[test]
    fn witness_rejects_a_chunk_from_another_map() {
        let chunks = [encode_chunk(&[way(1), way(2)]), encode_chunk(&[way(5)])];
        let leaves = [leaf(0, 1, 2, &chunks[0]), leaf(1, 5, 5, &chunks[1])];
        let root = merkle_root(&leaves);
        let mut witness = Witness {
            chunks: vec![ChunkProof {
                index: 1,
                bytes: chunks[1].clone(),
                proof: merkle_proof(&leaves, 1),
            }],
        };
        assert_eq!(
            witness
                .ways(&root)
                .unwrap()
                .keys()
                .copied()
                .collect::<Vec<_>>(),
            [5]
        );

        // The same street with a one-way flag flipped.
        let mut forged = way(5);
        forged.flags |= CAR_BACKWARD;
        witness.chunks[0].bytes = encode_chunk(&[forged]);
        assert_eq!(witness.ways(&root), Err(Error::ChunkNotInPack { chunk: 1 }));
        witness.chunks[0].bytes = vec![1, 2, 3];
        assert_eq!(witness.ways(&root), Err(Error::MalformedChunk { chunk: 1 }));
    }
}
