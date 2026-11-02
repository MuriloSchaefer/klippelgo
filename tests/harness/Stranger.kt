package org.klippelgo.tests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.klippelgo.chain.Call
import org.klippelgo.chain.Coin
import org.klippelgo.chain.KlippelGoClient
import org.klippelgo.chain.Matchmaker
import org.klippelgo.chain.RideRequest
import org.klippelgo.chain.RouteSummary
import org.klippelgo.chain.P256
import org.klippelgo.chain.SoftwareDeviceKey
import org.klippelgo.chain.SessionCertificate
import org.klippelgo.chain.SessionKey
import org.klippelgo.core.GossipConfig
import org.klippelgo.core.GossipNode
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Someone on the gossip network who follows no rules: a node that hears everything said in the pilot city's cells and can say
 * anything. Gossip is open, so this is not a stand-in for an attacker, it is one. The security tests use it to
 * listen for what the app lets out, and to send the app messages a well-behaved phone never would.
 *
 * It writes the protocol's messages by itself, from the documented format (`apps/docs/security.md`), not with
 * the app's `Matchmaker`. If the two disagree about a message, a test here fails, which is the point.
 */
class Stranger(private val stack: Stack) : AutoCloseable {
    private val node = GossipNode.start(GossipConfig(listen = null, bootstrap = listOf(stack.gossip), router = false))
    /** Every gossip cell of the pilot pack: the stranger hears the whole city and speaks in every part of it. */
    private val topics = Matchmaker.covering(stack.pack.bounds() ?: error("the pack has no bounds")).map(Matchmaker::topicFor).toSet()
    private val json = ObjectMapper()
    private val messages = CopyOnWriteArrayList<ByteArray>()

    @Volatile private var open = true

    /** Rides' own topics the stranger joined: anyone can, knowing the quote hash, which is on chain. */
    private val rides = CopyOnWriteArraySet<String>()

    private val listener = Thread {
        while (open) {
            runCatching { node.drain().filter { it.topic in topics || it.topic in rides }.forEach { messages += it.data } }
            Thread.sleep(200)
        }
    }.apply {
        isDaemon = true
        start()
    }

    init {
        topics.forEach(node::subscribe)
        waitUntil("the stranger to reach the gossip network", 20_000) { true.takeIf { node.connectedPeers() > 0u } }
        // Give the mesh a heartbeat or two to take the new node in, or the first messages fall on the floor.
        Thread.sleep(2_500)
    }

    /** Everything heard in the cells so far, as it went over the wire. */
    fun heard(): List<String> = messages.map { String(it) }

    fun heardJson(type: String? = null): List<JsonNode> =
        messages.mapNotNull { runCatching { json.readTree(it) }.getOrNull() }.filter { type == null || it.get("t")?.asText() == type }

    fun forget() = messages.clear()

