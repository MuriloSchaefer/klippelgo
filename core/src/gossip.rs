//! The gossip network phones match through: libp2p GossipSub v1.1 over TCP, Noise and Yamux.
//!
//! This module moves bytes between peers on topics and knows nothing about rides. What a message means, who may
//! send it and whether its account signature is valid is decided by the caller, which must treat every message
//! as untrusted: anyone can join a topic and publish to it.
//!
//! A node is either a phone, which dials bootstrap nodes and subscribes to the cells around it, or a router,
//! which listens for phones and forwards every KlippelGo topic they subscribe to.

use futures::StreamExt;
use libp2p::gossipsub::{
    self, DataTransform, IdentTopic, IdentityTransform, MaxCountSubscriptionFilter, Message,
    MessageAuthenticity, RawMessage, TopicHash, TopicSubscriptionFilter, ValidationMode,
};
use libp2p::swarm::{NetworkBehaviour, SwarmEvent};
use libp2p::{noise, ping, tcp, yamux, Multiaddr, Swarm, SwarmBuilder};
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{mpsc as std_mpsc, Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;

/// Port the bootstrap node listens on by default.
pub const DEFAULT_PORT: u16 = 4001;

/// A cell's topic is this, the release (16 lowercase hex characters: the first eight bytes of the hash of the
/// release's code contracts), a slash and five geohash characters. Phones on releases with other code contracts
/// are on other topics, so they never match through an escrow one of them does not use
/// (docs/releases-and-data.md).
const MATCH_TOPIC_PREFIX: &str = "/klippelgo/match/";

/// Characters in a release as a match topic carries it.
const RELEASE_HEX_LEN: usize = 16;

/// A ride's topic is this and its quote hash in lowercase hex.
const RIDE_TOPIC_PREFIX: &str = "/klippelgo/ride/1/0x";

/// Largest message a peer accepts. Matching messages are a few hundred bytes; a route sealed for the chosen
/// driver is a few kilobytes, and tens for a trip across a large city.
const MAX_MESSAGE_BYTES: usize = 64 * 1024;

/// Messages kept for the caller between two `drain` calls. Older ones are dropped first.
const MAX_INBOX: usize = 4096;

const REDIAL_EVERY: Duration = Duration::from_secs(3);

/// Topics a router joins, all told, and so topics a phone believes it is on. More than the 17 000 rides the busiest
/// hour has under way at the target. Whoever connects can take up to 100 of them (K12).
const MAX_ROUTER_TOPICS: usize = 1 << 15;

/// Topics one peer may make a router join between two sweeps. Without it a peer could leave each hundred it joined
/// and ask for another, and fill the router, and every phone's data, over a single connection. What a phone is really
/// on still gets joined, at the sweep.
const MAX_JOINS_PER_SWEEP: u32 = 100;

/// What a phone accepts in one exchange with its router, which says every topic it is on when the phone connects, in
/// one go. A topic there takes 84 bytes and a few of framing.
const MAX_HELLO_BYTES: usize = MAX_ROUTER_TOPICS * 96;

/// How often a router leaves the topics nobody is on. A ride has a topic of its own, so they come and go all day.
const SWEEP_EVERY: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, uniffi::Record)]
pub struct GossipConfig {
    /// Multiaddr to listen on, e.g. `/ip4/0.0.0.0/tcp/4001`. Phones leave it empty: they only dial out.
    pub listen: Option<String>,
    /// Multiaddrs of bootstrap nodes to stay connected to, e.g. `/ip4/10.0.2.2/tcp/4001`.
    pub bootstrap: Vec<String>,
    /// Forward every KlippelGo topic that a connected peer subscribes to.
    pub router: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GossipMessage {
    pub topic: String,
    pub data: Vec<u8>,
    /// Peer id of the node that published it. This is a network identity, not an account.
    pub from: String,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum GossipError {
    #[error("invalid address {0}")]
    InvalidAddress(String),
    #[error("could not start the node: {0}")]
    Start(String),
    #[error("message is larger than {MAX_MESSAGE_BYTES} bytes")]
    TooLarge,
    #[error("topic is neither a cell's nor a ride's")]
    InvalidTopic,
    #[error("the node has stopped")]
    Stopped,
}

enum Command {
    Subscribe(String),
    Unsubscribe(String),
    Publish(String, Vec<u8>),
    Stop,
}

#[derive(NetworkBehaviour)]
struct Behaviour {
    gossipsub: gossipsub::Behaviour<BoundedMessages, MaxCountSubscriptionFilter<KlippelGoTopics>>,
    ping: ping::Behaviour,
}

#[derive(Default)]
struct Shared {
    inbox: Mutex<VecDeque<GossipMessage>>,
    peers: AtomicU32,
    topics: AtomicU32,
}

#[derive(uniffi::Object)]
pub struct GossipNode {
    peer_id: String,
    listen: Vec<String>,
    commands: mpsc::UnboundedSender<Command>,
    shared: Arc<Shared>,
}

#[uniffi::export]
impl GossipNode {
    /// Starts the node on its own thread and returns once it is listening (when `listen` is set).
    #[uniffi::constructor]
    pub fn start(config: GossipConfig) -> Result<Arc<Self>, GossipError> {
        let listen = config.listen.as_deref().map(parse_addr).transpose()?;
        let bootstrap = config
            .bootstrap
            .iter()
            .map(|a| parse_addr(a))
            .collect::<Result<Vec<_>, _>>()?;

        let shared = Arc::new(Shared::default());
        let (commands, command_rx) = mpsc::unbounded_channel();
        let (ready_tx, ready_rx) = std_mpsc::channel();
        let task_shared = shared.clone();
        std::thread::Builder::new()
            .name("klippelgo-gossip".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(runtime) => runtime,
                    Err(e) => {
                        let _ = ready_tx.send(Err(GossipError::Start(e.to_string())));
                        return;
                    }
                };
                runtime.block_on(run(
                    listen,
                    bootstrap,
                    config.router,
                    command_rx,
                    task_shared,
                    ready_tx,
                ));
            })
            .map_err(|e| GossipError::Start(e.to_string()))?;

        let (peer_id, listen) = ready_rx.recv().map_err(|_| GossipError::Stopped)??;
        Ok(Arc::new(Self {
            peer_id,
            listen,
            commands,
            shared,
        }))
    }

