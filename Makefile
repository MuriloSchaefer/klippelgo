.PHONY: test test-contracts test-core testnet deploy demo testnet-reset android-two-phones android-apk android-test-chain

test: test-contracts test-core

test-contracts:
	cd contracts && forge test

test-core:
	cd core && cargo test

## Start the local chain (foreground). Run deploy and demo from another terminal.
testnet:
	testnet/anvil.sh

deploy:
	testnet/deploy.sh

demo:
	testnet/demo-ride.sh

testnet-reset:
	rm -rf testnet/.state deployments/local.json

## Android: rider and driver on two emulators against the local testnet (see apps/android/README.md).
android-two-phones:
	apps/android/scripts/two-phones.sh

android-apk:
	cd apps/android && ./gradlew :app:assembleDebug

android-test-chain:
	cd apps/android/chain && KLIPPELGO_RPC=http://127.0.0.1:8545 KLIPPELGO_DEPLOYMENT=$(CURDIR)/deployments/local.json ../gradlew test
