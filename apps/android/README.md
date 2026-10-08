# KlippelGo Android

Kotlin and Jetpack Compose shell over the shared Rust core (`../../core`, bound with UniFFI).

Not created yet. Planned layout:

- `app/` Compose UI, Portuguese (pt-BR) by default with English and Spanish strings.
- Keys in StrongBox, falling back to TEE Keystore. The signing key is a non-exportable P-256 key behind
  biometrics or device PIN.
- Talks to the local testnet at `http://10.0.2.2:8545` from the emulator.
