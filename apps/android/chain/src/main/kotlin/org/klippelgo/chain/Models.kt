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
) {
    /** What the driver keeps: the fare minus R$ 1,00 for Instituto Klippel. */
    val driverPayout: BigInteger get() = fare - fee
}

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

data class Coin(val symbol: String, val address: String, val decimals: Int) {
    fun toBaseUnits(reais: BigDecimal): BigInteger = reais.movePointRight(decimals).toBigIntegerExact()
    fun toReais(units: BigInteger): BigDecimal = BigDecimal(units, decimals)
}

class ChainException(message: String, cause: Throwable? = null) : Exception(message, cause)
