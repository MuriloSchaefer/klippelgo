package org.klippelgo.app.ui.tags

/**
 * The name of every piece of the UI a test can look at or touch. The composables carry these as test tags, and
 * the test harness (`tests/harness/drivers`) compiles this same file, so a tag renamed here is renamed there.
 *
 * A tag is `<component>.<element>`. A component's own tag is on its outermost box: it being on screen is how a
 * test knows which screen the phone shows. This file must stay plain Kotlin, with no Android import.
 */
object Tags {
    // ---------------------------------------------------------------- everywhere
    const val ERROR = "error"
    const val ERROR_MESSAGE = "error.message"
    const val ERROR_DISMISS = "error.dismiss"
    const val NETWORK_NOTICE = "network-notice"
    const val BACK = "back"

    /** `language.pt`, `language.en`, `language.es`. */
    fun language(code: String) = "language.$code"

    const val MAP = "map"
    const val MAP_ZOOM_IN = "map.zoom-in"
    const val MAP_ZOOM_OUT = "map.zoom-out"

    /** Shown while the map pack is not ready: what is missing, and the button to fetch it. */
    const val MAP_NOTICE = "map-notice"

    /** `state` is one of the names below. The text beside it is in the app's language; the tag is not. */
    fun mapNoticeStatus(state: String) = "map-notice.status.$state"
    const val MAP_CHECKING = "checking"
    const val MAP_NOT_PUBLISHED = "not-published"
    const val MAP_NOT_DOWNLOADED = "not-downloaded"
    const val MAP_DOWNLOADING = "downloading"
    const val MAP_FAILED = "failed"
    const val MAP_NOTICE_DOWNLOAD = "map-notice.download"

    // ---------------------------------------------------------------- onboarding
    const val WELCOME = "welcome"
    const val WELCOME_START = "welcome.start"

    const val CREATE_ACCOUNT = "create-account"
    const val CREATE_NAME = "create-account.name"
    const val CREATE_ROLE_RIDER = "create-account.role.rider"
    const val CREATE_ROLE_DRIVER = "create-account.role.driver"
    const val CREATE_RPC = "create-account.rpc"
    const val CREATE_CONTINUE = "create-account.continue"

    // ---------------------------------------------------------------- rider
    const val TABS = "tabs"

    /** `tabs.ride`, `tabs.wallet`, `tabs.rules`, `tabs.profile`. */
    fun tab(name: String) = "tabs.$name"

    const val WHERE_TO = "where-to"
    const val WHERE_TO_BALANCE = "where-to.balance"
    const val WHERE_TO_PROFILE = "where-to.profile"
    const val WHERE_TO_PICKUP = "where-to.pickup"
    const val WHERE_TO_ASK = "where-to.ask"

    /** The wallet tab, and the same screen right after creating an account, offering to add balance. */
    const val WALLET = "wallet"
    const val FUNDING = "funding"
    const val WALLET_TOTAL = "wallet.total"
    const val WALLET_ADD = "wallet.add"
    const val WALLET_SKIP = "wallet.skip"
    fun walletBalance(symbol: String) = "wallet.balance.$symbol"
    fun walletAmount(reais: Int) = "wallet.amount.$reais"
    fun walletCoin(symbol: String) = "wallet.coin.$symbol"

    const val HISTORY = "history"
    const val HISTORY_EMPTY = "history.empty"
    fun historyRide(id: String) = "history.ride.$id"
    fun historyStars(id: String) = "history.ride.$id.stars"

    /** `status` is the ride's status as the escrow names it: `Completed`, `Cancelled`… */
    fun historyStatus(id: String, status: String) = "history.ride.$id.status.$status"
    const val HISTORY_RATED = "rated"

    const val RULES = "rules"
    const val RULES_BASE = "rules.base"
    const val RULES_PER_KM = "rules.per-km"
    const val RULES_PER_MINUTE = "rules.per-minute"
    const val RULES_MINIMUM = "rules.minimum"
    const val RULES_RIDER_RANGE = "rules.rider-range"
    const val RULES_DRIVER_DISCOUNT = "rules.driver-discount"
    fun rulesFee(symbol: String) = "rules.fee.$symbol"
    fun rulesRiderBond(symbol: String) = "rules.rider-bond.$symbol"
    fun rulesDriverBond(symbol: String) = "rules.driver-bond.$symbol"

