# The local testnet's ERC-4337 bundler: Pimlico's Alto, pinned, on a pinned Node.
FROM node:22.12.0-bookworm-slim
RUN npm install -g --no-audit --no-fund @pimlico/alto@0.0.21 \
 && apt-get update && apt-get install -y --no-install-recommends curl jq && rm -rf /var/lib/apt/lists/*
COPY run.sh /run.sh
ENTRYPOINT ["/run.sh"]
