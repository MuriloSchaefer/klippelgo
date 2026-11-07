//! Two phones that only know a bootstrap node exchange messages through it.

use klippelgo_core::gossip::{GossipConfig, GossipError, GossipMessage, GossipNode};
use std::sync::Arc;
use std::time::{Duration, Instant};

const TOPIC: &str = "/klippelgo/match/9206bc18e70d7a15/6g9fp";

/// A ride's topic: the length of a real one, which is what counts when a router holds many.
fn ride_topic(ride: u32) -> String {
    format!("/klippelgo/ride/1/0x{ride:064x}")
}

fn phone(bootstrap: &str) -> Arc<GossipNode> {
    let config = GossipConfig {
        listen: None,
        bootstrap: vec![bootstrap.to_string()],
        router: false,
    };
    GossipNode::start(config).unwrap()
}

/// Publishes again until the message arrives, as the apps do: gossip keeps no history for late joiners.
fn deliver(from: &GossipNode, to: &GossipNode, data: &[u8]) -> GossipMessage {
    let deadline = Instant::now() + Duration::from_secs(20);
    while Instant::now() < deadline {
        from.publish(TOPIC.to_string(), data.to_vec()).unwrap();
        std::thread::sleep(Duration::from_millis(200));
        if let Some(message) = to.drain().into_iter().find(|m| m.data == data) {
            return message;
        }
    }
    panic!("message was not delivered");
}

#[test]
fn phones_reach_each_other_through_a_bootstrap_node() {
    let config = GossipConfig {
        listen: Some("/ip4/127.0.0.1/tcp/0".into()),
        bootstrap: vec![],
        router: true,
    };
    let router = GossipNode::start(config).unwrap();
    let address = router.listen_addresses().remove(0);

    let rider = phone(&address);
    let driver = phone(&address);
    rider.subscribe(TOPIC.to_string()).unwrap();
    driver.subscribe(TOPIC.to_string()).unwrap();

    let request = deliver(&rider, &driver, b"request");
    assert_eq!(request.topic, TOPIC);
    assert_eq!(request.from, rider.peer_id());

    let offer = deliver(&driver, &rider, b"offer");
    assert_eq!(offer.from, driver.peer_id());
    assert_eq!(router.connected_peers(), 2);
}

#[test]
fn rejects_foreign_topics_and_oversized_messages() {
    let node = GossipNode::start(GossipConfig {
        listen: None,
        bootstrap: vec![],
        router: false,
    })
    .unwrap();
    assert!(matches!(
        node.subscribe("/other/topic".into()),
        Err(GossipError::InvalidTopic)
    ));
    for bad in [
        "/klippelgo/match/9206bc18e70d7a15/6g9f",
        "/klippelgo/match/9206bc18e70d7a15/6g9fa",
        // The topic of before releases, and a release of the wrong length.
        "/klippelgo/match/1/6g9fp",
        "/klippelgo/match/9206bc18e70d7a/6g9fp",
        "/klippelgo/ride/1/0xabc",
        "/klippelgo/ride/1/0xABCDEF0000000000000000000000000000000000000000000000000000000000",
        &format!("/klippelgo/{}", "x".repeat(15_000)),
    ] {
        assert!(matches!(
            node.subscribe(bad.into()),
            Err(GossipError::InvalidTopic)
        ));
    }
    node.subscribe(ride_topic(1)).unwrap();
    assert!(matches!(
        node.publish(TOPIC.into(), vec![0; 70_000]),
        Err(GossipError::TooLarge)
    ));
    assert!(matches!(
        GossipNode::start(GossipConfig {
            listen: Some("nope".into()),
            bootstrap: vec![],
            router: false
        }),
        Err(GossipError::InvalidAddress(_))
    ));
}

#[test]
fn a_router_leaves_a_topic_once_nobody_is_on_it() {
    // Only this test starts a router that needs sweeping soon, and it does so after this line.
    std::env::set_var("KLIPPELGO_SWEEP_MS", "200");
    let router = GossipNode::start(GossipConfig {
        listen: Some("/ip4/127.0.0.1/tcp/0".into()),
        bootstrap: vec![],
        router: true,
    })
    .unwrap();
    let phone = GossipNode::start(GossipConfig {
        listen: None,
        bootstrap: router.listen_addresses(),
        router: false,
    })
    .unwrap();
    let topic = ride_topic(0);
    let until = |what: &str, check: &dyn Fn() -> bool| {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        while !check() {
            assert!(std::time::Instant::now() < deadline, "{what}");
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
    };
    until("the phone to connect", &|| phone.connected_peers() > 0);
    phone.subscribe(topic.clone()).unwrap();
    until("the router to join", &|| router.router_topics() == 1);
    phone.unsubscribe(topic).unwrap();
    until("the router to leave", &|| router.router_topics() == 0);
}

/// A router is on every ride's topic. A phone must go on hearing it past GossipSub's default of 100 per peer.
#[test]
fn a_phone_finds_a_ride_topic_on_a_router_holding_hundreds() {
    let router = GossipNode::start(GossipConfig {
        listen: Some("/ip4/127.0.0.1/tcp/0".into()),
        bootstrap: vec![],
        router: true,
    })
    .unwrap();
    let address = router.listen_addresses().remove(0);
    let (rider, driver) = (phone(&address), phone(&address));
    let topic = ride_topic(0);
    // Other rides: each phone of the crowd is held to 100 topics by the router, as a phone is.
    let crowd: Vec<_> = (0..4).map(|_| phone(&address)).collect();
    for (i, other) in crowd.iter().enumerate() {
        for k in 0..90 {
            other
                .subscribe(ride_topic(1 + 1000 * i as u32 + k))
                .unwrap();
        }
    }
    rider.subscribe(topic.clone()).unwrap();
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        // The driver publishes without joining, as a driver's phone does.
        let _ = driver.publish(topic.clone(), b"position".to_vec());
        std::thread::sleep(Duration::from_millis(200));
        if rider.drain().iter().any(|m| m.data == b"position") {
            break;
        }
        assert!(Instant::now() < deadline, "the position did not arrive");
    }
}

/// A phone hears of every topic the router holds in one message when it connects. It must be able to read that,
/// here more than 90 KB of it: reading it failed without a trace, and left the phone deaf and mute, still connected.
#[test]
fn a_phone_that_connects_to_a_busy_router_hears_and_is_heard() {
    let router = GossipNode::start(GossipConfig {
        listen: Some("/ip4/127.0.0.1/tcp/0".into()),
        bootstrap: vec![],
        router: true,
    })
    .unwrap();
    let address = router.listen_addresses().remove(0);
    let crowd: Vec<_> = (0..12).map(|_| phone(&address)).collect();
    for (i, other) in crowd.iter().enumerate() {
        for k in 0..90 {
            other
                .subscribe(ride_topic(1 + 1000 * i as u32 + k))
                .unwrap();
        }
    }
    std::thread::sleep(Duration::from_secs(4));

    let (rider, driver) = (phone(&address), phone(&address));
    let topic = ride_topic(0);
    rider.subscribe(topic.clone()).unwrap();
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let _ = driver.publish(topic.clone(), b"position".to_vec());
        std::thread::sleep(Duration::from_millis(200));
        if rider.drain().iter().any(|m| m.data == b"position") {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the phones do not hear each other"
        );
    }
}