    const val PROFILE = "profile"
    const val PROFILE_NAME = "profile.name"
    const val PROFILE_ADDRESS = "profile.address"
    fun profileChain(connected: Boolean) = "profile.chain." + if (connected) "connected" else "unreachable"
    fun profileGossip(connected: Boolean) = "profile.gossip." + if (connected) "connected" else "unreachable"
    const val PROFILE_DRIVER_MODE = "profile.driver-mode"
    const val PROFILE_SIGN_OUT = "profile.sign-out"

    /** Asking for a ride: where to and at what price, before the request goes out. */
    const val ASK = "ask"
    const val ASK_PRICE = "ask.price"
    const val ASK_RECOMMENDED = "ask.recommended"
    const val ASK_LOWER = "ask.lower"
    const val ASK_RAISE = "ask.raise"
    const val ASK_NOT_ENOUGH = "ask.not-enough"
    const val ASK_SUBMIT = "ask.submit"
    fun askCoin(symbol: String) = "ask.coin.$symbol"

    const val DESTINATION = "destination"
    const val DESTINATION_SEARCH = "destination.search"
    const val DESTINATION_PICKED = "destination.picked"
    const val DESTINATION_CHANGE = "destination.change"
    const val DESTINATION_ROUTE = "destination.route"
    const val DESTINATION_ROUTING = "destination.routing"
    const val DESTINATION_ROUTE_ERROR = "destination.route-error"
    fun destinationPlace(index: Int) = "destination.place.$index"

    /** The request is out: the drivers' quotes as they arrive. */
    const val OFFERS = "offers"
    const val OFFERS_TRIP = "offers.trip"
    const val OFFERS_PRICE = "offers.price"
    const val OFFERS_STATUS = "offers.status"
    const val OFFERS_ASKED_AGAIN = "offers.asked-again"

    /** One driver's quote, by the driver's address in lower case. */
    fun offer(driver: String) = "offers.offer.${driver.lowercase()}"

    const val REPRICE = "reprice"
    const val REPRICE_PRICE = "reprice.price"
    const val REPRICE_LOWER = "reprice.lower"
    const val REPRICE_RAISE = "reprice.raise"
    const val REPRICE_SUBMIT = "reprice.submit"

    const val CONFIRM = "confirm"
    const val CONFIRM_DRIVER = "confirm.driver"
    const val CONFIRM_FARE = "confirm.fare"
    const val CONFIRM_TO_DRIVER = "confirm.to-driver"
    const val CONFIRM_FEE = "confirm.fee"
    const val CONFIRM_BOND = "confirm.bond"
    const val CONFIRM_LOCK = "confirm.lock"

    /** From the fare being locked until the driver starts the ride. */
    const val ON_THE_WAY = "on-the-way"

    /** `status` is the ride's status as the escrow names it: `Open` until the driver accepts, then `Accepted`. */
    fun onTheWayStatus(status: String) = "on-the-way.status.$status"
    const val ON_THE_WAY_LOCKED = "on-the-way.locked"
    const val ON_THE_WAY_DRIVER = "on-the-way.driver"
    const val ON_THE_WAY_CANCEL = "on-the-way.cancel"

    /** What the rider knows of where the driver is: busy with someone else, or showing live on the map. */
    const val DRIVER_BUSY = "driver-whereabouts.busy"
    const val DRIVER_LIVE = "driver-whereabouts.live"

    const val ON_TRIP = "on-trip"
    const val ON_TRIP_FARE = "on-trip.fare"
    const val ON_TRIP_ARRIVED = "on-trip.arrived"

    // ---------------------------------------------------------------- both
    const val RIDE_END = "ride-end"
    const val RIDE_END_TITLE = "ride-end.title"
    const val RIDE_END_RIDE = "ride-end.ride"
    const val RIDE_END_AMOUNT = "ride-end.amount"
    const val RIDE_END_STARS = "ride-end.stars"

