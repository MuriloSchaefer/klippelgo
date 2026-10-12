package org.klippelgo.tests.cli

import com.fasterxml.jackson.databind.ObjectMapper
import org.klippelgo.chain.DevAccount
import org.klippelgo.chain.Ride
import org.klippelgo.chain.RideStatus
import org.klippelgo.core.Coordinate
import org.klippelgo.core.EscrowRef
import org.klippelgo.core.VehicleKind
import org.klippelgo.core.routeRequest
import org.klippelgo.tests.Phone
import org.klippelgo.tests.Stack
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Moves an emulator's GPS along the route its driver is on, so a ride can be watched without anyone driving:
 * to the pickup while the ride has not started, then along the ride's own route.
 *
 * The route is the one the driver's app checked and saved (`files/rides/<quote hash>.route.json`), drawn on this
 * computer's copy of the same map pack. Nothing is invented: the car goes where the app says the ride goes.
 */
object Drive {
    private val json = ObjectMapper()

    /**
     * `leg` is `pickup`, `ride`, or null to pick by where the ride stands. Along the ride the rider is in the
     * car, so the rider's emulator moves with it: `riderSerial` names it, or it is the emulator signed in as
     * the ride's rider.
     */
    fun run(stack: Stack, serial: String?, riderSerial: String?, leg: String?, steps: Int, everySeconds: Double) {
        val phone = Phone(stack, serial ?: theDriversPhone(stack))
        // Before anything is read from the app: this is for emulators, as the rest of the harness is.
        phone.requireTestDevice()
        val account = accountOf(phone)
        val driver = stack.client(account)
        // The ride the driver is on: the one that started, or else the one locked first.
        val ride = driver.underWay().firstOrNull()
            ?: throw UsageException("${phone.serial} (${account.name}) has no ride under way. Take one in the app first.")
        val route = routeOf(stack, phone, ride)
        val wanted = leg ?: if (ride.status == RideStatus.Started) "ride" else "pickup"
        val line = when (wanted) {
            "ride" -> route
            "pickup" -> {
                val from = whereIs(phone) ?: throw UsageException("${phone.serial} has no position yet. Set one: klippelgo locate <lat> <lon>")
                roadTo(stack, from, route.first())
            }
            else -> throw UsageException("The leg is \"pickup\" or \"ride\", not \"$wanted\".")
        }
        val passenger = if (wanted == "ride") theRidersPhone(stack, ride, riderSerial, phone.serial) else null
        println("${phone.serial} (${account.name}), ride ${ride.id} (${ride.status}): ${if (wanted == "ride") "driving the ride" else "going to the pickup"}, ${metres(line).toInt()} m in $steps steps")
        if (passenger != null) println("${passenger.serial} is the rider's phone and goes along")
        // The start, then one step at a time: `steps` equal stretches of the way.
        for (i in 0..steps) {
            val at = along(line, i.toDouble() / steps)
            phone.locate(at.latE7 / 1e7, at.lonE7 / 1e7)
            passenger?.locate(at.latE7 / 1e7, at.lonE7 / 1e7)
            println("  %2d/%d  %.6f, %.6f".format(java.util.Locale.ROOT, i, steps, at.latE7 / 1e7, at.lonE7 / 1e7))
            if (i < steps) Thread.sleep((everySeconds * 1000).toLong())
        }
    }

    // ---------------------------------------------------------------- which phone, which account

    /** The running emulators. Only those: a phone someone uses that happens to be plugged in is not looked into. */
    private fun emulators(stack: Stack): List<String> {
        val process = ProcessBuilder(stack.adb, "devices").redirectErrorStream(true).start()
        val serials = process.inputStream.bufferedReader().readLines().drop(1).mapNotNull { line ->
            line.split(Regex("\\s+")).takeIf { it.size >= 2 && it[1] == "device" }?.get(0)
        }
        process.waitFor(10, TimeUnit.SECONDS)
        return serials.filter { it.startsWith("emulator-") }
    }

    /**
     * The emulator signed in as the ride's rider, or null when none is running: the rider may be a peer with
     * no phone, and the car is driven all the same. With several, the caller has to say which.
     */
    private fun theRidersPhone(stack: Stack, ride: Ride, named: String?, driver: String): Phone? {
        if (named != null) return Phone(stack, named).also { it.requireTestDevice() }
        val riders = emulators(stack).filter { serial ->
            serial != driver && runCatching { stack.client(accountOf(Phone(stack, serial))).address.equals(ride.rider, ignoreCase = true) }.getOrDefault(false)
        }
        // `make android-two-phones` starts its phones in pairs, the rider on the port just before its driver's.
        // With several signed in as the rider (a test emulator left running, say), that one is the driver's own.
        val paired = driver.removePrefix("emulator-").toIntOrNull()?.let { "emulator-${it - 2}" }
        return when {
            riders.size == 1 -> Phone(stack, riders[0])
            riders.isEmpty() -> null.also { println("No running emulator is signed in as the ride's rider: only the car moves.") }
            paired in riders -> Phone(stack, paired!!)
            else -> throw UsageException(
                "Several phones are signed in as the rider (${riders.joinToString(", ")}). Say which: make android-drive RIDER=${riders[0]}, or klippelgo drive --rider ${riders[0]}",
            )
        }
    }