    pub fn peer_id(&self) -> String {
        self.peer_id.clone()
    }

    /// Addresses other nodes can dial, with the port the system picked when `listen` asked for port 0.
    pub fn listen_addresses(&self) -> Vec<String> {
        self.listen.clone()
    }

    pub fn connected_peers(&self) -> u32 {
        self.shared.peers.load(Ordering::Relaxed)
    }

    /// Topics a router holds.
    pub fn router_topics(&self) -> u32 {
        self.shared.topics.load(Ordering::Relaxed)
    }

    pub fn subscribe(&self, topic: String) -> Result<(), GossipError> {
        check_topic(&topic)?;
        self.send(Command::Subscribe(topic))
    }

    pub fn unsubscribe(&self, topic: String) -> Result<(), GossipError> {
        self.send(Command::Unsubscribe(topic))
    }

    /// Sends to the peers on the topic right now. Gossip keeps no history, so a peer that joins later misses
    /// it: publish again for as long as the message matters.
    pub fn publish(&self, topic: String, data: Vec<u8>) -> Result<(), GossipError> {
        check_topic(&topic)?;
        if data.len() > MAX_MESSAGE_BYTES {
            return Err(GossipError::TooLarge);
        }
        self.send(Command::Publish(topic, data))
    }

    /// Messages received since the last call, oldest first.
    pub fn drain(&self) -> Vec<GossipMessage> {
        self.shared.inbox.lock().unwrap().drain(..).collect()
    }

