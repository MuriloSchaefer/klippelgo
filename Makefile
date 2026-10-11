.PHONY: test test-contracts test-core test-mappack testnet deploy demo testnet-reset reset-ratings gossip-node mappack publish-pack serve-packs android-two-phones android-apk android-test-chain

test: test-contracts test-core test-mappack

test-contracts:
	cd contracts && forge test

test-core:
	cd core && cargo test --workspace --all-features

test-mappack:
	cd mappack && cargo test

## Start the local chain (foreground). Run deploy and demo from another terminal.
testnet:
	testnet/anvil.sh

deploy:
	testnet/deploy.sh

demo:
	testnet/demo-ride.sh

testnet-reset:
	rm -rf testnet/.state deployments/local.json

## Clear the ratings one account received, keeping everything else: make reset-ratings WHO=driver
reset-ratings:
	testnet/reset-ratings.sh $(or $(WHO),driver)

## Bootstrap node of the gossip network phones match through (foreground), on port 4001. Runs in Docker.
gossip-node:
	docker compose -f testnet/docker-compose.yml up --build gossip

## Build a map pack with Docker: make mappack REGION=br-pr-cascavel (see mappack/README.md).
REGION ?= br-pr-cascavel
PACK ?= mappack/build/$(REGION)/pack
mappack:
	mappack/build.sh $(REGION)

## Activate a pack on the local testnet through its governance: make publish-pack [PACK=<pack dir>]
publish-pack:
	testnet/publish-pack.sh $(PACK)

## Serve the built packs to phones on port 4002 (foreground), as a mirror would.
serve-packs:
	mappack/serve.sh

## Android: rider and driver on two emulators against the local testnet (see apps/android/README.md).
android-two-phones:
	apps/android/scripts/two-phones.sh

android-apk:
	cd apps/android && ./gradlew :app:assembleDebug

android-test-chain:
	testnet/publish-pack.sh testnet/fixtures/test-pack
	cd apps/android/chain && KLIPPELGO_RPC=http://127.0.0.1:8545 KLIPPELGO_DEPLOYMENT=$(CURDIR)/deployments/local.json ../gradlew test
