# Bootstrap node of the KlippelGo gossip network. Built and run by testnet/docker-compose.yml.
FROM rust:1.99-slim-bookworm AS build
# Use the image's compiler as is: rust-toolchain.toml would also fetch the Android targets, which a server
# build does not need.
ENV RUSTUP_TOOLCHAIN=1.99.0
WORKDIR /core
COPY Cargo.toml Cargo.lock uniffi.toml ./
COPY src src
RUN cargo build --release --locked --bin klippelgo-node

FROM debian:bookworm-slim
COPY --from=build /core/target/release/klippelgo-node /usr/local/bin/klippelgo-node
USER nobody
EXPOSE 4001
ENTRYPOINT ["klippelgo-node"]
CMD ["/ip4/0.0.0.0/tcp/4001"]