    pub fn stop(&self) {
        let _ = self.commands.send(Command::Stop);
    }
}

impl GossipNode {
    fn send(&self, command: Command) -> Result<(), GossipError> {
        self.commands
            .send(command)
            .map_err(|_| GossipError::Stopped)
    }
}

impl Drop for GossipNode {
    fn drop(&mut self) {
        self.stop();
    }
}

/// [SWEEP_EVERY], or what `KLIPPELGO_SWEEP_MS` says, for the tests.
fn sweep_every() -> Duration {
    std::env::var("KLIPPELGO_SWEEP_MS")
        .ok()
        .and_then(|ms| ms.parse().ok())
        .map(Duration::from_millis)
        .unwrap_or(SWEEP_EVERY)
}

fn parse_addr(addr: &str) -> Result<Multiaddr, GossipError> {
    addr.parse()
        .map_err(|_| GossipError::InvalidAddress(addr.to_string()))
}

fn check_topic(topic: &str) -> Result<(), GossipError> {
    if is_topic(topic) {
        Ok(())
    } else {
        Err(GossipError::InvalidTopic)
    }
}

/// Whether this is a topic KlippelGo uses. The shapes are exact, so a stranger cannot pick how long a name every
/// phone keeps in memory is.
fn is_topic(topic: &str) -> bool {
    if let Some(rest) = topic.strip_prefix(MATCH_TOPIC_PREFIX) {
        let Some((release, cell)) = rest.split_once('/') else {
            return false;
        };
        return release.len() == RELEASE_HEX_LEN
            && release.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
            && cell.len() == 5
            && cell
                .chars()
                .all(|c| "0123456789bcdefghjkmnpqrstuvwxyz".contains(c));
    }
    topic.strip_prefix(RIDE_TOPIC_PREFIX).is_some_and(|hash| {
        hash.len() == 64 && hash.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
    })
}

#[derive(Default, Clone)]
struct KlippelGoTopics;

impl TopicSubscriptionFilter for KlippelGoTopics {
    fn can_subscribe(&mut self, topic: &TopicHash) -> bool {
        is_topic(topic.as_str())
    }
}

/// Drops a message over [MAX_MESSAGE_BYTES] before it is believed or forwarded. A phone's transport limit has to be
/// as large as the hello, so it cannot be what bounds a message.
#[derive(Default, Clone)]
struct BoundedMessages;

impl DataTransform for BoundedMessages {
    fn inbound_transform(&self, raw: RawMessage) -> Result<Message, std::io::Error> {
        if raw.data.len() > MAX_MESSAGE_BYTES {
            return Err(std::io::ErrorKind::InvalidData.into());
        }
        IdentityTransform.inbound_transform(raw)
    }

    fn outbound_transform(
        &self,
        topic: &TopicHash,
        data: Vec<u8>,
    ) -> Result<Vec<u8>, std::io::Error> {
        IdentityTransform.outbound_transform(topic, data)
    }
}

