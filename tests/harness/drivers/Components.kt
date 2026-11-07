package org.klippelgo.tests.drivers

import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.tests.Phone

/**
 * A driver for one piece of the app's UI: what a person can read on it and do with it, and nothing else. Each
 * composable in `apps/android/app/.../ui` that a person meets has one, in the file of the same name here. The
 * tests and the CLI go through these and never touch a tag or a coordinate themselves.
 *
 * Every public function is also a CLI command: `klippelgo ui <component> <function> [arguments]`.
 */
abstract class Component(protected val phone: Phone, val tag: String) {
    /** Waits until this piece is on screen. */
    open fun shown(): Boolean {
        phone.awaitTag(tag)
        return true
    }

    /** Whether this piece is on screen right now. */
    open fun visible(): Boolean = phone.find(tag) != null

    /** Waits until this piece has left the screen. */
    open fun gone(): Boolean {
        phone.awaitGone(tag)
        return true
    }

    protected fun read(tag: String): String = phone.read(tag)

    protected fun readOrNull(tag: String): String? = phone.find(tag)?.text
}

/** The dialog that says an action failed, with the reason the chain or the app gave. */
class ErrorDialog(phone: Phone) : Component(phone, Tags.ERROR) {
    fun message(): String? = readOrNull(Tags.ERROR_MESSAGE)

    fun dismiss() = phone.tap(Tags.ERROR_DISMISS)
}

/** The red notice that the chain node or the gossip network is out of reach. */
class NetworkNotice(phone: Phone) : Component(phone, Tags.NETWORK_NOTICE) {
    fun text(): String? = readOrNull(tag)
}

/** The PT / EN / ES switch, on the welcome screen and in the profile. */
class Language(phone: Phone) : Component(phone, Tags.language("pt")) {
    /** `code` is pt, en or es. */
    fun pick(code: String) {
        // On its first start the app sets Portuguese, which redraws the screen: a tap that lands while it does is
        // lost, and a person taps again. Tapping a language already chosen does nothing, so tapping again is safe.
        phone.await("the app to switch to $code") { screen ->
            val chip = screen[Tags.language(code)]
            when {
                chip?.selected == true -> true
                chip != null -> { runCatching { phone.tap(Tags.language(code)) }; Thread.sleep(1_000); null }
                else -> null
            }
        }
    }

    fun current(): String = listOf("pt", "en", "es").first { phone.reach(Tags.language(it)).selected }
}

/** The back arrow at the top of a screen. */
class Back(phone: Phone) : Component(phone, Tags.BACK) {
    fun tap() = phone.tap(tag)

    /** The phone's own back button or gesture. */
    fun system() = phone.back()
}

/** The map. Moving it under the pin is how a rider says where the car should come. */
class MapView(phone: Phone) : Component(phone, Tags.MAP) {
    /**
     * Drags the map by a share of its own size: `right` 0.25 moves what is shown a quarter of the map's width
     * to the right, so the pin ends up west of where it was.
     */
    fun drag(right: Double, down: Double) {
        val map = phone.awaitTag(tag)
        val dx = ((map.right - map.left) * right).toInt()
        val dy = (map.height * down).toInt()
        // Slowly, so the map stops where the finger does instead of being flung.
        phone.swipe(map.x - dx / 2, map.y - dy / 2, map.x + dx / 2, map.y + dy / 2, steps = 120)
    }

    fun zoomIn() = phone.tap(Tags.MAP_ZOOM_IN)

    fun zoomOut() = phone.tap(Tags.MAP_ZOOM_OUT)
}

/** What the release registry says of the app's release, above every screen, with the button to update. */
class ReleaseNotice(phone: Phone) : Component(phone, Tags.RELEASE_NOTICE) {
    /** `revoked`, `deprecated`, `update` (a newer release is out), or `none` when there is no notice. */
    fun status(): String =
        phone.screen().startingWith(Tags.releaseNoticeStatus("")).firstOrNull()?.tag?.removePrefix(Tags.releaseNoticeStatus("")) ?: "none"

    /** Waits until the notice says `status` (`none` waits for it to leave), as the app reads the registry again. */
    fun awaitStatus(status: String, timeoutMs: Long = 90_000): Boolean {
        phone.await("the release notice to say $status", timeoutMs) { screen ->
            val now = screen.startingWith(Tags.releaseNoticeStatus("")).firstOrNull()?.tag?.removePrefix(Tags.releaseNoticeStatus("")) ?: "none"
            true.takeIf { now == status }
        }
        return true
    }

    fun text(): String? = readOrNull(Tags.RELEASE_NOTICE_TEXT)

    /** Whether the notice offers to update. */
    fun offersUpdate(): Boolean = phone.find(Tags.RELEASE_NOTICE_UPDATE) != null

    /** Whether the notice holds the update back until the ride under way is finished. */
    fun updateWaitsForRide(): Boolean = phone.find(Tags.RELEASE_NOTICE_AFTER_RIDE) != null

    /** Taps the update button. What follows is Android's installer, or the reason the update was refused. */
    fun update() = phone.tap(Tags.RELEASE_NOTICE_UPDATE)

    /** Why the last update did not install, when it did not. */
    fun failure(): String? = readOrNull(Tags.RELEASE_NOTICE_FAILED)
}

/** What escrows of earlier releases still hold for this account: rides to finish there, and money to withdraw. */
class PreviousRelease(phone: Phone) : Component(phone, Tags.PREVIOUS_RELEASE) {
    fun rides(): String? = readOrNull(Tags.PREVIOUS_RELEASE_RIDES)

    fun owed(escrow: String): String? = readOrNull(Tags.previousReleaseOwed(escrow))

    /** Takes back what that escrow owes, and waits until the screen no longer shows it. */
    fun withdraw(escrow: String) {
        phone.tap(Tags.previousReleaseWithdraw(escrow))
        phone.awaitGone(Tags.previousReleaseOwed(escrow), 60_000)
    }
}

/** The card that stands where the map's controls would be while the region's map pack is not on the phone. */
class MapNotice(phone: Phone) : Component(phone, Tags.MAP_NOTICE) {
    /** `checking`, `not-published`, `not-downloaded`, `downloading`, `failed`, or `ready` when there is no notice. */
    fun status(): String =
        phone.screen().startingWith(Tags.mapNoticeStatus("")).firstOrNull()?.tag?.removePrefix(Tags.mapNoticeStatus("")) ?: "ready"

    /** Taps the download button and waits for the pack to be fetched, checked and opened. */
    fun download() {
        phone.tap(Tags.MAP_NOTICE_DOWNLOAD, timeoutMs = 60_000)
        phone.await("the map pack to download and open", 180_000) { screen ->
            check(!screen.has(Tags.mapNoticeStatus(Tags.MAP_FAILED))) { "The map pack failed: " + screen[Tags.mapNoticeStatus(Tags.MAP_FAILED)]?.text }
            true.takeIf { !screen.has(tag) && screen.has(Tags.MAP) }
        }
    }
}
