//! The gossip network phones match through: libp2p GossipSub v1.1 over TCP, Noise and Yamux.
//!
//! This module moves bytes between peers on topics and knows nothing about rides. What a message means, who may
//! send it and whether its account signature is valid is decided by the caller, which must treat every message
//! as untrusted: anyone can join a topic and publish to it.
//!
//! A node is either a phone, which dials bootstrap nodes and subscribes to the cells around it, or a router,
//! which listens for phones and forwards every KlippelGo topic they subscribe to.

use futures::StreamExt;
use libp2p::gossipsub::{self, IdentTopic, MessageAuthenticity, ValidationMode};
use libp2p::swarm::{NetworkBehaviour, SwarmEvent};
use libp2p::{noise, ping, tcp, yamux, Multiaddr, Swarm, SwarmBuilder};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{mpsc as std_mpsc, Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;

/// Port the bootstrap node listens on by default.
pub const DEFAULT_PORT: u16 = 4001;

/// Every KlippelGo topic starts with this. Routers forward nothing else.
pub const TOPIC_PREFIX: &str = "/klippelgo/";

/// Largest message a peer accepts. Matching messages are a few hundred bytes.
const MAX_MESSAGE_BYTES: usize = 4096;

/// Messages kept for the caller between two `drain` calls. Older ones are dropped first.
const MAX_INBOX: usize = 4096;

const REDIAL_EVERY: Duration = Duration::from_secs(3);

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
    #[error("topic must start with {TOPIC_PREFIX}")]
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
    gossipsub: gossipsub::Behaviour,
    ping: ping::Behaviour,
}

#[derive(Default)]
struct Shared {
    inbox: Mutex<VecDeque<GossipMessage>>,
    peers: AtomicU32,
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

fn parse_addr(addr: &str) -> Result<Multiaddr, GossipError> {
    addr.parse()
        .map_err(|_| GossipError::InvalidAddress(addr.to_string()))
}

fn check_topic(topic: &str) -> Result<(), GossipError> {
    if topic.starts_with(TOPIC_PREFIX) {
        Ok(())
    } else {
        Err(GossipError::InvalidTopic)
    }
}

fn build_swarm() -> Result<Swarm<Behaviour>, Box<dyn std::error::Error + Send + Sync>> {
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
            let config = gossipsub::ConfigBuilder::default()
                .heartbeat_interval(Duration::from_secs(1))
                .validation_mode(ValidationMode::Strict)
                .max_transmit_size(MAX_MESSAGE_BYTES + 512)
                .build()?;
            Ok(Behaviour {
                gossipsub: gossipsub::Behaviour::new(
                    MessageAuthenticity::Signed(key.clone()),
                    config,
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
    let mut swarm = match build_swarm() {
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
            event = swarm.select_next_some() => match event {
                SwarmEvent::NewListenAddr { address, .. } => {
                    if let Some(ready) = ready.take() {
                        let _ = ready.send(Ok((peer_id.clone(), vec![address.to_string()])));
                    }
                }
                SwarmEvent::ConnectionEstablished { .. } | SwarmEvent::ConnectionClosed { .. } => {
                    shared.peers.store(swarm.connected_peers().count() as u32, Ordering::Relaxed);
                }
                SwarmEvent::Behaviour(BehaviourEvent::Gossipsub(gossipsub::Event::Subscribed { topic, .. })) => {
                    if router && topic.as_str().starts_with(TOPIC_PREFIX) {
                        let _ = swarm.behaviour_mut().gossipsub.subscribe(&IdentTopic::new(topic.as_str()));
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
