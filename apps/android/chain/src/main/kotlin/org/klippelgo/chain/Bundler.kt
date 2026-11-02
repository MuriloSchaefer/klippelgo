package org.klippelgo.chain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * An ERC-4337 bundler, over JSON-RPC: it takes people's signed operations and puts them on chain. It cannot
 * change an operation once signed (the account checks its signature over the hash), but it is believed on two
 * things: its gas estimates, which decide what the account pays (the client caps them, see
 * `KlippelGoClient.sendCalls`), and the receipt it reports, from which the client reads what happened (a ride's
 * id). Apart from that it can refuse or delay, so the phone can be pointed at another.
 */
class Bundler(private val url: String, private val entryPoint: String) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = ObjectMapper()

    /** Gas limits for the operation, as the bundler simulates it. The operation carries a placeholder signature. */
    data class Estimate(
        val preVerificationGas: BigInteger,
        val verificationGasLimit: BigInteger,
        val callGasLimit: BigInteger,
        val paymasterVerificationGasLimit: BigInteger?,
        val paymasterPostOpGasLimit: BigInteger?,
    )

    fun estimate(op: UserOperation): Estimate {
        val r = call("eth_estimateUserOperationGas", listOf(op.toRpc(), entryPoint))
        fun q(name: String) = r.get(name)?.asText()?.let(Numeric::decodeQuantity)
        return Estimate(
            preVerificationGas = q("preVerificationGas") ?: throw ChainException("the bundler gave no preVerificationGas"),
            verificationGasLimit = q("verificationGasLimit") ?: throw ChainException("the bundler gave no verificationGasLimit"),
            callGasLimit = q("callGasLimit") ?: throw ChainException("the bundler gave no callGasLimit"),
            paymasterVerificationGasLimit = q("paymasterVerificationGasLimit"),
            paymasterPostOpGasLimit = q("paymasterPostOpGasLimit"),
        )
    }

    /** Sends a signed operation. Returns its hash, which [receipt] waits on. */
    fun send(op: UserOperation): String = call("eth_sendUserOperation", listOf(op.toRpc(), entryPoint)).asText()

    /** What an operation that ran did, from the bundler's receipt. */
    data class Receipt(
        val success: Boolean,
        /** What the operation cost in the native coin, paid by the paymaster and charged to the account in reais. */
        val actualGasCost: BigInteger,
        val transactionHash: String,
        /** The logs the operation's calls emitted, with the contract that emitted each. */
        val logs: List<Log>,
        val reason: String?,
    )

    data class Log(val address: String, val topics: List<String>, val data: String)

    /** Waits for the operation to be on chain, up to `timeoutMs`. */
    fun receipt(userOpHash: String, timeoutMs: Long = 60_000): Receipt {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val r = call("eth_getUserOperationReceipt", listOf(userOpHash))
            if (!r.isNull) {
                val logs = r.get("logs").map { l ->
                    Log(l.get("address").asText().lowercase(), l.get("topics").map { it.asText() }, l.get("data").asText())
                }
                return Receipt(
                    success = r.get("success").asBoolean(),
                    actualGasCost = Numeric.decodeQuantity(r.get("actualGasCost").asText()),
                    transactionHash = r.get("receipt")?.get("transactionHash")?.asText().orEmpty(),
                    logs = logs,
                    reason = r.get("reason")?.asText()?.takeIf { it.isNotBlank() && it != "0x" },
                )
            }
            Thread.sleep(500)
        }
        throw ChainException("operation $userOpHash was not on chain after ${timeoutMs / 1000} s")
    }

    private fun call(method: String, params: List<Any>): JsonNode {
        val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
        // As bytes: OkHttp would add a charset to a string body, and some bundlers refuse anything but application/json.
        val request = Request.Builder().url(url).post(body.toByteArray().toRequestBody("application/json".toMediaType())).build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ChainException("the bundler at $url answered ${response.code}")
            response.body?.string() ?: throw ChainException("the bundler at $url answered nothing")
        }
        val node = json.readTree(text)
        node.get("error")?.let { e ->
            val data = e.get("data")?.let { if (it.isTextual) it.asText() else it.toString() }
            throw ChainException(KlippelGoClient.describeBundlerError(e.get("message")?.asText(), data))
        }
        return node.get("result") ?: throw ChainException("the bundler at $url answered without a result")
    }
}
