package org.klippelgo.tests.security

import org.klippelgo.chain.DevAccount
import org.klippelgo.tests.LyingRpc
import org.klippelgo.tests.Scenario
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A second RPC provider the app checks its full reads against (P3 in apps/docs/security.md). While the two
 * disagree about what the screen shows, the app says so and starts no new ride; once they agree again, it goes on.
 * The second provider here is the stack's own node behind a proxy that lies about the tariff.
 */
class ProvidersTest : Scenario() {
    private fun askForARide() {
        app.whereTo.ask()
        app.ask.shown()
        assertTrue(app.ask.search("Avenida Brasil").isNotEmpty())
        app.ask.pick(0)
        app.ask.submit()
    }

    @Test
    fun `two providers that disagree stop new rides and say so, and agreeing again lets them start`() {
        LyingRpc(stack, "tariff()").use { liar ->
            freshRider(checkRpc = liar.urlFromPhone)
            app.providersNotice.awaitShown()
            assertTrue(!app.providersNotice.text().isNullOrBlank())

            // The app refuses to ask for a ride, and says why.
            askForARide()
            app.error.shown()
            assertTrue(!app.error.message().isNullOrBlank())
            app.error.dismiss()
            app.error.gone()
            // Back where the notice shows: the screen for asking covers it.
            app.back.system()
            app.whereTo.shown()
            app.providersNotice.shown()

            // The lie stops: the next full read finds them agreeing, and a driver hears the request.
            liar.lying = false
            app.providersNotice.awaitGone()
            val driver = peer(DevAccount.E2E_DRIVER)
            askForARide()
            app.offers.shown()
            driver.awaitRequest(appRider)
        }
    }
}
