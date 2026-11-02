//! A router at its limit. Its own file, for a process of its own, as it runs hundreds of phones and every join goes
//! to all of them. It takes a few minutes, a few gigabytes and a release build: `cargo test --release --test gossip_full -- --ignored`.

use klippelgo_core::gossip::{GossipConfig, GossipNode};
use std::sync::Arc;
use std::time::{Duration, Instant};

const MAX_ROUTER_TOPICS: u32 = 1 << 15;

fn ride_topic(ride: u32) -> String {
    format!("/klippelgo/ride/1/0x{ride:064x}")
}

fn phone(bootstrap: &str) -> Arc<GossipNode> {
    GossipNode::start(GossipConfig {
        listen: None,
        bootstrap: vec![bootstrap.to_string()],
        router: false,
    })
    .unwrap()
}

/// A peer that leaves each hundred topics and asks for the next cannot make the router join without end.
#[test]
fn a_router_joins_a_hundred_topics_per_peer_between_sweeps() {
    let router = GossipNode::start(GossipConfig {
        listen: Some("/ip4/127.0.0.1/tcp/0".into()),
        bootstrap: vec![],
        router: true,
    })
    .unwrap();
    let stranger = phone(&router.listen_addresses().remove(0));
    while stranger.connected_peers() == 0 {
        std::thread::sleep(Duration::from_millis(50));
    }
    for round in 0..4 {
        let topics: Vec<_> = (0..100).map(|k| ride_topic(round * 100 + k)).collect();
        for topic in &topics {
            stranger.subscribe(topic.clone()).unwrap();
        }
        std::thread::sleep(Duration::from_millis(500));
        for topic in topics {
            stranger.unsubscribe(topic).unwrap();
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    assert_eq!(router.router_topics(), 100);
}

/// Strangers on every topic a router will hold: it stops there, a phone that connects then reads all of it, and a
/// ride refused for lack of room gets its topic when room appears.
#[test]
#[ignore = "hundreds of phones, in a release build"]
fn a_full_router_stops_joining_and_a_new_phone_still_reads_it_all() {
    std::env::set_var("KLIPPELGO_SWEEP_MS", "2000");
    let router = GossipNode::start(GossipConfig {
        listen: Some("/ip4/127.0.0.1/tcp/0".into()),
        bootstrap: vec![],
        router: true,
    })
    .unwrap();
    let address = router.listen_addresses().remove(0);
    let until = |what: &str, seconds: u64, check: &dyn Fn() -> bool| {
        let deadline = Instant::now() + Duration::from_secs(seconds);
        while !check() {
            assert!(Instant::now() < deadline, "{what}");
            std::thread::sleep(Duration::from_millis(250));
        }
    };
    // One phone per hundred topics, as the router allows, and a few past what it holds.
    let mut crowd = Vec::new();
    for i in 0..(MAX_ROUTER_TOPICS / 100 + 2) {
        let stranger = phone(&address);
        until("a stranger to connect", 600, &|| {
            stranger.connected_peers() > 0
        });
        for k in 0..100 {
            stranger.subscribe(ride_topic(1 + i * 100 + k)).unwrap();
        }
        crowd.push(stranger);
        std::thread::sleep(Duration::from_millis(50));
    }
    until("the router to fill", 1200, &|| {
        router.router_topics() == MAX_ROUTER_TOPICS
    });
    std::thread::sleep(Duration::from_secs(5));
    assert_eq!(router.router_topics(), MAX_ROUTER_TOPICS);

    // The ride has no room: a stranger leaving makes some.
    let (rider, driver) = (phone(&address), phone(&address));
    let topic = ride_topic(0);
    until("the phones to connect", 600, &|| {
        rider.connected_peers() > 0 && driver.connected_peers() > 0
    });
    rider.subscribe(topic.clone()).unwrap();
    std::thread::sleep(Duration::from_secs(3));
    crowd.truncate(crowd.len() - 10);
    let deadline = Instant::now() + Duration::from_secs(300);
    loop {
        let _ = driver.publish(topic.clone(), b"position".to_vec());
        std::thread::sleep(Duration::from_millis(250));
        if rider.drain().iter().any(|m| m.data == b"position") {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the phones do not hear each other"
        );
    }
}