fn build_swarm(router: bool) -> Result<Swarm<Behaviour>, Box<dyn std::error::Error + Send + Sync>> {
    let swarm = SwarmBuilder::with_new_identity()
        .with_tokio()
        .with_tcp(
            tcp::Config::default().nodelay(true),
            noise::Config::new,
            yamux::Config::default,
        )?
        .with_behaviour(|key| {
            // Strict mode: every message carries its publisher's peer signature and a sequence number, and
            // peers drop anything unsigned or oversized before it reaches the caller.
            let mut config = gossipsub::ConfigBuilder::default();
            config
                .heartbeat_interval(Duration::from_secs(1))
                .validation_mode(ValidationMode::Strict)
                .max_transmit_size(MAX_MESSAGE_BYTES + 512);
            // How many topics a peer may say it is on. A router is on every ride's topic, so a phone believes it up
            // to all a router can hold, and reads it all in one go: past either limit it would hear nothing from
            // the router, and show no sign of it. A router holds a phone to the default, 100.
            let mut filter = MaxCountSubscriptionFilter {
                filter: KlippelGoTopics,
                max_subscribed_topics: 100,
                max_subscriptions_per_request: 100,
            };
            if !router {
                config
                    .max_transmit_size(MAX_HELLO_BYTES)
                    .max_control_message_size(MAX_HELLO_BYTES);
                filter.max_subscribed_topics = MAX_ROUTER_TOPICS;
                filter.max_subscriptions_per_request = MAX_ROUTER_TOPICS;
            }
            let config = config.build()?;
            Ok(Behaviour {
                gossipsub: gossipsub::Behaviour::new_with_subscription_filter_and_transform(
                    MessageAuthenticity::Signed(key.clone()),
                    config,
                    filter,
                    BoundedMessages,
                )?,
                ping: ping::Behaviour::new(
                    ping::Config::new().with_interval(Duration::from_secs(10)),
                ),
            })
        })?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(60)))
        .build();
    Ok(swarm)
}

type Ready = std_mpsc::Sender<Result<(String, Vec<String>), GossipError>>;

