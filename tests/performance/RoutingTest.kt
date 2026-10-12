package org.klippelgo.tests.performance

import org.klippelgo.tests.Bench
import org.klippelgo.tests.Scenario
import kotlin.test.Test

/** Search and routing happen on the phone, with no server to wait for. This is how long the phone takes. */
class RoutingTest : Scenario() {
    @Test
    fun `finding a place and measuring the route to it`() {
        freshRider()
        app.whereTo.ask()
        app.ask.shown()

        val searches = mutableListOf<Long>()
        val routes = mutableListOf<Long>()
        for (query in listOf("Avenida Brasil", "Rua Paraná", "Catedral", "Rodoviária", "Hospital")) {
            searches += Bench.time {
                phone.type("destination.search", query)
                phone.await("places for \"$query\"") { it["destination.place.0"] }
            }
            // From picking the place until the route has been found by Valhalla and measured by the verifier.
            routes += Bench.time {
                phone.tap("destination.place.0")
                app.ask.route()
            }
            app.ask.changeDestination()
            phone.awaitTag("destination.search")
        }
        Bench.record("address search, typed text to first result (median of 5)", Bench.median(searches), "ms", 3_000)
        Bench.record("address search, slowest of 5", searches.max(), "ms", 6_000)
        Bench.record("route found and verified on the phone (median of 5)", Bench.median(routes), "ms", 5_000)
        Bench.record("route found and verified on the phone, slowest of 5", routes.max(), "ms", 10_000)
    }
}
