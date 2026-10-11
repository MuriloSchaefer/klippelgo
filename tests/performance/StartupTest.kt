package org.klippelgo.tests.performance

import org.klippelgo.tests.Bench
import org.klippelgo.tests.Scenario
import kotlin.test.Test

/** How long a person waits before the app is of use. */
class StartupTest : Scenario() {
    @Test
    fun `starting the app`() {
        // Cold, with nothing on the phone: until Android has drawn the first screen.
        val cold = (1..3).map { phone.launch(fresh = true).also { app.welcome.shown() } }
        Bench.record("cold start to the welcome screen", Bench.median(cold), "ms", 6_000)

        // From tapping Continue on the create-account screen to a home screen showing the balance on chain.
        app.welcome.start()
        app.createAccount.fill("Ana", "rider", stack.rpcFromPhone)
        val connect = Bench.time {
            app.createAccount.submit()
            app.funding.skip()
            phone.await("the balance", 60_000) { screen -> screen["where-to.balance"]?.takeIf { it.text.any(Char::isDigit) } }
        }
        Bench.record("new account to a home screen with the balance", connect, "ms", 20_000)

        // Fetching the region's map pack from the mirror, checking every file and the proof root, opening it.
        val download = Bench.time { app.mapNotice.download() }
        Bench.record("map pack downloaded, verified and opened", download, "ms", 90_000)

        // Coming back: the pack is checked again against the chain and opened, and the map is drawn.
        val back = (1..3).map {
            Bench.time {
                phone.launch()
                phone.awaitTag("map", 60_000)
                phone.awaitTag("where-to.pickup", 60_000)
            }
        }
        Bench.record("reopening the app to a usable map", Bench.median(back), "ms", 20_000)
        Bench.record("memory with the map open", phone.memoryMb().toLong(), "MB", 900)
    }
}
