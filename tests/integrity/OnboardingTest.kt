package org.klippelgo.tests.integrity

import org.klippelgo.tests.Peer
import org.klippelgo.tests.Scenario
import org.klippelgo.tests.waitUntil
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** From a phone with nothing on it to a rider's home screen, and what the app keeps when it is closed. */
class OnboardingTest : Scenario() {
    @Test
    fun `a new rider creates an account, adds money and finds it on chain`() {
        val rider = stack.client(org.klippelgo.chain.DevAccount.RIDER)
        val before = rider.balanceOf(rider.brz)

        phone.launch(fresh = true)
        app.welcome.shown()
        // The language switch changes what the screen says, and Portuguese is what it starts in.
        assertEquals("pt", app.language.current())
        val portuguese = phone.read("welcome.start")
        app.language.pick("en")
        assertNotEquals(portuguese, phone.read("welcome.start"))
        app.language.pick("pt")
        assertEquals(portuguese, phone.read("welcome.start"))

        app.welcome.start()
        app.createAccount.shown()
        assertEquals("rider", app.createAccount.role())
        app.createAccount.fill("Ana", "rider", stack.rpcFromPhone)
        app.createAccount.submit()

        // A new rider is offered to add balance. R$ 50 in BRZ is what the screen proposes.
        app.funding.shown()
        app.funding.add()
        app.whereTo.shown()
        waitUntil("the R$ 50 to arrive on chain") { true.takeIf { rider.balanceOf(rider.brz) - before == rider.brz.toBaseUnits(BigDecimal(50)) } }

        // The balance on the map is the account's coins on chain, added up.
        val onChain = { rider.coins.fold(BigDecimal.ZERO) { sum, coin -> sum + coin.toReais(rider.balanceOf(coin)) }.setScale(2, java.math.RoundingMode.DOWN) }
        waitUntil("the app to show the balance on chain") { true.takeIf { app.whereTo.balance() == onChain() } }

        app.tabs.open("wallet")
        assertEquals(onChain(), app.wallet.total())
        assertEquals(rider.brz.toReais(rider.balanceOf(rider.brz)).setScale(2, java.math.RoundingMode.DOWN), app.wallet.balance("BRZ"))

        // The profile shows the name typed and the account the app signs with, and that both networks answer.
        app.tabs.open("profile")
        assertEquals("Ana", app.profile.name())
        assertEquals(Peer.short(appRider), app.profile.address().lowercase())
        waitUntil("the app to reach the chain and the gossip network") {
            true.takeIf { app.profile.chainConnected() == true && app.profile.gossipConnected() == true }
        }

        app.profile.signOut()
        app.welcome.shown()
    }

    @Test
    fun `the app comes back as it was left, with its map`() {
        freshRider()
        assertEquals("ready", app.mapNotice.status())

        phone.launch()
        // No welcome, no download: the role and the verified pack are still on the phone.
        app.whereTo.shown()
        phone.awaitTag("map")
        assertEquals("ready", app.mapNotice.status())
        assertTrue(app.whereTo.pickup().isNotBlank())
    }

    @Test
    fun `the rules screen shows the fare guide and the fees as they are on chain`() {
        freshRider()
        app.tabs.open("rules")
        val tariff = chain.tariff
        val shown = app.rules.tariff()
        fun reais(centavos: Long) = BigDecimal(centavos).movePointLeft(2)
        assertEquals(reais(tariff.baseCentavos), shown.base)
        assertEquals(reais(tariff.perKmCentavos), shown.perKm)
        assertEquals(reais(tariff.perMinuteCentavos), shown.perMinute)
        assertEquals(reais(tariff.minimumCentavos), shown.minimum)
        assertTrue((tariff.riderRangeBps / 100).toString() in shown.riderRange)
        assertTrue((tariff.driverDiscountBps / 100).toString() in shown.driverDiscount)
        // The government's tax as the escrow holds it, and what a ride costs the network at today's gas price.
        assertTrue(java.math.BigDecimal(chain.taxBps()).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ',') in app.rules.tax(), app.rules.tax())
        assertTrue(app.rules.infrastructure().startsWith("≈"), app.rules.infrastructure())
        for (coin in chain.coins) {
            val (fee, riderBond, driverBond) = chain.tokenConfig(coin)
            assertEquals(coin.toReais(fee).setScale(2), app.rules.fee(coin.symbol))
            assertEquals(coin.toReais(riderBond).setScale(2), app.rules.riderBond(coin.symbol))
            assertEquals(coin.toReais(driverBond).setScale(2), app.rules.driverBond(coin.symbol))
        }
    }
}
