package org.klippelgo.chain

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A person's account against a running local testnet and its bundler: what the phone works out on its own (the
 * address, the operation's hash) is what the contracts say, and a new account pays its first operation in reais.
 *
 * Runs when KLIPPELGO_RPC is set, with the bundler next to the node (`testnet/docker-compose.yml`), e.g.
 *   KLIPPELGO_RPC=http://127.0.0.1:8545 KLIPPELGO_DEPLOYMENT=../../../deployments/local.json ./gradlew :chain:test
 */
class SmartAccountTest {
    private val rpc = System.getenv("KLIPPELGO_RPC").orEmpty()
    private val deployment = System.getenv("KLIPPELGO_DEPLOYMENT").orEmpty()
        .takeIf { it.isNotBlank() }?.let { Deployment.fromJson(File(it).readText()) } ?: Deployment.LOCAL_DEFAULT

    private fun fresh(): KlippelGoClient =
        KlippelGoClient(rpc, SoftwareDeviceKey(BigInteger(256, SecureRandom()).mod(P256.N - BigInteger.ONE) + BigInteger.ONE), deployment)

    private fun read(to: String, function: Function): List<Type<*>> {
        val web3 = Web3j.build(HttpService(rpc))
        val res = web3.ethCall(Transaction.createEthCallTransaction(null, to, FunctionEncoder.encode(function)), DefaultBlockParameterName.LATEST).send()
        check(!res.hasError()) { res.error.message }
        @Suppress("UNCHECKED_CAST")
        return FunctionReturnDecoder.decode(res.value, function.outputParameters as List<TypeReference<Type<*>>>)
    }

    @Test
    fun theAddressAndTheOperationHashAreWhatTheContractsSay() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val client = fresh()
        val fromFactory = read(
            deployment.accountFactory,
            Function("accountOf", listOf(DynamicBytes(client.account.signer)), listOf(object : TypeReference<Address>() {})),
        )[0] as Address
        assertEquals(fromFactory.value.lowercase(), client.address)

        val op = UserOperation(
            sender = client.address,
            nonce = BigInteger.valueOf(7),
            factory = deployment.accountFactory,
            factoryData = client.account.factoryData,
            callData = SmartAccount.execute(listOf(Call(deployment.registry, "0x1234"))),
            callGasLimit = BigInteger.valueOf(500_000),
            verificationGasLimit = BigInteger.valueOf(900_000),
            preVerificationGas = BigInteger.valueOf(60_000),
            maxFeePerGas = BigInteger.valueOf(3_000_000_000),
            maxPriorityFeePerGas = BigInteger.valueOf(1_000_000_000),
            paymaster = deployment.paymaster,
            paymasterVerificationGasLimit = BigInteger.valueOf(300_000),
            paymasterPostOpGasLimit = BigInteger.valueOf(100_000),
            paymasterData = deployment.brz,
        )
        val packed = PackedOp(op)
        val onChain = read(
            deployment.entryPoint,
            Function("getUserOpHash", listOf(packed), listOf(object : TypeReference<Bytes32>() {})),
        )[0] as Bytes32
        assertEquals(Numeric.toHexString(onChain.value), Numeric.toHexString(op.hash(deployment.chainId, deployment.entryPoint)))
    }

    @Test
    fun aNewAccountRegistersAndPaysItsGasInReais() {
        if (rpc.isBlank()) return println("KLIPPELGO_RPC not set, skipping")
        val client = fresh()
        val brz = client.brz
        // The Pix stand-in: reais arrive before the account exists.
        client.mint(brz, brz.toBaseUnits(java.math.BigDecimal("20.00")))
        val before = client.balanceOf(brz)

        client.ensureRegistered(KlippelGoClient.ROLE_RIDER)

        assertEquals(KlippelGoClient.ROLE_RIDER, client.rolesOf())
        val paid = before - client.balanceOf(brz)
        assertTrue(paid.signum() > 0, "the account paid nothing for its gas")
        assertTrue(paid < brz.toBaseUnits(java.math.BigDecimal("0.10")), "the account paid R$ ${brz.toReais(paid)} for one operation")

        // Its session key speaks for it, as the account itself says on chain; a certificate it did not sign does not.
        val certificate = client.sessionKey().certificate
        assertTrue(client.certified(certificate))
        val other = fresh()
        assertFalse(client.certified(certificate.copy(signature = other.account.signature(certificate.hash(deployment.chainId)))))
        assertFalse(client.certified(certificate.copy(session = other.sessionKey().address)))

        // Signed by the account itself, but out of time: expired, or claiming to last more than two days.
        val now = System.currentTimeMillis() / 1000
        fun signedFor(expiresAt: Long) = SessionCertificate(
            client.address, certificate.session, expiresAt,
            client.account.signature(SessionCertificate.hashOf(deployment.chainId, client.address, certificate.session, expiresAt)),
        )
        assertTrue(client.certified(signedFor(now + 3_600)))
        assertFalse(client.certified(signedFor(now - 1)))
        assertFalse(client.certified(signedFor(now + 3 * 86_400)))
    }

    /** The EntryPoint's packed form of an operation, for asking it the hash it computes. */
    private class PackedOp(op: UserOperation) : DynamicStruct(
        Address(op.sender),
        Uint256(op.nonce),
        DynamicBytes(Numeric.hexStringToByteArray(op.factory.orEmpty()) + Numeric.hexStringToByteArray(op.factoryData ?: "0x")),
        DynamicBytes(Numeric.hexStringToByteArray(op.callData)),
        Bytes32(Numeric.toBytesPadded(op.verificationGasLimit, 16) + Numeric.toBytesPadded(op.callGasLimit, 16)),
        Uint256(op.preVerificationGas),
        Bytes32(Numeric.toBytesPadded(op.maxPriorityFeePerGas, 16) + Numeric.toBytesPadded(op.maxFeePerGas, 16)),
        DynamicBytes(
            Numeric.hexStringToByteArray(op.paymaster.orEmpty()) + Numeric.toBytesPadded(op.paymasterVerificationGasLimit, 16) +
                Numeric.toBytesPadded(op.paymasterPostOpGasLimit, 16) + Numeric.hexStringToByteArray(op.paymasterData ?: "0x"),
        ),
        DynamicBytes(ByteArray(0)),
    )
}
