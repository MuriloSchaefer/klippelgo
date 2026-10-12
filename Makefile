.PHONY: test test-contracts test-core test-mappack testnet deploy demo testnet-reset reset-ratings gossip-node mappack publish-pack serve-packs android-two-phones android-four-phones android-apk android-test-chain e2e e2e-integrity e2e-security e2e-performance e2e-up e2e-down e2e-three-phones android-drive

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

## Android: two riders and two drivers on four emulators. Needs about 16 GB of free RAM.
android-four-phones:
	PAIRS=2 apps/android/scripts/two-phones.sh

## Android: move a driver's emulator along its current route, a tenth of the way each second: to the pickup
## until the ride starts, then along the ride. Run it again after tapping "Iniciar corrida".
##   make android-drive                          the emulator in driver mode (emulator-5556 with two phones)
##   make android-drive PHONE=emulator-5560      the second driver of android-four-phones
##   make android-drive LEG=ride STEPS=20 EVERY=0.5
## Along the ride the rider's emulator moves with the car. RIDER=emulator-5554 names it when several fit.
android-drive:
	tests/klippelgo drive $(LEG) $(if $(PHONE),--phone $(PHONE)) $(if $(RIDER),--rider $(RIDER)) $(if $(STEPS),--steps $(STEPS)) $(if $(EVERY),--every $(EVERY))

android-apk:
	cd apps/android && ./gradlew :app:assembleDebug

android-test-chain:
	testnet/publish-pack.sh testnet/fixtures/test-pack
	cd apps/android/chain && KLIPPELGO_RPC=http://127.0.0.1:8545 KLIPPELGO_DEPLOYMENT=$(CURDIR)/deployments/local.json ../gradlew test

## End-to-end tests: the real app on an emulator against the real chain, gossip and map (see tests/README.md).
## Brings up whatever is missing first, or uses the chain at KLIPPELGO_RPC.
e2e:
	tests/run.sh

e2e-integrity:
	tests/run.sh integrity

e2e-security:
	tests/run.sh security

e2e-performance:
	tests/run.sh performance

## Three copies of the app in one ride: a rider and two drivers on three emulators. Needs about 12 GB of free
## RAM. HEADLESS=0 make e2e-three-phones shows the three windows.
e2e-three-phones:
	PHONES=3 tests/run.sh integrity --tests '*ThreePhones*'

e2e-up:
	tests/stack.sh up

## Stops what the end-to-end tests started, and only that.
e2e-down:
	tests/stack.sh down