    /** The rider confirming arrival, which pays the driver; and, once paid, either side sending the rating. */
    const val RIDE_END_PAY = "ride-end.pay"
    const val RIDE_END_RATE = "ride-end.rate"
    const val RIDE_END_SKIP = "ride-end.skip"

    /** The nth star of a star picker tagged `picker`. */
    fun star(picker: String, n: Int) = "$picker.$n"

    // ---------------------------------------------------------------- driver
    const val GO_ONLINE = "go-online"
    const val GO_ONLINE_TOGGLE = "go-online.toggle"
    fun goOnlineStatus(online: Boolean) = "go-online.status." + if (online) "online" else "offline"
    const val GO_ONLINE_RIDER_MODE = "go-online.rider-mode"
    const val GO_ONLINE_TODAY = "go-online.today"
    const val GO_ONLINE_SCORE = "go-online.score"
    const val GO_ONLINE_RIDES = "go-online.rides"
    const val GO_ONLINE_WAITING = "go-online.waiting"

    /** Why the policy will not let this driver accept rides. */
    const val BLOCKED = "blocked"

    const val AUTO_SKIP = "auto-skip"
    const val AUTO_SKIP_PER_KM = "auto-skip.per-km"
    const val AUTO_SKIP_PER_KM_LOWER = "auto-skip.per-km.lower"
    const val AUTO_SKIP_PER_KM_RAISE = "auto-skip.per-km.raise"
    const val AUTO_SKIP_BELOW = "auto-skip.below"
    const val AUTO_SKIP_BELOW_LOWER = "auto-skip.below.lower"
    const val AUTO_SKIP_BELOW_RAISE = "auto-skip.below.raise"
    const val AUTO_SKIP_SKIPPED = "auto-skip.skipped"

    /** A rider's request on the driver's screen, to quote on or pass. */
    const val REQUEST = "request"
    const val REQUEST_RIDER = "request.rider"
    const val REQUEST_PICKUP = "request.pickup-region"
    const val REQUEST_DROPOFF = "request.dropoff-region"
    const val REQUEST_TRIP = "request.trip"
    const val REQUEST_PAYS = "request.pays"
    const val REQUEST_PRICE = "request.price"
    const val REQUEST_LOWER = "request.lower"
    const val REQUEST_RAISE = "request.raise"
    const val REQUEST_YOUR_OFFER = "request.your-offer"
    const val REQUEST_PASS = "request.pass"
    const val REQUEST_SEND = "request.send"

    /** The driver's rides in hand: the one being driven, and the one a rider just locked. */
    const val DRIVER_RIDE = "driver-ride"
    const val DRIVER_RIDE_GUARANTEED = "driver-ride.guaranteed"
    const val DRIVER_RIDE_START = "driver-ride.start"
    const val DRIVER_RIDE_CANCEL = "driver-ride.cancel"
    const val DRIVER_RIDE_ACCEPT = "driver-ride.accept"
    const val DRIVER_RIDE_DECLINE = "driver-ride.decline"
    const val DRIVER_RIDE_NEXT_REQUEST = "driver-ride.next-request"
    const val DRIVER_RIDE_QUOTE = "driver-ride.quote"

    /** `slot` is `current` for the ride being driven and `chosen` for the one waiting for the driver's answer. */
    fun rideTitle(slot: String) = "driver-ride.$slot.title"
    fun rideRider(slot: String) = "driver-ride.$slot.rider"
    fun rideNumber(slot: String) = "driver-ride.$slot.ride"

    /** `state` says what the route line says: checked on this phone's map, still waiting, or no map to check on. */
    fun rideRoute(slot: String, state: String) = "driver-ride.$slot.route.$state"

    const val SLOT_CURRENT = "current"
    const val SLOT_CHOSEN = "chosen"

    const val ROUTE_CHECKED = "checked"
    const val ROUTE_WAITING = "waiting"
    const val ROUTE_NEEDS_MAP = "needs-map"
}