    /** The running emulator whose app is in driver mode. With several, the caller has to say which. */
    private fun theDriversPhone(stack: Stack): String {
        val drivers = emulators(stack).filter { runCatching { preferences(Phone(stack, it)).contains(">DRIVER<") }.getOrDefault(false) }
        return when (drivers.size) {
            1 -> drivers[0]
            0 -> throw UsageException("No running emulator has the app in driver mode. Start them: make android-two-phones")
            else -> throw UsageException("Several phones are driving (${drivers.joinToString(", ")}). Say which: klippelgo drive --phone ${drivers[0]}")
        }
    }

    /** The app's own settings. Readable because the builds the emulators run are debug builds. */
    private fun preferences(phone: Phone): String = phone.shell("run-as ${Phone.APP} cat shared_prefs/klippelgo.xml")

    private fun accountOf(phone: Phone): DevAccount {
        val prefs = runCatching { preferences(phone) }.getOrElse { throw UsageException("Could not read the app's settings on ${phone.serial}. Is KlippelGo installed there?") }
        val name = Regex("""<string name="account">(\w+)</string>""").find(prefs)?.groupValues?.get(1)
            ?: throw UsageException("The app on ${phone.serial} has no account yet. Open it and pick a role.")
        return DevAccount.valueOf(name)
    }

    // ---------------------------------------------------------------- the route

    /** The ride's route as the driver's app saved it after checking it, as a line on the map. */
    private fun routeOf(stack: Stack, phone: Phone, ride: Ride): List<Coordinate> {
        val file = "files/rides/${ride.quoteHash.lowercase()}.route.json"
        val quote = runCatching { phone.shell("run-as ${Phone.APP} cat $file") }.getOrElse {
            throw UsageException("The app on ${phone.serial} has no checked route for ride ${ride.id} yet. It shows \"rota conferida\" once it does.")
        }
        val checked = stack.pack.checkQuote(EscrowRef(stack.deployment.chainId.toULong(), stack.deployment.escrow), quote.trim())
        return checked.line
    }

    /** Where the emulator last said it was. */
    private fun whereIs(phone: Phone): Coordinate? {
        // Android writes the numbers in the phone's language: with a comma for the decimals in Portuguese.
        val number = """(-?\d+[.,]\d+)"""
        val match = Regex("""Location\[\w+ $number,$number""").find(phone.shell("dumpsys location")) ?: return null
        fun e7(text: String) = Math.round(text.replace(',', '.').toDouble() * 1e7).toInt()
        return Coordinate(e7(match.groupValues[1]), e7(match.groupValues[2]))
    }

    /**
     * The way by road from one point to another, from the router on the pack's tiles when it is running
     * (`tests/stack.sh up` starts it). Without it the car goes in a straight line, which is said.
     */
    private fun roadTo(stack: Stack, from: Coordinate, to: Coordinate): List<Coordinate> {
        val shape = runCatching {
            val request = HttpRequest.newBuilder(URI("${stack.valhalla}/route")).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(routeRequest(from, to, VehicleKind.CAR))).build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200)
            polyline(json.readTree(response.body()).get("trip").get("legs").get(0).get("shape").asText())
        }.getOrNull()
        if (shape != null && shape.size >= 2) return shape
        println("No router at ${stack.valhalla}: going to the pickup in a straight line. For the way by road: tests/stack.sh up")
        return listOf(from, to)
    }

    /** Valhalla's shape: a polyline with six decimals. */
    private fun polyline(encoded: String): List<Coordinate> {
        val points = mutableListOf<Coordinate>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var value = 0L
                while (true) {
                    val byte = encoded[i++].code - 63
                    value = value or ((byte and 0x1F).toLong() shl shift)
                    shift += 5
                    if (byte < 0x20) break
                }
                val delta = if (value and 1L == 1L) (value shr 1).inv() else value shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            points += Coordinate((lat * 10).toInt(), (lon * 10).toInt())
        }
        return points
    }

    // ---------------------------------------------------------------- along a line

    private fun metresBetween(a: Coordinate, b: Coordinate): Double {
        val north = (b.latE7 - a.latE7) * 0.0111195
        val east = (b.lonE7 - a.lonE7) * 0.0111195 * cos(Math.toRadians(a.latE7 / 1e7))
        return hypot(north, east)
    }

    private fun metres(line: List<Coordinate>): Double = line.zipWithNext().sumOf { (a, b) -> metresBetween(a, b) }

    /** The point a share of the way along a line, measured by distance: 0 is its start, 1 its end. */
    fun along(line: List<Coordinate>, share: Double): Coordinate {
        var left = metres(line) * share.coerceIn(0.0, 1.0)
        for ((a, b) in line.zipWithNext()) {
            val stretch = metresBetween(a, b)
            if (left <= stretch) {
                val t = if (stretch == 0.0) 0.0 else left / stretch
                return Coordinate((a.latE7 + (b.latE7 - a.latE7) * t).toInt(), (a.lonE7 + (b.lonE7 - a.lonE7) * t).toInt())
            }
            left -= stretch
        }
        return line.last()
    }
}
