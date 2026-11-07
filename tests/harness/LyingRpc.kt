package org.klippelgo.tests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import org.web3j.crypto.Hash
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI

/**
 * An RPC provider that lies: a JSON-RPC proxy on this computer in front of the stack's chain node that, while
 * [lying], changes the answer to every `eth_call` of `function` (the last byte of its 32-byte word `word` is
 * flipped). Everything else passes through. A phone reaches it at [urlFromPhone], as the app's second provider.
 */
class LyingRpc(private val stack: Stack, function: String, private val word: Int = 0) : AutoCloseable {
    @Volatile var lying = true

    private val selector = Hash.sha3String(function).take(10)
    private val json = ObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes()
            val conn = URI(stack.rpc).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
            val answer = conn.inputStream.use { it.readBytes() }
            val out = if (lying) json.writeValueAsBytes(lie(json.readTree(body), json.readTree(answer))) else answer
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }

    /** Its address as the emulator sees this computer. */
    val urlFromPhone: String get() = "http://10.0.2.2:${server.address.port}"

    private fun lie(request: JsonNode, answer: JsonNode): JsonNode {
        if (request.isArray) {
            val byId = request.associateBy { it.get("id").asText() }
            answer.forEach { a -> byId[a.get("id").asText()]?.let { lieOne(it, a) } }
        } else {
            lieOne(request, answer)
        }
        return answer
    }

    private fun lieOne(request: JsonNode, answer: JsonNode) {
        if (request.get("method")?.asText() != "eth_call") return
        val data = request.get("params")?.get(0)?.let { it.get("data") ?: it.get("input") }?.asText() ?: return
        if (!data.startsWith(selector)) return
        val result = answer.get("result")?.asText() ?: return
        val at = 2 + 64 * (word + 1) - 2
        if (result.length < at + 2) return
        val flipped = result.substring(at, at + 2).toInt(16) xor 0x01
        (answer as ObjectNode).put("result", result.substring(0, at) + "%02x".format(flipped) + result.substring(at + 2))
    }

    override fun close() = server.stop(0)
}
