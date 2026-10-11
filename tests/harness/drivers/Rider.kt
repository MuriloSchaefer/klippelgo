package org.klippelgo.tests.drivers

import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.tests.Phone
import org.klippelgo.tests.reais
import java.math.BigDecimal

/** The rider's four tabs: ride, wallet, rules, profile. */
class Tabs(phone: Phone) : Component(phone, Tags.TABS) {
    /** `name` is ride, wallet, rules or profile. */
    fun open(name: String) {
        phone.tap(Tags.tab(name))
        phone.await("the $name tab to open") { screen -> screen[Tags.tab(name)]?.takeIf { it.selected } }
    }

    fun selected(): String = listOf("ride", "wallet", "rules", "profile").first { phone.reach(Tags.tab(it)).selected }
}

/** The rider's home: the map with the pickup pin, the balance, and the way in to asking for a ride. */
class WhereTo(phone: Phone) : Component(phone, Tags.WHERE_TO) {
    /** The balance on the map, all coins together. Null until the chain has answered. */
    fun balance(): BigDecimal? = read(Tags.WHERE_TO_BALANCE).takeIf { it.any(Char::isDigit) }?.let(::reais)

    /** What the map calls the spot under the pin. */
    fun pickup(): String = read(Tags.WHERE_TO_PICKUP)

    /** Taps "Para onde?". */
    fun ask() = phone.tap(Tags.WHERE_TO_ASK)

    fun profile() = phone.tap(Tags.WHERE_TO_PROFILE)
}

data class HistoryRide(val ride: String, val status: String, val rated: Boolean, val text: String)

/** Balances, adding test money, and past rides. */
open class Wallet(phone: Phone, tag: String = Tags.WALLET) : Component(phone, tag) {
    fun total(): BigDecimal = reais(read(Tags.WALLET_TOTAL))

    /** `symbol` is BRZ or BRLA. */
    fun balance(symbol: String): BigDecimal = reais(read(Tags.walletBalance(symbol)))

    /** `reais` is 20, 50 or 100. */
    fun pickAmount(reais: Int) = phone.tap(Tags.walletAmount(reais))

    fun pickCoin(symbol: String) = phone.tap(Tags.walletCoin(symbol))

    /** Adds the picked amount of the picked coin. */
    fun add() = phone.tap(Tags.WALLET_ADD)

    fun history(): List<HistoryRide> {
        phone.reach(Tags.HISTORY)
        val screen = phone.screen()
        return screen.nodes.filter { Regex("""history\.ride\.\d+""").matches(it.tag) }.map { card ->
            val id = card.tag.removePrefix("history.ride.")
            val states = screen.startingWith("history.ride.$id.status.").map { it.tag.substringAfterLast('.') }
            HistoryRide(id, states.firstOrNull { it != Tags.HISTORY_RATED } ?: "", Tags.HISTORY_RATED in states, card.text)
        }
    }

    /** Rates a past ride from its card. */
    fun rate(ride: String, stars: Int) = phone.tap(Tags.star(Tags.historyStars(ride), stars))
}

/** The wallet as shown right after creating a rider's account, offering to add balance before the first ride. */
class Funding(phone: Phone) : Wallet(phone, Tags.FUNDING) {
    fun skip() = phone.tap(Tags.WALLET_SKIP)
}

/** What the fare guide and the escrow charge today, as the app read them from the chain. */
data class RulesShown(
    val base: BigDecimal,
    val perKm: BigDecimal,
    val perMinute: BigDecimal,
    val minimum: BigDecimal,
    val riderRange: String,
    val driverDiscount: String,
)

class Rules(phone: Phone) : Component(phone, Tags.RULES) {
    fun tariff(): RulesShown = RulesShown(
        base = reais(read(Tags.RULES_BASE)),
        perKm = reais(read(Tags.RULES_PER_KM)),
        perMinute = reais(read(Tags.RULES_PER_MINUTE)),
        minimum = reais(read(Tags.RULES_MINIMUM)),
        riderRange = read(Tags.RULES_RIDER_RANGE),
        driverDiscount = read(Tags.RULES_DRIVER_DISCOUNT),
    )

