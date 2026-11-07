package org.klippelgo.tests

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/**
 * Instituto Klippel's team council, acting the way its members do: with `gov/klippel-gov`. On the local
 * testnet the council's only member is Instituto Klippel (account #1), in every role, and every action runs at
 * once; [colleague] adds a second member, and [needApprovals] makes a change wait for them.
 *
 * Every change made here is undone when the test ends, in the reverse order, whether it passed or failed: the
 * chain is shared with the next test and the next run. Each undoing is registered before its change is sent,
 * and does nothing harmful when the change never happened, so a change that lands while the command reporting
 * it fails is undone too.
 */
class Governance(private val stack: Stack) : AutoCloseable {
    private val undo = ArrayDeque<() -> Unit>()
    private val json = ObjectMapper()

    /** The council's address, from the deployment file the app was built with. */
    val council: String = json.readTree(stack.deploymentFile).get("TeamCouncil")?.asText()
        ?: error("${stack.deploymentFile} has no TeamCouncil: deploy again with this code's contracts")

    /** Runs `klippel-gov` as Instituto Klippel, and gives what it printed. */
    fun gov(vararg args: String): String = stack.script("gov/klippel-gov", *args)

    /** Runs it as another member, signing with that member's key. */
    fun govAs(key: String, vararg args: String): String =
        stack.script(mapOf("KLIPPEL_GOV_WALLET" to "--private-key $key"), "gov/klippel-gov", *args)

    /** An action `klippel-gov` reported, and whether it ran in the same transaction. */
    data class Action(val id: Int, val ran: Boolean)

    /** Asks for a change (the words after `klippel-gov request`), as Instituto Klippel. */
    fun request(vararg change: String): Action = action(gov("request", *change))

    fun approve(key: String, action: Action): String = govAs(key, "approve", action.id.toString())

    private fun action(printed: String): Action {
        Regex("""Done: action (\d+) ran at once""").find(printed)?.let { return Action(it.groupValues[1].toInt(), true) }
        Regex("""Action (\d+) is waiting""").find(printed)?.let { return Action(it.groupValues[1].toInt(), false) }
        error("klippel-gov printed no action:\n$printed")
    }

    // ---------------------------------------------------------------- changes, each undone at the end

    /**
     * Sets the government's tax to `percent` of each fare, and puts back the rate it was. With [approver], the
     * change and its undoing each wait for that member's approval too, as they do once [needApprovals] made
     * the tax need two.
     */
    fun tax(percent: BigDecimal, approver: String? = null): Action {
        val before = percentOf(taxBps())
        undo.addFirst { restoreTax(before, approver) }
        val action = request("tax", words(percent))
        if (!action.ran && approver != null) approve(approver, action)
        return action
    }

    private fun restoreTax(rate: BigDecimal, approver: String?) {
        if (percentOf(taxBps()).compareTo(rate) == 0) return
        val back = request("tax", words(rate))
        if (!back.ran && approver != null) approve(approver, back)
    }

    /**
     * Asks for the tax change without anyone else's approval, so that it waits. At the end it is cancelled if
     * it is still waiting, or, if [approver] approved it meanwhile, the rate it was is put back with their
     * approval.
     */
    fun taxPending(percent: BigDecimal, approver: String): Action {
        val before = percentOf(taxBps())
        var pending: Action? = null
        undo.addFirst {
            // Left waiting, it would run for anyone once the tax needs one approval again: cancel it.
            pending?.let { if (gov("action", it.id.toString()).contains("waiting")) gov("cancel", it.id.toString()) }
            restoreTax(before, approver)
        }
        val action = request("tax", words(percent))
        pending = action
        check(!action.ran) { "the tax change ran at once: it was meant to wait for a second approval" }
        return action
    }

    /** The tax as the escrow holds it now, in basis points, read afresh. */
    fun taxBps(): Int =
        stack.script("cast", "call", "--rpc-url", stack.rpc, stack.deployment.escrow, "taxBps()(uint16)").trim().split(" ")[0].toInt()

    /** Changes the tariff's price per km, in reais, and puts back the one it was. */
    fun perKm(reais: BigDecimal, was: BigDecimal) {
        undo.addFirst { request("tariff", "perKm=${words(was)}") }
        request("tariff", "perKm=${words(reais)}")
    }

    /** Segurança's playbook: nobody may open or accept a ride. Undone with the board's `resume-rides`. */
    fun stopRides() {
        undo.addFirst { gov("resume-rides") }
        check(gov("stop-rides").contains("ran at once")) { "stop-rides did not run" }
    }

    /** The board's playbook that puts back the policies `stop-rides` switched off. */
    fun resumeRides() {
        check(gov("resume-rides").contains("ran at once")) { "resume-rides did not run" }
    }

    /** Segurança blocks an account, which is unblocked at the end if it still is. */
    fun block(account: String) {
        // klippel-gov refuses to unblock an account that is not blocked: nothing to undo then.
        undo.addFirst { runCatching { gov("unblock", account) } }
        gov("block", account)
    }

