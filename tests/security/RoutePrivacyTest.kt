package org.klippelgo.tests.security

import org.klippelgo.chain.DevAccount
import org.klippelgo.core.Coordinate
import org.klippelgo.core.regionCell
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a rider is and where they go leaves the phone only sealed to the driver they chose (invariant A9 and
 * the table "Who learns what" in `apps/docs/security.md`). A stranger records everything said in the cell while
 * the app asks for a ride, locks it and is driven, and then goes through the recording.
 *
 * The app is the rider here, so this says nothing of whom a driver's app sends its position to (A10).
 */
class RoutePrivacyTest : Scenario() {
    @Test
    fun `the cell never hears where the rider is or where they go`() {
        val stranger = stranger()
        val driver = peer(DevAccount.DRIVER)
        freshRider()
        app.whereTo.ask()
        app.ask.search("Avenida Brasil")
        app.ask.pick(0)
        app.ask.submit()

        val request = driver.awaitRequest(appRider)
        val offer = driver.offer(request)
        app.offers.awaitOffer(driver.address)
        app.offers.pick(driver.address)
        app.confirm.lock()
        app.onTheWay.awaitStatus("Open")
        val ride = driver.awaitRide(offer)
        // Only the chosen driver can open the route, and so only this test's driver knows the two points.
        val route = driver.awaitRoute(ride)
        driver.accept(ride.id)
        app.onTheWay.awaitStatus("Accepted")

        // The driver is somewhere the test chose; the rider's phone is told, sealed.
        val driverAt = Coordinate(route.pickup.latE7 + 31_337, route.pickup.lonE7 - 27_183)
        waitUntil("the rider to see the driver's position", 60_000) {
            driver.sharePosition(ride.id, driverAt)
            true.takeIf { app.onTheWay.driverLive() }
        }

        // A position from anyone but the ride's driver is not believed: a stranger cannot place a car on the
        // rider's map. The app is already showing the real one, so this checks only that nothing breaks.
        stranger.say(stranger.position(stranger.newKey(), ride.quoteHash), seconds = 3)
        assertTrue(app.onTheWay.driverLive())

        // What went over the wire as text, and what the sealed fields hold once their base64 is undone: if
        // sealing ever stopped encrypting, the points would be readable there.
        val unwrapped = stranger.heardJson().mapNotNull { it.get("sealed")?.asText() }
            .map { String(java.util.Base64.getDecoder().decode(it), Charsets.ISO_8859_1) }
        assertTrue(unwrapped.size >= 2, "the stranger heard no sealed route or position")
        val heard = stranger.heard() + unwrapped
        assertTrue(heard.any { "\"request\"" in it } && heard.any { "\"route\"" in it } && heard.any { "\"position\"" in it }, "the stranger heard too little to judge")

        // The request says what the docs say it says, and no more.
        val said = stranger.heardJson("request").first { it.get("rider").asText() == appRider }
        assertEquals(
            setOf("t", "rider", "token", "maxFare", "expiresAt", "nonce", "pack", "route", "distanceM", "durationS", "from", "to", "sig"),
            said.fieldNames().asSequence().toSet(),
        )
        // The regions are the right ones, about a square kilometre each, and the length is rounded.
        assertEquals(regionCell(route.pickup), said.get("from").asText())
        assertEquals(regionCell(route.dropoff), said.get("to").asText())
        assertEquals(0, said.get("distanceM").asInt() % 500)
        assertEquals(0, said.get("durationS").asInt() % 60)

        // Nothing in the clear names a point: not the pickup, not the drop-off, not where the driver is. In
        // integers as the app holds them, or in degrees to the fourth decimal (about ten metres).
        val secrets = listOf("the pickup" to route.pickup, "the drop-off" to route.dropoff, "the driver's position" to driverAt)
        for ((what, point) in secrets) {
            for (number in listOf(point.latE7, point.lonE7)) {
                val forms = listOf(number.toString(), "%.4f".format(java.util.Locale.ROOT, number / 1e7), (number / 1000).toString())
                for (form in forms) {
                    assertFalse(heard.any { form in it }, "$what went over gossip in the clear ($form)")
                }
            }
        }

        // The route and the position went out, sealed, and each named only who it was for.
        val reveal = stranger.heardJson("route").first()
        assertEquals(setOf("t", "request", "driver", "sealed", "key", "sig"), reveal.fieldNames().asSequence().toSet())
        assertEquals(driver.address, reveal.get("driver").asText())
        assertEquals(setOf("t", "quote", "sealed", "sig"), stranger.heardJson("position").first().fieldNames().asSequence().toSet())

        driver.cancel(ride.id)
    }
}
