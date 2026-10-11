//! Two phones that only know a bootstrap node exchange messages through it.

use klippelgo_core::gossip::{GossipConfig, GossipError, GossipMessage, GossipNode};
use std::sync::Arc;
use std::time::{Duration, Instant};

const TOPIC: &str = "/klippelgo/match/1/6g9fp";

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
    assert!(matches!(
        node.publish(TOPIC.into(), vec![0; 5000]),
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
