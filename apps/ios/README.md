# KlippelGo iOS

Swift and SwiftUI shell over the shared Rust core (`../../core`, bound with UniFFI).

Not created yet. Planned layout:

- `KlippelGo/` SwiftUI app, Portuguese (pt-BR) by default with English and Spanish via String Catalogs.
- Keys in Secure Enclave. The signing key is a non-exportable P-256 key behind Face ID, Touch ID or passcode.
- Talks to the local testnet at `http://127.0.0.1:8545` from the simulator.