    fun unblock(account: String) {
        gov("unblock", account)
    }

    // ---------------------------------------------------------------- releases

    /** The release the app under test was built at, as its deployment file names it. */
    val release: String = json.readTree(stack.deploymentFile).get("version")?.asText()
        ?: error("${stack.deploymentFile} names no release version: deploy again with this code's contracts")

    /** `0` none, `1` active, `2` deprecated, `3` revoked, read afresh from the release registry. */
    fun releaseStatus(version: String = release): Int = stack.script(
        "cast", "call", "--rpc-url", stack.rpc, stack.deployment.releases, "statusOf(uint48)(uint8)",
        org.klippelgo.chain.Deployment.packVersion(version).toString(),
    ).trim().split(" ")[0].toInt()

    /** The board deprecates the app's release. It is put back at the end. */
    fun deprecateRelease() {
        undo.addFirst { reinstate(release) }
        request("release", "deprecate", release)
    }

    /** Segurança revokes the app's release, in the registry only. It is put back at the end. */
    fun revokeRelease() {
        undo.addFirst { reinstate(release) }
        request("release", "revoke", release)
    }

    /** The board puts the app's release back, as it does once a revoke turns out not to be needed. */
    fun reinstateRelease() = reinstate(release)

    private fun reinstate(version: String) {
        if (releaseStatus(version) in 2..3) request("release", "reinstate", version)
    }

    /**
     * The board publishes the next release after the latest, naming `apks` (files) as its APKs, and gives its
     * version. A release cannot be unpublished: at the end it is revoked, so no phone is offered it.
     */
    fun publishNextRelease(vararg apks: java.io.File): String {
        val latest = stack.script("cast", "call", "--rpc-url", stack.rpc, stack.deployment.releases, "latest()(uint48)").trim().split(" ")[0].toLong()
        val next = org.klippelgo.chain.Deployment.versionText(latest + 1)
        undo.addFirst { if (releaseStatus(next) in 1..2) request("release", "revoke", next) }
        request("release", "publish", next, *apks.map { it.path }.toTypedArray())
        return next
    }

    /**
     * A second member of a role (`finance`, `security`, …), with a key of its own and gas: Instituto Klippel,
     * as the board, adds them. They leave the role at the end. Gives the member's private key.
     */
    fun colleague(role: String): String {
        val keyPair = Keys.createEcKeyPair()
        val key = Numeric.toHexStringWithPrefixZeroPadded(keyPair.privateKey, 64)
        val address = "0x" + Keys.getAddress(keyPair)
        stack.script("cast", "rpc", "--rpc-url", stack.rpc, "anvil_setBalance", address, "0xde0b6b3a7640000")
        undo.addFirst {
            if (stack.script("cast", "call", "--rpc-url", stack.rpc, council, "rolesOf(address)(uint256)", address).trim().split(" ")[0] != "0") {
                gov("team", "remove", address, role)
            }
        }
        gov("team", "add", address, role)
        return key
    }

    /** Makes the permission over this function (a signature, `setTax(uint16,address)`) need `n` approvals. */
    fun needApprovals(signature: String, n: Int) {
        val id = permissionFor(signature)
        val before = permission(id)[4].asInt()
        undo.addFirst { if (permission(id)[4].asInt() != before) gov("team", "set", id.toString(), "approvals=$before") }
        gov("team", "set", id.toString(), "approvals=$n")
    }

    /** The council's permission over the function with this signature. */
    fun permissionFor(signature: String): Int {
        val selector = stack.script("cast", "sig", signature).trim()
        val count = stack.script("cast", "call", "--rpc-url", stack.rpc, council, "permissionCount()(uint256)").trim().split(" ")[0].toInt()
        return (0 until count).firstOrNull { permission(it)[7].asText() == selector }
            ?: error("the council has no permission over $signature")
    }

    private fun permission(id: Int) = json.readTree(
        stack.script(
            "cast", "call", "--json", "--rpc-url", stack.rpc, council,
            "permission(uint32)((uint8,uint8,bool,bool,uint8,uint32,address,bytes4,uint16,bytes32))", id.toString(),
        ),
    ).let { if (it.has("data")) it.get("data") else it }.get(0)

    override fun close() {
        val failures = mutableListOf<Throwable>()
        while (undo.isNotEmpty()) runCatching { undo.removeFirst()() }.onFailure { failures += it }
        failures.firstOrNull()?.let { throw IllegalStateException("could not put governance back as it was", it) }
    }

    private fun percentOf(bps: Int): BigDecimal = BigDecimal(bps).movePointLeft(2)

    /** A number as klippel-gov reads it: a comma for the decimals, never a separator for thousands. */
    private fun words(n: BigDecimal): String = n.stripTrailingZeros().toPlainString().replace('.', ',')
}
