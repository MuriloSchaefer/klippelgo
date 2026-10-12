package org.klippelgo.tests.drivers

import org.klippelgo.tests.Phone

/** The app on one phone: every UI component's driver, by the name the CLI knows it by. */
class App(val phone: Phone) {
    val error = ErrorDialog(phone)
    val networkNotice = NetworkNotice(phone)
    val language = Language(phone)
    val back = Back(phone)
    val map = MapView(phone)
    val mapNotice = MapNotice(phone)

    val welcome = Welcome(phone)
    val createAccount = CreateAccount(phone)
    val rideEnd = RideEnd(phone)

    val tabs = Tabs(phone)
    val whereTo = WhereTo(phone)
    val wallet = Wallet(phone)
    val funding = Funding(phone)
    val rules = Rules(phone)
    val profile = Profile(phone)
    val ask = Ask(phone)
    val offers = Offers(phone)
    val confirm = Confirm(phone)
    val onTheWay = OnTheWay(phone)
    val onTrip = OnTrip(phone)

    val goOnline = GoOnline(phone)
    val autoSkip = AutoSkip(phone)
    val request = IncomingRequest(phone)
    val driverRide = DriverRide(phone)

    val components: Map<String, Component> = linkedMapOf(
        "welcome" to welcome, "create-account" to createAccount, "funding" to funding,
        "tabs" to tabs, "where-to" to whereTo, "wallet" to wallet, "rules" to rules, "profile" to profile,
        "ask" to ask, "offers" to offers, "confirm" to confirm, "on-the-way" to onTheWay, "on-trip" to onTrip,
        "ride-end" to rideEnd,
        "go-online" to goOnline, "auto-skip" to autoSkip, "request" to request, "driver-ride" to driverRide,
        "map" to map, "map-notice" to mapNotice, "language" to language, "back" to back,
        "network-notice" to networkNotice, "error" to error,
    )
}
