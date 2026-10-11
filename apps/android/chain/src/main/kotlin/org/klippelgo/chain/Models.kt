package org.klippelgo.chain

import java.math.BigDecimal
import java.math.BigInteger

/** Mirrors `RideEscrow.Status`. */
enum class RideStatus { None, Open, Accepted, Started, Completed, Cancelled, Disputed, Resolved;

    val isActive get() = this == Open || this == Accepted || this == Started || this == Disputed

    companion object {
        fun of(i: Int) = entries.getOrElse(i) { None }
    }
}

data class Ride(
    val id: BigInteger,
    val rider: String,
    val driver: String,
    val token: String,
    val status: RideStatus,
    val openedAt: Long,
    val startedAt: Long,
    val fare: BigInteger,
    val fee: BigInteger,
    val riderBond: BigInteger,
    val driverBond: BigInteger,
    val quoteHash: String,
    /** Hash of the trip receipt on record, or all zeros while there is none. */
    val receiptHash: String,
    /** Unix seconds when the receipt was put on record, 0 while there is none. */
    val receiptAt: Long,
) {
    /** What the driver keeps: the fare minus R$ 1,00 for Instituto Klippel. */
    val driverPayout: BigInteger get() = fare - fee
}

/**
 * What a request tells drivers about the trip, computed on the rider's phone from its map pack: enough to price
 * it, and nothing that places it. The exact route stays on the rider's phone behind `routeHash`.
 */
data class RouteSummary(
    /** Id of the map pack the route was computed on (`manifestHash` in MapRegistry), as 0x hex. */
    val packId: String,
    /** The rider's salted commitment to the route, as 0x hex. Every quote for the request signs over it. */
    val routeHash: String,
    /** Metres by road, rounded to 500 m. */
    val distanceM: Int,
    /** Seconds, rounded to a minute. No traffic is known. */
    val durationS: Int,
)

/** A rider's request for a ride, as gossiped to nearby drivers. */
data class RideRequest(
    /** Hash of the request's contents, so nobody else can reuse the id. */
    val id: BigInteger,
    val rider: String,
    val token: String,
    /** The most the rider will pay. Drivers quote at or below it. */
    val maxFare: BigInteger,
    /** Unix seconds, by the phones' clocks. */
    val expiresAt: Long,
    val route: RouteSummary,
)

/** A driver's signed quote for a request. The rider locks `quoteHash` in the escrow when taking it. */
data class Offer(
    val requestId: BigInteger,
    val driver: String,
    val fare: BigInteger,
    val expiresAt: Long,
    val quoteHash: String,
    /** The driver's signature over the quote, kept as proof of what was offered. */
    val signature: String,
    /** The key the rider seals the route to if they take this offer, as 0x hex. Made for this offer alone. */
    val routeKey: String,
)

data class DriverInfo(
    val address: String,
    /** Average stars x100, 0 when unrated. */
    val score: Int,
    val ratings: Int,
    val completedRides: Int,
    /** Whether the governor's policy currently lets this driver accept rides. */
    val canAccept: Boolean,
)

/** A requirement of the accept policy that a driver does not meet. */
sealed interface Blocker {
    /** Not registered with a role the policy asks for. */
    data object Role : Blocker

    data class Stake(val have: BigDecimal, val need: BigDecimal) : Blocker

    /** Average stars x100. */
    data class Score(val have: Int, val need: Int) : Blocker

    /** A missing or expired credential. `cnh` tells the driving licence from any other kind. */
    data class Credential(val cnh: Boolean) : Blocker

    /** The account registered too recently. */
    data object AccountAge : Blocker

    /** The policy itself is switched off, so nobody may accept. */
    data object Disabled : Blocker
}

data class Coin(val symbol: String, val address: String, val decimals: Int) {
    fun toBaseUnits(reais: BigDecimal): BigInteger = reais.movePointRight(decimals).toBigIntegerExact()
    fun toReais(units: BigInteger): BigDecimal = BigDecimal(units, decimals)
}

class ChainException(message: String, cause: Throwable? = null) : Exception(message, cause)
