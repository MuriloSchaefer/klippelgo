package org.klippelgo.chain

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger

/**
 * Test reais on the local testnet, standing in for a Pix: anvil's public deployer account (#0) mints the mock
 * coins, which mint to anyone, to an account that may not even exist yet. It refuses any chain but the local
 * one: the key is public.
 */
class Faucet(private val web3: Web3j, private val deployment: Deployment) {
    fun mint(coin: Coin, to: String, amount: BigInteger) {
        check(deployment.chainId == LOCAL_CHAIN) { "Test reais exist only on the local testnet" }
        val manager = RawTransactionManager(web3, Credentials.create(KEY), deployment.chainId, PollingTransactionReceiptProcessor(web3, 500, 120))
        val data = FunctionEncoder.encode(Function("mint", listOf(Address(to), Uint256(amount)), emptyList()))
        val gasPrice = web3.ethGasPrice().send().gasPrice
        // Several phones may ask at once: one faucet transaction at a time keeps its nonces in order.
        val sent = synchronized(Faucet) { manager.sendTransaction(gasPrice, BigInteger.valueOf(200_000), coin.address, data, BigInteger.ZERO) }
        if (sent.hasError()) throw ChainException("mint: ${sent.error.message}")
        val receipt = PollingTransactionReceiptProcessor(web3, 500, 120).waitForTransactionReceipt(sent.transactionHash)
        if (!receipt.isStatusOK) throw ChainException("mint: transaction ${receipt.transactionHash} reverted")
    }

    companion object {
        private const val LOCAL_CHAIN = 31337L

        /** Anvil's account #0 from its public test mnemonic: the deployer, which holds no privilege after deploying. */
        private const val KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
    }
}
