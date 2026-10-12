package org.klippelgo.tests.cli

import com.fasterxml.jackson.databind.ObjectMapper
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.Offer
import org.klippelgo.chain.RideRequest
import org.klippelgo.core.Coordinate
import org.klippelgo.tests.Peer
import org.klippelgo.tests.Stack
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A peer account as someone at a command line plays it: the same [Peer] the tests use, with what it is in the
 * middle of kept between commands. Every public function is a command: `klippelgo peer <account> <function>`.
 */
class PeerSession(private val peer: Peer) {
    private var request: RideRequest? = null

    fun address(): String = peer.address

    /** This account's balance in BRZ, in reais. */
    fun balance(): BigDecimal = peer.coin.toReais(peer.balance())

    // ---------------------------------------------------------------- as a rider

    /** Asks for a ride between two points, at `price` reais or at what the fare guide recommends. */
    fun ask(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, price: BigDecimal? = null): Map<String, Any> {
        val route = peer.route(at(fromLat, fromLon), at(toLat, toLon))
        val asked = peer.ask(route, price?.let(peer.coin::toBaseUnits))
        request = asked
        return mapOf(
            "request" to asked.id.toString(16),
            "distanceM" to asked.route.distanceM,
            "durationS" to asked.route.durationS,
            "price" to peer.coin.toReais(asked.maxFare),
        )
    }

    /** The quotes received for the request this peer made, cheapest first. */
    fun offers(): List<Map<String, Any>> = peer.offers(current()).map(::shown)

    /** Waits for a driver's quote. */
    fun awaitOffer(driver: String): Map<String, Any> = shown(peer.awaitOffer(current(), driver))

    /** Takes a driver's quote: locks the fare and sends them the route. Gives the ride id. */
    fun take(driver: String): BigInteger {
        val asked = current()
        val offer = peer.offers(asked).firstOrNull { it.driver == driver.lowercase() } ?: error("no offer from $driver")
        return peer.take(asked, offer).also { request = null }
    }

    /** Withdraws the request. */
    fun withdraw() {
        peer.withdraw(current())
        request = null
    }

    /** Confirms arrival, which pays the driver. */
    fun complete(ride: BigInteger) = peer.complete(ride)

    /** Where the driver of this ride last said they were, or null. */
    fun driverPosition(ride: BigInteger) = peer.driverPosition(ride)

    // ---------------------------------------------------------------- as a driver

    /** The riders' open requests this peer can see. */
    fun requests(): List<Map<String, Any>> = peer.requests().map {
        mapOf(
            "rider" to it.rider,
            "distanceM" to it.route.distanceM,
            "durationS" to it.route.durationS,
            "price" to peer.coin.toReais(it.maxFare),
            "from" to it.route.pickupCell,
            "to" to it.route.dropoffCell,
        )
    }

    /** Quotes `fare` reais for a rider's request, or the rider's own price. Waits for the request if need be. */
    fun offer(rider: String, fare: BigDecimal? = null): Map<String, Any> =
        shown(peer.offer(peer.awaitRequest(rider), fare?.let(peer.coin::toBaseUnits)))

    /** Waits for the route the rider sealed for this ride and checks it on the map. Gives its length. */
    fun awaitRoute(ride: BigInteger): Map<String, Any> = peer.awaitRoute(peer.ride(ride)).let {
        mapOf("distanceM" to it.distanceM.toInt(), "durationS" to it.durationS.toInt(), "checked" to true)
    }

    /** The newest ride that names this account as its driver. */
    fun lastRideAsDriver(): BigInteger? = peer.client.ridesAsDriver().firstOrNull()

    fun accept(ride: BigInteger) = peer.accept(ride)

    fun start(ride: BigInteger) = peer.start(ride)

    /** Tells the rider of this ride where this driver is. */
    fun position(ride: BigInteger, lat: Double, lon: Double) = peer.sharePosition(ride, at(lat, lon))

    // ---------------------------------------------------------------- either

    fun ride(id: BigInteger) = peer.ride(id)

    fun cancel(ride: BigInteger) = peer.cancel(ride)

    fun rate(ride: BigInteger, stars: Int = 5) = peer.rate(ride, stars)

    private fun current() = request ?: error("this peer has no request out. Make one: peer <account> ask …")

    private fun shown(offer: Offer) = mapOf("driver" to offer.driver, "fare" to peer.coin.toReais(offer.fare), "quoteHash" to offer.quoteHash)

    private fun at(lat: Double, lon: Double) = Coordinate(Math.round(lat * 1e7).toInt(), Math.round(lon * 1e7).toInt())
}

/**
 * Keeps the peer accounts alive between CLI commands. A peer is a gossip node with keys in memory that must
 * keep announcing, so it cannot live inside a command that ends. `klippelgo peers start` runs this in the
 * background; `klippelgo peer …` talks to it over a socket on this computer.
 */
object PeerDaemon {
    private val json = ObjectMapper()

    private fun portFile(stack: Stack) = File(stack.runDir, "peers.port")

    val ACCOUNTS = mapOf("rider" to DevAccount.RIDER, "driver" to DevAccount.DRIVER, "rider2" to DevAccount.RIDER_2, "driver2" to DevAccount.DRIVER_2)

    fun serve(stack: Stack) {
        val sessions = ConcurrentHashMap<String, PeerSession>()
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        portFile(stack).writeText(server.localPort.toString())
        Runtime.getRuntime().addShutdownHook(Thread { portFile(stack).delete() })
        println("Peers listening on 127.0.0.1:${server.localPort}")
        while (true) {
            val socket = server.accept()
            socket.use {
                val line = it.getInputStream().bufferedReader().readLine() ?: return@use
                // Whatever connects and says something that is not a command is hung up on.
                val request = runCatching { json.readTree(line) }.getOrNull()?.takeIf { r -> r.isObject } ?: return@use
                if (request.get("stop")?.asBoolean() == true) {
                    it.getOutputStream().write("{\"ok\":true}\n".toByteArray())
                    return
                }
                val answer = runCatching {
                    val name = request.get("peer").asText()
                    val account = ACCOUNTS[name] ?: throw UsageException("No account \"$name\". There is: ${ACCOUNTS.keys.joinToString(", ")}")
                    val session = sessions.getOrPut(name) { PeerSession(Peer(stack, account)) }
                    call(session, request.get("fn").asText(), request.get("args").map { a -> a.asText() })
                }
                val reply = answer.fold(
                    { result -> mapOf("ok" to true, "result" to if (result == Unit) null else result) },
                    { error -> mapOf("ok" to false, "error" to (error.message ?: error.javaClass.simpleName)) },
                )
                it.getOutputStream().write((json.writeValueAsString(reply) + "\n").toByteArray())
            }
        }
    }

    /** Sends one command to the running daemon and gives back its answer. */
    fun send(stack: Stack, message: Map<String, Any>): Any? {
        val port = portFile(stack).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?: throw UsageException("The peers are not running. Start them: klippelgo peers start")
        val socket = runCatching { Socket(InetAddress.getLoopbackAddress(), port) }
            .getOrElse { throw UsageException("The peers are not running. Start them: klippelgo peers start") }
        socket.use {
            it.soTimeout = 120_000
            it.getOutputStream().write((json.writeValueAsString(message) + "\n").toByteArray())
            val answer = json.readTree(it.getInputStream().bufferedReader().readLine() ?: error("the peers stopped"))
            if (!answer.get("ok").asBoolean()) throw UsageException(answer.get("error").asText())
            return answer.get("result")
        }
    }

    fun running(stack: Stack): Boolean = portFile(stack).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        ?.let { port -> runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isSuccess } ?: false
}