    fun fee(symbol: String): BigDecimal = reais(read(Tags.rulesFee(symbol)))

    fun riderBond(symbol: String): BigDecimal = reais(read(Tags.rulesRiderBond(symbol)))

    fun driverBond(symbol: String): BigDecimal = reais(read(Tags.rulesDriverBond(symbol)))
}

class Profile(phone: Phone) : Component(phone, Tags.PROFILE) {
    fun name(): String = read(Tags.PROFILE_NAME)

    /** The account's address as the app shortens it: the first six characters and the last four. */
    fun address(): String = read(Tags.PROFILE_ADDRESS)

    /** Whether the app says it reaches the chain node. Null while it does not know yet. */
    fun chainConnected(): Boolean? = when {
        phone.find(Tags.profileChain(true)) != null -> true
        phone.find(Tags.profileChain(false)) != null -> false
        else -> null
    }

    fun gossipConnected(): Boolean? = when {
        phone.find(Tags.profileGossip(true)) != null -> true
        phone.find(Tags.profileGossip(false)) != null -> false
        else -> null
    }

    fun driverMode() = phone.tap(Tags.PROFILE_DRIVER_MODE)

    fun signOut() = phone.tap(Tags.PROFILE_SIGN_OUT)
}

/** Asking for a ride, before the request goes out: where to, and how much at most. */
class Ask(phone: Phone) : Component(phone, Tags.ASK) {
    /** Types into the destination search and gives back the places found, in order. */
    fun search(query: String): List<String> {
        phone.type(Tags.DESTINATION_SEARCH, query)
        phone.await("places for \"$query\"") { screen -> screen[Tags.destinationPlace(0)] }
        // The list fills as the search settles; read it once it has stopped changing.
        var last = emptyList<String>()
        repeat(10) {
            Thread.sleep(200)
            val now = places()
            if (now == last && now.isNotEmpty()) return now
            last = now
        }
        return last
    }

    fun places(): List<String> = phone.screen().nodes.filter { Regex("""destination\.place\.\d+""").matches(it.tag) }.map { it.text }

    /** Picks the nth place found, counting from 0, and waits for the route to it to be measured. */
    fun pick(index: Int): String {
        phone.tap(Tags.destinationPlace(index))
        return route()
    }

    fun destination(): String = read(Tags.DESTINATION_PICKED)

    /** The measured route, as "8,5 km · 22 min". Waits while the phone is routing; fails if it found no route. */
    fun route(): String = phone.await("the route to be measured", 60_000) { screen ->
        screen[Tags.DESTINATION_ROUTE_ERROR]?.let { error("The app found no route: ${it.text}") }
        screen[Tags.DESTINATION_ROUTE]?.text
    }

    fun changeDestination() = phone.tap(Tags.DESTINATION_CHANGE)

    /** The price the rider is about to ask. */
    fun price(): BigDecimal = reais(read(Tags.ASK_PRICE))

    /** What the fare guide recommends for the route. */
    fun recommended(): BigDecimal = reais(read(Tags.ASK_RECOMMENDED))

    /** Lowers the price by one step of R$ 0,50. False when the fare guide allows no lower. */
    fun lower(): Boolean = step(Tags.ASK_LOWER)

    fun raise(): Boolean = step(Tags.ASK_RAISE)

    private fun step(button: String): Boolean {
        if (!phone.reach(button).enabled) return false
        phone.tap(button)
        return true
    }

    fun pickCoin(symbol: String) = phone.tap(Tags.askCoin(symbol))

    /** Whether the app says the balance does not cover the price and the bond. */
    fun notEnoughBalance(): Boolean = phone.find(Tags.ASK_NOT_ENOUGH) != null

    /** Sends the request out to the drivers. */
    fun submit() = phone.tap(Tags.ASK_SUBMIT)
}

/** The request is out: the drivers' quotes as they arrive, cheapest first. */
class Offers(phone: Phone) : Component(phone, Tags.OFFERS) {
    fun trip(): String = read(Tags.OFFERS_TRIP)

