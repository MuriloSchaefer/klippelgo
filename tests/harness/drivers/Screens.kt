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
    /**
     * `role` is rider or driver. An empty `rpc` keeps the address the screen proposes; `checkRpc` is the second
     * provider every full read is checked against, left as it is when empty.
     */
    fun fill(name: String, role: String, rpc: String = "", checkRpc: String = "") {
        phone.type(Tags.CREATE_NAME, name)
        phone.tap(if (role.equals("driver", ignoreCase = true)) Tags.CREATE_ROLE_DRIVER else Tags.CREATE_ROLE_RIDER)
        if (rpc.isNotBlank()) phone.type(Tags.CREATE_RPC, rpc)
        if (checkRpc.isNotBlank()) phone.type(Tags.CREATE_CHECK_RPC, checkRpc)
    }

    fun checkRpc(): String = read(Tags.CREATE_CHECK_RPC)

    fun name(): String = read(Tags.CREATE_NAME)

    fun rpc(): String = read(Tags.CREATE_RPC)

    fun role(): String = if (phone.reach(Tags.CREATE_ROLE_DRIVER).selected) "driver" else "rider"

    fun submit() = phone.tap(Tags.CREATE_CONTINUE)

    /** "I already have an account": this phone joins one instead of making its own. */
    fun join() = phone.tap(Tags.CREATE_JOIN)
}

/** A new phone joining an account the person has: the code it shows, and the accounts its key was added to. */
class Join(phone: Phone) : Component(phone, Tags.JOIN) {
    /** The code to give a device already on the account, or the guardian. */
    fun code(): String = read(Tags.JOIN_CODE).trim()

    /** The fingerprint the screen shows under the code, without spaces. */
    fun fingerprint(): String = Regex("""([0-9a-f]{4} ){3}[0-9a-f]{4}""").find(read(Tags.JOIN_FINGERPRINT))?.value?.replace(" ", "") ?: ""

    /** The accounts this phone's key was added to, as found so far. */
    fun found(): List<String> = phone.screen().startingWith("join.found.").map { it.tag.removePrefix("join.found.") }

    /** Waits until the phone finds `account` has its key. */
    fun awaitFound(account: String, timeoutMs: Long = 90_000): Boolean {
        phone.awaitTag(Tags.joinFound(account), timeoutMs)
        return true
    }

    /** Whether the screen says a guardian's recovery of `account` to this phone is waiting. */
    fun recoveryWaiting(account: String): Boolean = phone.find(Tags.joinRecovery(account)) != null

    fun awaitRecovery(account: String, timeoutMs: Long = 90_000): Boolean {
        phone.awaitTag(Tags.joinRecovery(account), timeoutMs)
        return true
    }

    /** Uses `account`, which the person checked by its address. */
    fun use(account: String) = phone.tap(Tags.joinUse(account))
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
