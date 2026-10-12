package org.klippelgo.tests.drivers

import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.tests.Phone
import org.klippelgo.tests.reais
import java.math.BigDecimal

/** The first screen of a phone with no account. */
class Welcome(phone: Phone) : Component(phone, Tags.WELCOME) {
    fun start() = phone.tap(Tags.WELCOME_START)
}

/** Name, role and node address, before the app connects. */
class CreateAccount(phone: Phone) : Component(phone, Tags.CREATE_ACCOUNT) {
    /** `role` is rider or driver. An empty `rpc` keeps the address the screen proposes. */
    fun fill(name: String, role: String, rpc: String = "") {
        phone.type(Tags.CREATE_NAME, name)
        phone.tap(if (role.equals("driver", ignoreCase = true)) Tags.CREATE_ROLE_DRIVER else Tags.CREATE_ROLE_RIDER)
        if (rpc.isNotBlank()) phone.type(Tags.CREATE_RPC, rpc)
    }

    fun name(): String = read(Tags.CREATE_NAME)

    fun rpc(): String = read(Tags.CREATE_RPC)

    fun role(): String = if (phone.reach(Tags.CREATE_ROLE_DRIVER).selected) "driver" else "rider"

    fun submit() = phone.tap(Tags.CREATE_CONTINUE)
}

/** What a finished ride shows, to the rider who pays by confirming and to either side when rating. */
data class RideEndInfo(
    val ride: String,
    val amount: BigDecimal,
    val paid: Boolean,
    /** The government's tax on the ride. */
    val tax: BigDecimal,
    /** What the ride cost the network on this side, as shown: exact once this phone paid, "≈" before. Or null. */
    val infrastructure: String?,
)

/** The end of a ride, for both sides: the rider confirms arrival and pays, and each rates the other. */
class RideEnd(phone: Phone) : Component(phone, Tags.RIDE_END) {
    fun info(): RideEndInfo {
        phone.awaitTag(tag)
        return RideEndInfo(
            ride = Regex("""\d+""").find(read(Tags.RIDE_END_RIDE))?.value ?: "",
            amount = reais(read(Tags.RIDE_END_AMOUNT)),
            paid = phone.find(Tags.RIDE_END_RATE) != null,
            tax = reais(read(Tags.RIDE_END_TAX)),
            infrastructure = phone.find(Tags.RIDE_END_INFRASTRUCTURE)?.text,
        )
    }

    /** Picks 1 to 5 stars. */
    fun stars(n: Int) = phone.tap(Tags.star(Tags.RIDE_END_STARS, n))

    /** Rider: confirms arrival, which pays the driver, with whatever stars are picked. */
    fun pay() = phone.tap(Tags.RIDE_END_PAY)

    /** Sends the rating for a ride already paid. */
    fun rate() = phone.tap(Tags.RIDE_END_RATE)

    fun skip() = phone.tap(Tags.RIDE_END_SKIP)
}
