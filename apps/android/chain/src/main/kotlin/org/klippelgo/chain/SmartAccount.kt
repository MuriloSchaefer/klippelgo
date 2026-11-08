package org.klippelgo.chain

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigInteger

/** One call an account makes: to a contract, with this data (and no native coin). */
data class Call(val to: String, val data: String)

/**
 * A person's account as the phone sees it: a `KlippelGoAccount` owned by a [DeviceKey]. Its address follows from
 * the key alone, worked out here and never asked of the chain node: a node that lied about it could have
 * someone's reais sent to an address they do not own.
 *
 * A phone added to an account another device made (or recovered to by a guardian) is `joined` to it instead: that
 * address was made from the other device's key, and is the one the person confirmed on this phone (apps A19).
 */
class SmartAccount(val key: DeviceKey, val deployment: Deployment, joined: String? = null) {
    /** The signer entry the account knows this key by: the verifier's address, then the key. */
    val signer: ByteArray = Numeric.hexStringToByteArray(deployment.deviceKeyVerifier) + key.publicKey

    /** The account this key's own first operation would create: an EIP-1167 clone at CREATE2 from the factory. */
    val ownAddress: String = cloneAddress(deployment.accountImplementation, Hash.sha3(signer), deployment.accountFactory)

    /** The account this phone signs for: its own, or the one it joined. */
    val address: String = joined?.lowercase() ?: ownAddress

    /** Whether this phone signs for an account another key made, which therefore exists already. */
    val isJoined: Boolean = !address.equals(ownAddress, ignoreCase = true)

    /** What the EntryPoint calls to create the account with its first operation: the factory and its data. */
    val factoryData: String = FunctionEncoder.encode(Function("createAccount", listOf(DynamicBytes(signer)), emptyList()))

    /** The account's signature over `hash` by this key: `abi.encode(signers, signatures)` with one of each. */
    fun signature(hash: ByteArray): ByteArray = encodeSignature(signer, key.sign(hash))

    companion object {
        /** `ERC7821.execute(mode, abi.encode(Execution[]))` for a batch, all or nothing. */
        fun execute(calls: List<Call>): String {
            val batch = DynamicArray(
                Execution::class.java,
                calls.map { Execution(Address(it.to), Uint256.DEFAULT, DynamicBytes(Numeric.hexStringToByteArray(it.data))) },
            )
            val executionData = Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(listOf(batch)))
            return FunctionEncoder.encode(
                Function("execute", listOf(Bytes32(BATCH_MODE), DynamicBytes(executionData)), emptyList()),
            )
        }

        fun encodeSignature(signer: ByteArray, signature: ByteArray): ByteArray = Numeric.hexStringToByteArray(
            FunctionEncoder.encodeConstructor(
                listOf(
                    DynamicArray(DynamicBytes::class.java, listOf(DynamicBytes(signer))),
                    DynamicArray(DynamicBytes::class.java, listOf(DynamicBytes(signature))),
                ),
            ),
        )

        /** The code every account has: an EIP-1167 clone of `implementation`. Another code is no KlippelGo account. */
        fun cloneCode(implementation: String): String =
            "0x363d3d373d3d3d363d73" + Numeric.cleanHexPrefix(implementation).lowercase() + "5af43d82803e903d91602b57fd5bf3"

        /** OpenZeppelin's `Clones.predictDeterministicAddress(implementation, salt, deployer)`. */
        fun cloneAddress(implementation: String, salt: ByteArray, deployer: String): String {
            val initCode = Numeric.hexStringToByteArray(
                "0x3d602d80600a3d3981f3363d3d373d3d3d363d73" + Numeric.cleanHexPrefix(implementation).lowercase() +
                    "5af43d82803e903d91602b57fd5bf3",
            )
            val packed = byteArrayOf(0xff.toByte()) + Numeric.hexStringToByteArray(deployer) + salt + Hash.sha3(initCode)
            return "0x" + Numeric.toHexStringNoPrefix(Hash.sha3(packed)).takeLast(40)
        }

        /** ERC-7579 mode: a batch (call type 0x01), reverting on any failure, no selector, no payload. */
        private val BATCH_MODE = ByteArray(32).also { it[0] = 0x01 }
    }

    /** ERC-7579's `Execution`: target, value, call data. */
    class Execution(target: Address, value: Uint256, callData: DynamicBytes) : DynamicStruct(target, value, callData)
}