    fun price(): BigDecimal = reais(read(Tags.OFFERS_PRICE))

    /** Each quote on screen: the driver's address and the fare, in the order shown. */
    fun offers(): Map<String, BigDecimal> =
        phone.screen().startingWith(Tags.offer("")).associate { it.tag.removePrefix(Tags.offer("")) to reais(it.text) }

    /** Waits for a quote from this driver and gives its fare. */
    fun awaitOffer(driver: String): BigDecimal = phone.await("an offer from $driver") { it[Tags.offer(driver)] }.reais()

    /** Picks a driver's quote, which opens the confirmation. */
    fun pick(driver: String) = phone.tap(Tags.offer(driver))

    /** Whether the app says the driver gave the ride up and the request went out again. */
    fun askedAgain(): Boolean = phone.find(Tags.OFFERS_ASKED_AGAIN) != null

    /** While nobody quotes: the price to ask again at. */
    fun newPrice(): BigDecimal = reais(read(Tags.REPRICE_PRICE))

    fun lowerPrice() = phone.tap(Tags.REPRICE_LOWER)

    fun raisePrice() = phone.tap(Tags.REPRICE_RAISE)

    /** Withdraws the request and asks again at the new price. */
    fun askAgain() = phone.tap(Tags.REPRICE_SUBMIT)
}

/** The amounts the confirmation shows, which are the amounts about to be locked. */
data class ConfirmShown(val fare: BigDecimal, val toDriver: BigDecimal, val fee: BigDecimal, val bond: BigDecimal, val toLock: BigDecimal)

/** The last look before locking the fare in the escrow. */
class Confirm(phone: Phone) : Component(phone, Tags.CONFIRM) {
    fun amounts(): ConfirmShown = ConfirmShown(
        fare = reais(read(Tags.CONFIRM_FARE)),
        toDriver = reais(read(Tags.CONFIRM_TO_DRIVER)),
        fee = reais(read(Tags.CONFIRM_FEE)),
        bond = reais(read(Tags.CONFIRM_BOND)),
        toLock = reais(read(Tags.CONFIRM_LOCK)),
    )

    fun driver(): String = read(Tags.CONFIRM_DRIVER)

    /** Locks the fare and the bond. */
    fun lock() = phone.tap(Tags.CONFIRM_LOCK)
}

/** From the fare being locked until the driver starts the ride. */
class OnTheWay(phone: Phone) : Component(phone, Tags.ON_THE_WAY) {
    /** `Open` while the driver has not answered, `Accepted` once they are coming. */
    fun status(): String =
        phone.await("the ride's status") { it.startingWith(Tags.onTheWayStatus("")).firstOrNull() }.tag.removePrefix(Tags.onTheWayStatus(""))

    fun awaitStatus(status: String): Boolean {
        phone.awaitTag(Tags.onTheWayStatus(status), 60_000)
        return true
    }

    /** What the pill on the map says is locked for the ride: the fare and the rider's bond. */
    fun locked(): BigDecimal = reais(read(Tags.ON_THE_WAY_LOCKED))

    fun driver(): String = read(Tags.ON_THE_WAY_DRIVER)

    /** Whether the app says the driver is still finishing someone else's ride. */
    fun driverBusy(): Boolean = phone.find(Tags.DRIVER_BUSY) != null

    /** Whether the app is showing the driver's live position. */
    fun driverLive(): Boolean = phone.find(Tags.DRIVER_LIVE) != null

    fun awaitDriverLive(): Boolean {
        phone.awaitTag(Tags.DRIVER_LIVE, 60_000)
        return true
    }

    fun cancel() = phone.tap(Tags.ON_THE_WAY_CANCEL)
}

/** The ride is under way. */
class OnTrip(phone: Phone) : Component(phone, Tags.ON_TRIP) {
    fun fare(): BigDecimal = reais(read(Tags.ON_TRIP_FARE))

    /** Taps "Cheguei", which opens the screen that confirms and pays. */
    fun arrived() = phone.tap(Tags.ON_TRIP_ARRIVED)
}
