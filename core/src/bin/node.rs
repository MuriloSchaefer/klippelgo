//! Bootstrap node for the gossip network. Phones dial it to find each other, and it forwards gossip for every
//! cell its peers subscribe to. It sees only what any gossip peer sees: signed, coarse matching messages.
//!
//!   klippelgo-node [listen multiaddr]      default /ip4/0.0.0.0/tcp/4001

use klippelgo_core::gossip::{GossipConfig, GossipNode, DEFAULT_PORT};
use std::time::Duration;

fn main() {
    let listen = std::env::args()
        .nth(1)
        .unwrap_or_else(|| format!("/ip4/0.0.0.0/tcp/{DEFAULT_PORT}"));
    let node = match GossipNode::start(GossipConfig {
        listen: Some(listen),
        bootstrap: vec![],
        router: true,
    }) {
        Ok(node) => node,
        Err(e) => {
            eprintln!("Could not start the gossip node: {e}");
            std::process::exit(1);
        }
    };
    println!("KlippelGo gossip node {}", node.peer_id());
    for addr in node.listen_addresses() {
        println!("Listening on {addr}");
    }
    println!("Android emulators reach it at /ip4/10.0.2.2/tcp/{DEFAULT_PORT}");

    let mut peers = u32::MAX;
    loop {
        std::thread::sleep(Duration::from_secs(1));
        node.drain();
        let now = node.connected_peers();
        if now != peers {
            println!("{now} peer(s) connected");
            peers = now;
        }
    }
}
