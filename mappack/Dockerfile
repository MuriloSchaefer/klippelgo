# The tools KlippelGo adds to a map pack build: osmium to cut a region out of an extract, and klippelgo-mappack
# for the proof index, the search database and the manifest. Built from the repo root by mappack/build.sh:
#
#   docker build -f mappack/Dockerfile -t klippelgo-mappack .
#
# Base images are pinned by digest so every rebuild runs the same compiler and the same osmium.
FROM rust:1.99-slim-bookworm@sha256:2c3a22f0a5533ea2dd5a16627bc841228151faa2d4de2644ac9987e4a2f1f2fa AS build
ENV RUSTUP_TOOLCHAIN=1.99.0
WORKDIR /src
COPY core/verify core/verify
COPY core/proofs core/proofs
COPY mappack/Cargo.toml mappack/Cargo.lock mappack/
COPY mappack/src mappack/src
RUN cargo build --release --locked --manifest-path mappack/Cargo.toml

FROM debian:bookworm-slim@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587
RUN apt-get update \
    && apt-get install -y --no-install-recommends osmium-tool \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/mappack/target/release/klippelgo-mappack /usr/local/bin/klippelgo-mappack
WORKDIR /work