/**
 * An ERC-4337 user operation for EntryPoint v0.9, in the unpacked form bundlers take over JSON-RPC. Its hash, the
 * one the account's key signs, is computed here from the operation itself (EIP-712, as the EntryPoint does),
 * never asked of a node.
 */
data class UserOperation(
    val sender: String,
    val nonce: BigInteger,
    val factory: String? = null,
    val factoryData: String? = null,
    val callData: String,
    val callGasLimit: BigInteger = BigInteger.ZERO,
    val verificationGasLimit: BigInteger = BigInteger.ZERO,
    val preVerificationGas: BigInteger = BigInteger.ZERO,
    val maxFeePerGas: BigInteger = BigInteger.ZERO,
    val maxPriorityFeePerGas: BigInteger = BigInteger.ZERO,
    val paymaster: String? = null,
    val paymasterVerificationGasLimit: BigInteger = BigInteger.ZERO,
    val paymasterPostOpGasLimit: BigInteger = BigInteger.ZERO,
    val paymasterData: String? = null,
    val signature: String = "0x",
) {
    private fun initCode(): ByteArray =
        if (factory == null) ByteArray(0) else Numeric.hexStringToByteArray(factory) + Numeric.hexStringToByteArray(factoryData ?: "0x")

    private fun paymasterAndData(): ByteArray =
        if (paymaster == null) {
            ByteArray(0)
        } else {
            Numeric.hexStringToByteArray(paymaster) + uint128(paymasterVerificationGasLimit) +
                uint128(paymasterPostOpGasLimit) + Numeric.hexStringToByteArray(paymasterData ?: "0x")
        }

    /** The hash the EntryPoint's `getUserOpHash` gives, for this chain and EntryPoint. */
    fun hash(chainId: Long, entryPoint: String): ByteArray {
        val domain = KlippelGoClient.abiHash(
            Bytes32(DOMAIN_TYPEHASH), Bytes32(Hash.sha3("ERC4337".toByteArray())), Bytes32(Hash.sha3("1".toByteArray())),
            Uint256(chainId), Address(entryPoint),
        )
        val struct = KlippelGoClient.abiHash(
            Bytes32(PACKED_USEROP_TYPEHASH), Address(sender), Uint256(nonce), Bytes32(Hash.sha3(initCode())),
            Bytes32(Hash.sha3(Numeric.hexStringToByteArray(callData))), Bytes32(uint128(verificationGasLimit) + uint128(callGasLimit)),
            Uint256(preVerificationGas), Bytes32(uint128(maxPriorityFeePerGas) + uint128(maxFeePerGas)),
            Bytes32(Hash.sha3(paymasterAndData())),
        )
        return Hash.sha3(byteArrayOf(0x19, 0x01) + domain + struct)
    }

    /** The JSON-RPC form: hex quantities, and the optional parts left out when absent. */
    fun toRpc(): Map<String, String> = buildMap {
        put("sender", sender)
        put("nonce", Numeric.encodeQuantity(nonce))
        if (factory != null) {
            put("factory", factory)
            put("factoryData", factoryData ?: "0x")
        }
        put("callData", callData)
        put("callGasLimit", Numeric.encodeQuantity(callGasLimit))
        put("verificationGasLimit", Numeric.encodeQuantity(verificationGasLimit))
        put("preVerificationGas", Numeric.encodeQuantity(preVerificationGas))
        put("maxFeePerGas", Numeric.encodeQuantity(maxFeePerGas))
        put("maxPriorityFeePerGas", Numeric.encodeQuantity(maxPriorityFeePerGas))
        if (paymaster != null) {
            put("paymaster", paymaster)
            put("paymasterVerificationGasLimit", Numeric.encodeQuantity(paymasterVerificationGasLimit))
            put("paymasterPostOpGasLimit", Numeric.encodeQuantity(paymasterPostOpGasLimit))
            put("paymasterData", paymasterData ?: "0x")
        }
        put("signature", signature)
    }

    companion object {
        private val DOMAIN_TYPEHASH = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".toByteArray(),
        )
        private val PACKED_USEROP_TYPEHASH = Hash.sha3(
            ("PackedUserOperation(address sender,uint256 nonce,bytes initCode,bytes callData,bytes32 accountGasLimits," +
                "uint256 preVerificationGas,bytes32 gasFees,bytes paymasterAndData)").toByteArray(),
        )

        private fun uint128(v: BigInteger): ByteArray = Numeric.toBytesPadded(v, 16)
    }
}