    /** Says something in every cell, again every second for a while: gossip keeps no history and drops are normal. */
    fun say(message: ByteArray, seconds: Int = 6) {
        repeat(seconds) {
            topics.forEach { node.publish(it, message) }
            try {
                Thread.sleep(1_000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Says something in one cell only, again every second for a while. */
    fun sayIn(cell: String, message: ByteArray, seconds: Int = 6) {
        repeat(seconds) {
            node.publish(Matchmaker.topicFor(cell), message)
            try {
                Thread.sleep(1_000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Joins the topic of the ride locked on `quoteHash`, and hears what is said there from now on. */
    fun joinRide(quoteHash: String) {
        val topic = "/klippelgo/ride/1/${quoteHash.lowercase()}"
        node.subscribe(topic)
        rides += topic
        Thread.sleep(2_500)
    }

    /** Says something on the topic of the ride locked on `quoteHash`, again every second for a while. */
    fun sayOnRide(quoteHash: String, message: ByteArray, seconds: Int = 6) {
        repeat(seconds) {
            node.publish("/klippelgo/ride/1/${quoteHash.lowercase()}", message)
            Thread.sleep(1_000)
        }
    }

    fun sayOnce(message: ByteArray) {
        topics.forEach { node.publish(it, message) }
    }

    /**
     * A new account nobody registered: a key made up on the spot, as anyone can, and its account created on chain
     * (a few test reais in, and one operation), so that its session certificate checks out and what refuses its
     * messages is what it lacks, not that it does not exist.
     */
    fun newKey(): KlippelGoClient {
        val key = SoftwareDeviceKey(BigInteger(256, SecureRandom()).mod(P256.N - BigInteger.ONE) + BigInteger.ONE)
        val client = KlippelGoClient(stack.rpc, key, stack.deployment)
        client.mint(client.brz, client.brz.toBaseUnits(java.math.BigDecimal("5.00")))
        // Any operation creates the account; this one approves nothing to nobody.
        client.sendCalls(
            listOf(Call(client.brz.address, org.web3j.abi.FunctionEncoder.encode(
                org.web3j.abi.datatypes.Function("approve", listOf(org.web3j.abi.datatypes.Address(client.address), Uint256(BigInteger.ZERO)), emptyList()),
            ))),
        )
        return client
    }

    // ---------------------------------------------------------------- writing messages

    /**
     * A ride request signed by `signer`, claiming to come from `rider`. Every field is the caller's to choose,
     * including ones a real phone would refuse to send.
     */
    fun request(
        signer: KlippelGoClient,
        coin: Coin,
        maxFare: BigInteger,
        route: RouteSummary,
        rider: String = signer.address,
        expiresAt: Long = System.currentTimeMillis() / 1000 + 600,
        session: SessionKey? = null,
    ): Pair<RideRequest, ByteArray> {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = requestId(rider, coin.address, maxFare, expiresAt, nonce, route)
        val message = encode(
            session ?: signer.sessionKey(),
            "t" to "request",
            "rider" to rider,
            "token" to coin.address.lowercase(),
            "maxFare" to maxFare.toString(),
            "expiresAt" to expiresAt,
            "nonce" to Numeric.toHexString(nonce),
            "pack" to route.packId,
            "route" to route.routeHash,
            "distanceM" to route.distanceM,
            "durationS" to route.durationS,
            "from" to route.pickupCell,
            "to" to route.dropoffCell,
            "sig" to Numeric.toHexString((session ?: signer.sessionKey()).sign(id)),
        )
        return RideRequest(BigInteger(1, id), rider.lowercase(), coin.address.lowercase(), maxFare, expiresAt, route) to message
    }

    /** The id of a request: the hash of everything it says, which is also what its rider signs. */
    private fun requestId(rider: String, token: String, maxFare: BigInteger, expiresAt: Long, nonce: ByteArray, route: RouteSummary): ByteArray =
        KlippelGoClient.abiHash(
            Bytes32(domain("KlippelGo request v4")),
            Uint256(stack.deployment.chainId),
            Address(stack.deployment.escrow),
            Address(rider),
            Address(token),
            Uint256(maxFare),
            Uint64(expiresAt),
            Bytes32(nonce),
            Bytes32(Numeric.hexStringToByteArray(route.packId)),
            Bytes32(Numeric.hexStringToByteArray(route.routeHash)),
            Uint256(route.distanceM.toLong()),
            Uint256(route.durationS.toLong()),
            Bytes32(Hash.sha3(route.pickupCell.toByteArray())),
            Bytes32(Hash.sha3(route.dropoffCell.toByteArray())),
        )

    /** Waits to overhear a request from this rider and reads it, as any node in the cell can. */
    fun overhearRequest(rider: String, timeoutMs: Long = 30_000): RideRequest = waitUntil("to overhear ${Peer.short(rider)}'s request", timeoutMs) {
        heardJson("request").lastOrNull { it.get("rider").asText().equals(rider, ignoreCase = true) }?.let { m ->
            val route = RouteSummary(
                packId = m.get("pack").asText(),
                routeHash = m.get("route").asText(),
                distanceM = m.get("distanceM").asInt(),
                durationS = m.get("durationS").asInt(),
                pickupCell = m.get("from").asText(),
                dropoffCell = m.get("to").asText(),
            )
            val maxFare = BigInteger(m.get("maxFare").asText())
            val expiresAt = m.get("expiresAt").asLong()
            val id = requestId(m.get("rider").asText(), m.get("token").asText(), maxFare, expiresAt, Numeric.hexStringToByteArray(m.get("nonce").asText()), route)
            RideRequest(BigInteger(1, id), m.get("rider").asText().lowercase(), m.get("token").asText().lowercase(), maxFare, expiresAt, route)
        }
    }

    /** A quote for a request signed by `signer`, claiming to come from `driver`, at any fare. */
    fun offer(
        signer: KlippelGoClient,
        request: RideRequest,
        fare: BigInteger,
        driver: String = signer.address,
        expiresAt: Long = minOf(System.currentTimeMillis() / 1000 + 300, request.expiresAt),
        seq: Long = System.currentTimeMillis(),
    ): ByteArray {
        val routeKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val quoteHash = signer.quoteHash(request, driver, fare, expiresAt)
        val signed = KlippelGoClient.abiHash(Bytes32(domain("KlippelGo offer v3")), Bytes32(quoteHash), Uint64(seq), Bytes32(routeKey))
        return encode(
            signer,
            "t" to "offer",
            "request" to hex(request.id),
            "driver" to driver,
            "fare" to fare.toString(),
            "expiresAt" to expiresAt,
            "seq" to seq,
            "routeKey" to Numeric.toHexString(routeKey),
            "sig" to Numeric.toHexString(signer.sign(signed)),
        )
    }

    /** A withdrawal of a request, signed by whoever `signer` is. Only the request's rider may withdraw it. */
    fun close(signer: KlippelGoClient, request: RideRequest): ByteArray = encode(
        signer,
        "t" to "close",
        "request" to hex(request.id),
        "sig" to Numeric.toHexString(signer.sign(KlippelGoClient.abiHash(Bytes32(domain("KlippelGo close v2")), Uint256(request.id)))),
    )

    /** A position for the ride locked on `quoteHash`, signed by `signer`, with bytes only the stranger made up. */
    fun position(signer: KlippelGoClient, quoteHash: String): ByteArray {
        val sealed = ByteArray(96).also { SecureRandom().nextBytes(it) }
        val quote = Numeric.hexStringToByteArray(quoteHash)
        val signed = KlippelGoClient.abiHash(Bytes32(domain("KlippelGo sealed position v2")), Bytes32(quote), Bytes32(Hash.sha3(sealed)))
        return encode(
            signer,
            "t" to "position",
            "quote" to quoteHash.lowercase(),
            "sealed" to Base64.getEncoder().encodeToString(sealed),
            "sig" to Numeric.toHexString(signer.sign(signed)),
        )
    }

    /** A message with one field changed and the signature left as it was. */
    fun tamper(message: ByteArray, field: String, value: Any): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val copy = (json.readValue(message, Map::class.java) as Map<String, Any>).toMutableMap()
        copy[field] = value
        return json.writeValueAsBytes(copy)
    }

    /**
     * A session key under a certificate of the stranger's making: naming `account`, until `expiresAt`, signed by
     * `signedBy`'s device key. Honest when `signedBy` is the account itself and the time is sensible; anything
     * else is a forgery a peer must refuse.
     */
    fun session(
        account: String,
        signedBy: KlippelGoClient,
        expiresAt: Long = System.currentTimeMillis() / 1000 + 3600,
        key: org.web3j.crypto.ECKeyPair = Keys.createEcKeyPair(),
    ): SessionKey {
        val address = "0x" + Keys.getAddress(key)
        val hash = SessionCertificate.hashOf(stack.deployment.chainId, account, address, expiresAt)
        return SessionKey(key, SessionCertificate(account.lowercase(), address, expiresAt, signedBy.account.signature(hash)))
    }

    /** A session key signing messages under someone else's real certificate. */
    fun underCertificateOf(other: KlippelGoClient): SessionKey = SessionKey(Keys.createEcKeyPair(), other.sessionKey().certificate)

    private fun encode(signer: KlippelGoClient, vararg fields: Pair<String, Any>): ByteArray = encode(signer.sessionKey(), *fields)

    /** A message as a phone writes it: signed ones carry the certificate of the session key that signed them. */
    private fun encode(session: SessionKey, vararg fields: Pair<String, Any>): ByteArray {
        val map = linkedMapOf(*fields)
        val c = session.certificate
        map["cert"] = mapOf(
            "account" to c.account,
            "session" to c.session,
            "expiresAt" to c.expiresAt,
            "sig" to Numeric.toHexString(c.signature),
        )
        return json.writeValueAsBytes(map)
    }

    private fun domain(name: String): ByteArray = Hash.sha3(name.toByteArray())

    private fun hex(id: BigInteger) = Numeric.toHexStringWithPrefixZeroPadded(id, 64)

    override fun close() {
        open = false
        listener.join(2_000)
        node.stop()
    }
}
