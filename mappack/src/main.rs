//! Builds and checks the parts of a map pack that KlippelGo defines itself (docs/maps-and-routing.md).
//!
//!   klippelgo-mappack proof-index <extract.osm.pbf> <pack dir>     writes proof-index/, prints the proof root
//!   klippelgo-mappack root        <extract.osm.pbf>                prints the proof root and writes nothing
//!   klippelgo-mappack search      <extract.osm.pbf> <pack dir>     writes search.sqlite
//!   klippelgo-mappack manifest    <pack dir> key=value...          writes manifest.json, prints the pack id
//!   klippelgo-mappack check       <pack dir>                       checks every file against the manifest
//!
//! `root` is what a validator runs before attesting a pack in `MapRegistry`.

mod extract;
mod roads;

use klippelgo_proofs::manifest::{self, hash_file, Manifest, OsmSnapshot};
use klippelgo_proofs::pack::{INDEX_FILE, SEARCH_FILE, WAYS_FILE};
use klippelgo_proofs::{hex, search, MapPack};
use klippelgo_verify::index::ProofIndex;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

type Failure = Box<dyn std::error::Error>;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let result = match args.as_slice() {
        ["proof-index", pbf, dir] => proof_index(Path::new(pbf), Some(Path::new(dir))),
        ["root", pbf] => proof_index(Path::new(pbf), None),
        ["search", pbf, dir] => build_search(Path::new(pbf), Path::new(dir)),
        ["manifest", dir, fields @ ..] => write_manifest(Path::new(dir), fields),
        ["check", dir] => check(Path::new(dir)),
        _ => {
            eprintln!("Usage:\n  klippelgo-mappack proof-index <extract.osm.pbf> <pack dir>\n  klippelgo-mappack root <extract.osm.pbf>\n  klippelgo-mappack search <extract.osm.pbf> <pack dir>\n  klippelgo-mappack manifest <pack dir> region=<name> version=<yyyymm> osm-source=<url> osm-sequence=<n> osm=<extract.osm.pbf> [bbox=<w,s,e,n>] [tool:<name>=<version>]...\n  klippelgo-mappack check <pack dir>");
            return ExitCode::from(2);
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

fn proof_index(pbf: &Path, dir: Option<&Path>) -> Result<(), Failure> {
    let extract = extract::read(pbf, false)?;
    let index = ProofIndex::build(&extract.ways).ok_or("the extract lists a way twice")?;
    let nodes: usize = extract.ways.iter().map(|w| w.nodes.len()).sum();
    eprintln!(
        "{} ways, {} node references, {} chunks ({} ways cross the edge of the extract and are left out)",
        extract.ways.len(),
        nodes,
        index.chunks.len(),
        extract.incomplete
    );
    if let Some(dir) = dir {
        let ways = dir.join(WAYS_FILE);
        std::fs::create_dir_all(ways.parent().unwrap())?;
        std::fs::write(&ways, index.chunks.concat())?;
        std::fs::write(dir.join(INDEX_FILE), index.directory.encode())?;
    }
    println!("{}", hex::encode(&index.directory.root()));
    Ok(())
}

fn build_search(pbf: &Path, dir: &Path) -> Result<(), Failure> {
    let extract = extract::read(pbf, true)?;
    std::fs::create_dir_all(dir)?;
    let counts = search::build(&dir.join(SEARCH_FILE), extract.streets, extract.features)?;
    eprintln!(
        "{} streets, {} addresses, {} places, {} points of interest",
        counts.streets, counts.addresses, counts.places, counts.pois
    );
    Ok(())
}

fn write_manifest(dir: &Path, fields: &[&str]) -> Result<(), Failure> {
    let mut values = BTreeMap::new();
    let mut tools = BTreeMap::from([(
        "klippelgo-mappack".to_string(),
        env!("CARGO_PKG_VERSION").to_string(),
    )]);
    for field in fields {
        let (key, value) = field
            .split_once('=')
            .ok_or_else(|| format!("expected key=value, got {field}"))?;
        match key.strip_prefix("tool:") {
            Some(tool) => tools.insert(tool.to_string(), value.to_string()),
            None => values.insert(key, value.to_string()),
        };
    }
    let get = |key: &str| values.get(key).ok_or_else(|| format!("{key}= is required"));
    let bbox = match values.get("bbox") {
        Some(text) => {
            let degrees: Vec<f64> = text.split(',').map(str::parse).collect::<Result<_, _>>()?;
            let e7: Vec<i32> = degrees.iter().map(|d| (d * 1e7).round() as i32).collect();
            Some(<[i32; 4]>::try_from(e7).map_err(|_| "bbox= takes west,south,east,north")?)
        }
        None => None,
    };
    let index = std::fs::read(dir.join(INDEX_FILE))?;
    let directory = klippelgo_verify::index::Directory::decode(&index)
        .ok_or("proof-index/index.bin is damaged")?;
    let manifest = Manifest {
        format: manifest::FORMAT,
        region: get("region")?.clone(),
        version: get("version")?.parse()?,
        osm: OsmSnapshot {
            source: get("osm-source")?.clone(),
            sequence: get("osm-sequence")?.parse()?,
            sha256: hash_file(&PathBuf::from(get("osm")?))?.sha256,
            bbox,
        },
        proof_root: directory.root(),
        tools,
        files: Manifest::hash_files(dir)?,
    };
    let bytes = manifest.to_bytes();
    std::fs::write(dir.join(manifest::FILE_NAME), &bytes)?;
    let (_, id) = Manifest::parse(&bytes)?;
    println!("{}", hex::encode(&id));
    Ok(())
}

fn check(dir: &Path) -> Result<(), Failure> {
    let pack = MapPack::open(dir)?;
    pack.check_files()?;
    println!("region       {}", pack.manifest.region);
    println!("version      {}", pack.manifest.version);
    println!("osm sha256   {}", hex::encode(&pack.manifest.osm.sha256));
    println!("proof root   {}", hex::encode(&pack.manifest.proof_root));
    println!("pack id      {}", hex::encode(&pack.id));
    println!(
        "{} files match the manifest, {} proof-index chunks",
        pack.manifest.files.len(),
        pack.chunks()
    );
    Ok(())
}
