.PHONY: test test-contracts test-core testnet deploy demo testnet-reset

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
