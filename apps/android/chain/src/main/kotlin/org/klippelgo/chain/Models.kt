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
 * it, and nothing that points at a door. It says how far and how long, and in which region of about a square
 * kilometre the trip starts and ends. The exact route stays on the rider's phone behind `routeHash`.
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
    /** The region the pickup is in: a geohash cell of about 1.2 km by 0.6 km (`regionCell` in the core). */
    val pickupCell: String,
    /** The region the drop-off is in. */
    val dropoffCell: String,
)

/**
 * The price guide governance set in `FareGuide`: what a trip is recommended to cost and how far riders and
 * drivers may move from it. The escrow never learns a trip's length, so the phones keep these limits: a driver's
 * phone ignores a request priced outside the rider's range, and a rider's phone ignores a quote under the
 * driver's floor. Amounts are in centavos.
 */
data class Tariff(
    val baseCentavos: Long,
    val perKmCentavos: Long,
    val perMinuteCentavos: Long,
    val minimumCentavos: Long,
    /** How far above or below the recommendation a rider may ask, and a driver quote above it, in basis points. */
    val riderRangeBps: Int,
    /** How far below the rider's price a driver may quote, in basis points. */
    val driverDiscountBps: Int,
) {
    /** The recommended price for a trip, from the distance and duration its request states. Mirrors `FareGuide.recommended`. */
    fun recommended(distanceM: Int, durationS: Int): Long {
        val price = maxOf(minimumCentavos, baseCentavos + perKmCentavos * distanceM / 1000 + perMinuteCentavos * durationS / 60)
        return (price + STEP_CENTAVOS / 2) / STEP_CENTAVOS * STEP_CENTAVOS
    }

    /** The lowest and highest price a rider may ask for the trip. */
    fun riderRange(distanceM: Int, durationS: Int): LongRange {
        val price = recommended(distanceM, durationS)
        val range = price * riderRangeBps / 10_000
        return (price - range)..(price + range)
    }

    /** The highest quote a driver may make for the trip, whatever the rider asked: the top of the rider's range. */
    fun driverCeiling(distanceM: Int, durationS: Int): Long = riderRange(distanceM, durationS).last

    /** The lowest quote a driver may make against a rider's price, in the same units as the price. */
    fun driverFloor(riderPrice: BigInteger): BigInteger =
        riderPrice - riderPrice * BigInteger.valueOf(driverDiscountBps.toLong()) / BigInteger.valueOf(10_000)

    companion object {
        /** Recommendations are whole multiples of R$ 0,50. */
        const val STEP_CENTAVOS = 50L
    }
}

/** A rider's request for a ride, as gossiped to nearby drivers. */
data class RideRequest(
    /** Hash of the request's contents, so nobody else can reuse the id. */
    val id: BigInteger,
    val rider: String,
    val token: String,
    /**
     * The rider's price. Drivers quote around it: down to the guide's floor under it, or above it up to the
     * guide's ceiling for the trip, which the rider is free to turn down.
     */
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
    fun fromCentavos(centavos: Long): BigInteger = BigInteger.valueOf(centavos) * BigInteger.TEN.pow(decimals - 2)

    fun toBaseUnits(reais: BigDecimal): BigInteger = reais.movePointRight(decimals).toBigIntegerExact()
    fun toReais(units: BigInteger): BigDecimal = BigDecimal(units, decimals)
}

class ChainException(message: String, cause: Throwable? = null) : Exception(message, cause)
