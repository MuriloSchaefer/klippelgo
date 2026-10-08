//! Networks the app can talk to. Contract addresses come from `deployments/<network>.json`.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Network {
    /// Local anvil testnet (`make testnet`).
    Local,
    /// Polygon Amoy public testnet.
    Amoy,
    /// Polygon PoS mainnet.
    Polygon,
}

impl Network {
    pub fn chain_id(self) -> u64 {
        match self {
            Network::Local => 31337,
            Network::Amoy => 80002,
            Network::Polygon => 137,
        }
    }

    pub fn default_rpc(self) -> &'static str {
        match self {
            // Android emulators reach the host at 10.0.2.2 instead.
            Network::Local => "http://127.0.0.1:8545",
            Network::Amoy => "https://rpc-amoy.polygon.technology",
            Network::Polygon => "https://polygon-rpc.com",
        }
    }

    pub fn deployment_file(self) -> &'static str {
        match self {
            Network::Local => "deployments/local.json",
            Network::Amoy => "deployments/amoy.json",
            Network::Polygon => "deployments/polygon.json",
        }
    }
}