async fn run(
    listen: Option<Multiaddr>,
    bootstrap: Vec<Multiaddr>,
    router: bool,
    mut commands: mpsc::UnboundedReceiver<Command>,
    shared: Arc<Shared>,
    ready: Ready,
) {
    let mut swarm = match build_swarm(router) {
        Ok(swarm) => swarm,
        Err(e) => {
            let _ = ready.send(Err(GossipError::Start(e.to_string())));
            return;
        }
    };
    let peer_id = swarm.local_peer_id().to_string();
    let mut ready = Some(ready);
    match listen {
        Some(addr) => {
            if let Err(e) = swarm.listen_on(addr) {
                let _ = ready
                    .take()
                    .unwrap()
                    .send(Err(GossipError::Start(e.to_string())));
                return;
            }
        }
        None => {
            let _ = ready.take().unwrap().send(Ok((peer_id.clone(), vec![])));
        }
    }

    let mut redial = tokio::time::interval(REDIAL_EVERY);
    let mut sweep = tokio::time::interval(sweep_every());
    let mut joined = 0;
    let mut joins: HashMap<libp2p::PeerId, u32> = HashMap::new();
    loop {
        tokio::select! {
            command = commands.recv() => match command {
                Some(Command::Subscribe(topic)) => {
                    let _ = swarm.behaviour_mut().gossipsub.subscribe(&IdentTopic::new(topic));
                }
                Some(Command::Unsubscribe(topic)) => {
                    let _ = swarm.behaviour_mut().gossipsub.unsubscribe(&IdentTopic::new(topic));
                }
                // Fails when no peer is on the topic yet. The caller publishes again, so nothing is queued.
                Some(Command::Publish(topic, data)) => {
                    let _ = swarm.behaviour_mut().gossipsub.publish(IdentTopic::new(topic), data);
                }
                Some(Command::Stop) | None => return,
            },
            // Keeps phones attached to their bootstrap nodes across restarts and network changes.
            _ = redial.tick() => {
                if swarm.connected_peers().next().is_none() {
                    for addr in &bootstrap {
                        let _ = swarm.dial(addr.clone());
                    }
                }
            }
            _ = sweep.tick(), if router => {
                let gossipsub = &mut swarm.behaviour_mut().gossipsub;
                let held: HashSet<_> = gossipsub.topics().cloned().collect();
                let wanted: HashSet<_> = gossipsub.all_peers().flat_map(|(_, topics)| topics).cloned().collect();
                for topic in held.iter().filter(|t| !wanted.contains(t)) {
                    let _ = gossipsub.unsubscribe(&IdentTopic::new(topic.as_str()));
                }
                // The topics it had no room for when a phone asked: nobody asks twice.
                let room = MAX_ROUTER_TOPICS.saturating_sub(gossipsub.topics().count());
                for topic in wanted.iter().filter(|t| !held.contains(t)).take(room) {
                    let _ = gossipsub.subscribe(&IdentTopic::new(topic.as_str()));
                }
                joined = gossipsub.topics().count();
                joins.clear();
                shared.topics.store(joined as u32, Ordering::Relaxed);
            }
            event = swarm.select_next_some() => match event {
                SwarmEvent::NewListenAddr { address, .. } => {
                    if let Some(ready) = ready.take() {
                        let _ = ready.send(Ok((peer_id.clone(), vec![address.to_string()])));
                    }
                }
                SwarmEvent::ConnectionEstablished { .. } | SwarmEvent::ConnectionClosed { .. } => {
                    shared.peers.store(swarm.connected_peers().count() as u32, Ordering::Relaxed);
                }
                SwarmEvent::Behaviour(BehaviourEvent::Gossipsub(gossipsub::Event::Subscribed { peer_id, topic })) => {
                    let asked = joins.entry(peer_id).or_default();
                    if router && joined < MAX_ROUTER_TOPICS && *asked < MAX_JOINS_PER_SWEEP {
                        *asked += 1;
                        let topic = IdentTopic::new(topic.as_str());
                        if matches!(swarm.behaviour_mut().gossipsub.subscribe(&topic), Ok(true)) {
                            joined += 1;
                            shared.topics.store(joined as u32, Ordering::Relaxed);
                        }
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Gossipsub(gossipsub::Event::Message { message, .. })) => {
                    let mut inbox = shared.inbox.lock().unwrap();
                    if inbox.len() == MAX_INBOX {
                        inbox.pop_front();
                    }
                    inbox.push_back(GossipMessage {
                        topic: message.topic.into_string(),
                        from: message.source.map(|p| p.to_string()).unwrap_or_default(),
                        data: message.data,
                    });
                }
                _ => {}
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ride(i: usize) -> TopicHash {
        TopicHash::from_raw(format!("{RIDE_TOPIC_PREFIX}{i:064x}"))
    }

    fn message(size: usize) -> RawMessage {
        RawMessage {
            source: None,
            data: vec![0; size],
            sequence_number: None,
            topic: ride(0),
            signature: None,
            key: None,
            validated: false,
        }
    }

    #[test]
    fn a_message_over_the_limit_is_dropped_on_arrival() {
        assert!(BoundedMessages
            .inbound_transform(message(MAX_MESSAGE_BYTES))
            .is_ok());
        assert!(BoundedMessages
            .inbound_transform(message(MAX_MESSAGE_BYTES + 1))
            .is_err());
    }

    #[test]
    fn a_peer_can_be_on_klippelgo_topics_only() {
        let mut filter = KlippelGoTopics;
        assert!(filter.can_subscribe(&ride(1)));
        assert!(filter.can_subscribe(&TopicHash::from_raw(
            "/klippelgo/match/9206bc18e70d7a15/6g9fp"
        )));
        assert!(!filter.can_subscribe(&TopicHash::from_raw("/klippelgo/other")));
        // The release has one shape: the old unversioned topic, a short or upper-case release, or a cell
        // without one are not KlippelGo's.
        assert!(!filter.can_subscribe(&TopicHash::from_raw("/klippelgo/match/1/6g9fp")));
        assert!(!filter.can_subscribe(&TopicHash::from_raw(
            "/klippelgo/match/9206BC18E70D7A15/6g9fp"
        )));
        assert!(!filter.can_subscribe(&TopicHash::from_raw(
            "/klippelgo/match/9206bc18e70d7a1/6g9fp"
        )));
        assert!(!filter.can_subscribe(&TopicHash::from_raw(
            "/klippelgo/match/9206bc18e70d7a15/6g9fp/x"
        )));
        assert!(!filter.can_subscribe(&TopicHash::from_raw(format!(
            "/klippelgo/match/9206bc18e70d7a15/{}",
            "b".repeat(15_000)
        ))));
    }
}
