package org.klippelgo.chain

import org.klippelgo.core.EscrowRef
import org.klippelgo.core.OfferTerms
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Bytes4
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Talks to the KlippelGo contracts as one account. Every call is blocking, so run it off the main thread.
 *
 * Rider and driver never talk to each other here: they both read and write the same RideEscrow, so a ride the
 * rider opens shows up for the driver on chain, and each step the driver takes shows up for the rider.
 *
 * Finding each other and agreeing a fare happens off chain, over gossip: see [Matchmaker].
 */
class KlippelGoClient(
    rpcUrl: String,
    privateKey: String,
    val deployment: Deployment,
) {
    private val web3: Web3j = Web3j.build(HttpService(rpcUrl))
    private val credentials: Credentials = Credentials.create(privateKey)
    val address: String = credentials.address.lowercase()

    private val txManager = RawTransactionManager(
        web3,
        credentials,
        deployment.chainId,
        PollingTransactionReceiptProcessor(web3, 500, 120),
    )

    val brz = Coin("BRZ", deployment.brz, 4)
    val brla = Coin("BRLA", deployment.brla, 18)
    val coins = listOf(brz, brla)

    // ---------------------------------------------------------------- reads

    fun chainId(): Long = web3.ethChainId().send().chainId.toLong()

    fun blockNumber(): BigInteger = web3.ethBlockNumber().send().blockNumber

    fun balanceOf(coin: Coin, owner: String = address): BigInteger =
        uint(call(coin.address, fn("balanceOf", listOf(Address(owner)), listOf(uint256Ref)))[0])

    fun rolesOf(who: String = address): Int =
        uint(call(deployment.registry, fn("rolesOf", listOf(Address(who)), listOf(uint8Ref)))[0]).toInt()

    /** Rides that reached a final state, by id. They never change again. */
    private val finished = java.util.concurrent.ConcurrentHashMap<BigInteger, Ride>()

    fun ride(id: BigInteger): Ride {
        val out = call(
            deployment.escrow,
            fn(
                "ride",
                listOf(Uint256(id)),
                listOf(addressRef, addressRef, addressRef, uint8Ref, uint64Ref, uint64Ref) +
                    List(4) { uint256Ref } + listOf(bytes32Ref, bytes32Ref, bytes32Ref, uint64Ref, uint256Ref, addressRef),
            ),
        )
        return Ride(
            id = id,
            rider = (out[0] as Address).value.lowercase(),
            driver = (out[1] as Address).value.lowercase(),
            token = (out[2] as Address).value.lowercase(),
            status = RideStatus.of(uint(out[3]).toInt()),
            openedAt = uint(out[4]).toLong(),
            startedAt = uint(out[5]).toLong(),
            fare = uint(out[6]),
            fee = uint(out[7]),
            riderBond = uint(out[8]),
            driverBond = uint(out[9]),
            quoteHash = Numeric.toHexString((out[10] as Bytes32).value),
            receiptHash = Numeric.toHexString((out[12] as Bytes32).value),
            receiptAt = uint(out[13]).toLong(),
            tax = uint(out[14]),
        )
    }

    // ---------------------------------------------------------------- what a ride costs beyond the fare

    /**
     * The government's tax on a ride, as a share of the fare in basis points. It comes out of what the driver
     * receives; the rider pays the fare and nothing on top. Governance can change it, so it is read again
     * every few seconds: a quote is priced on today's rate, and a ride keeps the rate it opened with.
     */
    fun taxBps(): Int {
        val now = System.currentTimeMillis()
        if (now - taxReadAt > TAX_FRESH_MS) {
            taxRate = uint(call(deployment.escrow, fn("taxBps", emptyList(), listOf(uint16Ref)))[0]).toInt()
            taxReadAt = now
        }
        return taxRate
    }

    @Volatile private var taxRate = 0
    @Volatile private var taxReadAt = 0L

    /** The tax a ride opened now at `fare` would carry. Mirrors `RideEscrow.open`. */
    fun taxOn(fare: BigInteger): BigInteger = fare * BigInteger.valueOf(taxBps().toLong()) / BigInteger.valueOf(10_000)

    /** What one native coin of the chain is worth in centavos, as governance set it in `FareGuide`. Zero: not set. */
    val nativeCoinCentavos: BigInteger by lazy {
        uint(call(deployment.fares, fn("nativeCoinCentavos", emptyList(), listOf(uint256Ref)))[0])
    }

    /** What the network asks for a unit of gas right now, in wei. */
    fun gasPrice(): BigInteger = web3.ethGasPrice().send().gasPrice

    /**
     * What `wei` paid to the network comes to in reais, at the rate governance set: the infrastructure cost of
     * the transactions behind a ride. Kept to a hundredth of a centavo, since on a cheap chain a whole ride
     * costs less than one. The same sum as `FareGuide.infrastructureCost`, which rounds to the centavo.
     */
    fun infrastructureCost(wei: BigInteger): BigDecimal =
        BigDecimal(wei * nativeCoinCentavos).divide(BigDecimal.TEN.pow(20), 4, java.math.RoundingMode.HALF_UP)

    private val spent = java.util.concurrent.ConcurrentHashMap<BigInteger, BigInteger>()

    /** Rides whose first transaction on this side (the rider's `open`, the driver's `accept`) this client sent. */
    private val seenFromTheStart = java.util.concurrent.ConcurrentHashMap.newKeySet<BigInteger>()

    /**
     * What this account's own transactions for a ride cost in gas so far, in wei, or null when this client did
     * not see the ride from its first transaction. It is kept in memory only, so after the app restarts the
     * sum would be missing its beginning: better to say an estimate than a part as if it were the whole.
     */
    fun gasSpent(rideId: BigInteger): BigInteger? = spent[rideId]?.takeIf { rideId in seenFromTheStart }

    private fun paid(rideId: BigInteger, vararg receipts: TransactionReceipt?) {
        val wei = receipts.filterNotNull().fold(BigInteger.ZERO) { sum, r ->
            sum + r.gasUsed * (r.effectiveGasPrice?.let { Numeric.decodeQuantity(it) } ?: BigInteger.ZERO)
        }
        spent.merge(rideId, wei, BigInteger::add)
    }

    /** Fee, rider bond and driver bond for a coin on the allow-list. */
    fun tokenConfig(coin: Coin): Triple<BigInteger, BigInteger, BigInteger> {
        val out = call(
            deployment.escrow,
            fn("tokenConfig", listOf(Address(coin.address)), listOf(uint256Ref, uint256Ref, uint256Ref)),
        )
        return Triple(uint(out[0]), uint(out[1]), uint(out[2]))
    }

    fun hasPermission(subject: String, target: String, selector: ByteArray): Boolean =
        (call(
            deployment.governor,
            fn("hasPermission", listOf(Address(subject), Address(target), Bytes4(selector)), listOf(boolRef)),
        )[0] as Bool).value

    fun canAcceptRides(driver: String = address): Boolean = hasPermission(driver, deployment.escrow, selector("accept(uint256)"))

    /** Every address that registered with the driver role, with its reputation. */
    fun drivers(): List<DriverInfo> {
        val registered = logs(deployment.registry, REGISTERED_TOPIC)
            .map { Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(it.topics[1]), 40) }
            .distinct()
        return registered.filter { rolesOf(it) and ROLE_DRIVER != 0 }.map(::driverInfo)
    }

    /** One driver's reputation and whether the policy lets them accept rides. */
    fun driverInfo(d: String = address): DriverInfo {
        val score = uint(call(deployment.reputation, fn("scoreOf", listOf(Address(d)), listOf(uint256Ref)))[0])
        val count = uint(call(deployment.reputation, fn("ratingCount", listOf(Address(d)), listOf(uint256Ref)))[0])
        val rides = uint(call(deployment.reputation, fn("completedRides", listOf(Address(d)), listOf(uint256Ref)))[0])
        return DriverInfo(d, score.toInt(), count.toInt(), rides.toInt(), canAcceptRides(d))
    }

    /**
     * Which requirements of the accept policy `driver` fails, read the same way `PBACGovernor.hasPermission`
     * checks them. Empty when the driver may accept rides.
     */
    fun acceptBlockers(driver: String = address): List<Blocker> {
        val p = call(
            deployment.governor,
            fn(
                "policy",
                listOf(Address(deployment.escrow), Bytes4(selector("accept(uint256)"))),
                listOf(boolRef, uint8Ref, uint16Ref, uint32Ref, uint256Ref, bytes32Ref),
            ),
        )
        if (!(p[0] as Bool).value) return listOf(Blocker.Disabled)
        val roles = uint(p[1]).toInt()
        val minScore = uint(p[2]).toInt()
        val minAge = uint(p[3]).toLong()
        val minStake = uint(p[4])
        val credential = (p[5] as Bytes32).value

        val id = call(
            deployment.registry,
            fn("identity", listOf(Address(driver)), listOf(uint8Ref, uint64Ref, uint64Ref, uint256Ref, uint256Ref)),
        )
        val registeredAt = uint(id[1]).toLong()
        val stake = uint(id[3])
        val blockers = mutableListOf<Blocker>()
        if (registeredAt == 0L || (roles != 0 && uint(id[0]).toInt() and roles == 0)) blockers += Blocker.Role
        if (stake < minStake) {
            val token = (call(deployment.registry, fn("stakeToken", emptyList(), listOf(addressRef)))[0] as Address).value
            val coin = coinFor(token)
            blockers += Blocker.Stake(coin.toReais(stake), coin.toReais(minStake))
        }
        if (registeredAt != 0L && System.currentTimeMillis() / 1000 - registeredAt < minAge) blockers += Blocker.AccountAge
        val info = driverInfo(driver)
        if (minScore != 0 && info.ratings > 0 && info.score < minScore) blockers += Blocker.Score(info.score, minScore)
        if (credential.any { it != 0.toByte() }) {
            val has = call(
                deployment.registry,
                fn("hasCredential", listOf(Address(driver), Bytes32(credential)), listOf(boolRef)),
            )[0] as Bool
            if (!has.value) blockers += Blocker.Credential(cnh = credential.contentEquals(CREDENTIAL_CNH))
        }
        return blockers
    }

    /** Whether this account already rated the other party of a completed ride. */
    fun hasRated(rideId: BigInteger): Boolean {
        val out = call(
            deployment.reputation,
            fn("rides", listOf(Uint256(rideId)), listOf(addressRef, addressRef, boolRef, boolRef)),
        )
        val rider = (out[0] as Address).value.lowercase()
        return if (rider == address) (out[2] as Bool).value else (out[3] as Bool).value
    }

    /**
     * How many rides the driver of `ride` has taken before it and not finished: accepted or under way. While it
     * is above zero the driver is busy with someone else and this ride waits its turn. Anyone can read this off
     * the chain.
     */
    fun ridesAhead(ride: Ride): Int = underWay(ride.driver).count { turn.compare(it, ride) < 0 }

    /**
     * The rides a driver accepted and has not finished, in the order they take them: a ride that started comes
     * before one only accepted, then the order the riders locked them. The first is the one the driver is on.
     * A ride in dispute is frozen and takes no turn.
     *
     * It looks at every ride that names the driver, not at the newest few: anyone can open rides naming a
     * driver, and a window of recent ones could be filled with those to push a real ride out of sight. Rides
     * that ended are remembered, so each is read from the chain once.
     */
    fun underWay(driver: String = address): List<Ride> = rideIds(driver = driver)
        .map { id -> finished[id] ?: ride(id).also { if (it.status.isFinal) finished[id] = it } }
        .filter { it.status == RideStatus.Accepted || it.status == RideStatus.Started }
        .sortedWith(turn)

    private val turn = compareBy<Ride>({ it.status != RideStatus.Started }, { it.id })

    /** Ride ids where this account is the driver, newest first. */
    fun ridesAsDriver(): List<BigInteger> = rideIds(driver = address)

    /** Ride ids where this account is the rider, newest first. */
    fun ridesAsRider(): List<BigInteger> = rideIds(rider = address)

    private fun rideIds(rider: String? = null, driver: String? = null): List<BigInteger> {
        val filter = EthFilter(DefaultBlockParameterName.EARLIEST, DefaultBlockParameterName.LATEST, deployment.escrow)
            .addSingleTopic(RIDE_OPENED_TOPIC)
        filter.addNullTopic() // rideId
        if (rider != null) filter.addSingleTopic(topicOf(rider)) else filter.addNullTopic()
        if (driver != null) filter.addSingleTopic(topicOf(driver))
        return getLogs(filter).map { Numeric.toBigInt(it.topics[1]) }.sortedDescending()
    }

    // ---------------------------------------------------------------- quotes

    /**
     * The hash a driver signs when quoting and the rider locks in the escrow. It names the escrow and chain, the
     * rider's commitment to the route, both parties, the token, the fare and an expiry, so a quote cannot be
     * replayed anywhere else or moved to another route. The Rust core computes it, the same way the verifier
     * does when a juror checks the quote (docs/maps-and-routing.md).
     */
    fun quoteHash(request: RideRequest, driver: String, fare: BigInteger, expiresAt: Long): ByteArray =
        org.klippelgo.core.quoteHash(
            EscrowRef(deployment.chainId.toULong(), deployment.escrow),
            Numeric.hexStringToByteArray(request.route.routeHash),
            OfferTerms(fare.toString(), request.token, request.rider, driver, expiresAt.toULong()),
        )

    // ---------------------------------------------------------------- price guide

    /** The price guide in force. Read once per client: it changes only through a governance proposal. */
    val tariff: Tariff by lazy {
        val t = call(
            deployment.fares,
            fn("tariff", emptyList(), listOf(uint32Ref, uint32Ref, uint32Ref, uint32Ref, uint16Ref, uint16Ref)),
        )
        Tariff(uint(t[0]).toLong(), uint(t[1]).toLong(), uint(t[2]).toLong(), uint(t[3]).toLong(), uint(t[4]).toInt(), uint(t[5]).toInt())
    }

    /** Whether `price` is one a rider may ask for a trip of this length. */
    fun riderMayAsk(coin: Coin, price: BigInteger, route: RouteSummary): Boolean {
        val range = tariff.riderRange(route.distanceM, route.durationS)
        return price >= coin.fromCentavos(range.first) && price <= coin.fromCentavos(range.last)
    }

    /**
     * Whether a driver may quote `fare` against a request: no more than 20% under the rider's price, and no
     * higher than the top of the fare guide's range for the trip, even if that is more than the rider asked.
     */
    fun driverMayQuote(request: RideRequest, fare: BigInteger): Boolean {
        val ceiling = coinFor(request.token).fromCentavos(tariff.driverCeiling(request.route.distanceM, request.route.durationS))
        return fare >= tariff.driverFloor(request.maxFare) && fare <= ceiling
    }

    // ---------------------------------------------------------------- map packs

    /**
     * The proof root governance activated for a map pack, or null for a pack MapRegistry does not know. A phone
     * compares it with the root in the pack it downloaded before trusting the pack.
     */
    fun packProofRoot(packId: ByteArray): ByteArray? =
        (call(deployment.maps, fn("proofRootOf", listOf(Bytes32(packId)), listOf(bytes32Ref)))[0] as Bytes32).value
            .takeIf { root -> root.any { it != 0.toByte() } }

    /** Whether quotes may be made on the pack now, by the chain's clock: activated, and not retired by a newer one. */
    fun packIsActive(packId: ByteArray): Boolean {
        val now = web3.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().block.timestamp
        return (call(deployment.maps, fn("isActive", listOf(Bytes32(packId), Uint64(now)), listOf(boolRef)))[0] as Bool).value
    }

    /** Id of the newest pack governance activated for a region such as `br-pr-cascavel`, or null. */
    fun latestPack(region: String): ByteArray? =
        (call(deployment.maps, fn("latest", listOf(Bytes32(Hash.sha3(region.toByteArray()))), listOf(bytes32Ref)))[0] as Bytes32)
            .value.takeIf { id -> id.any { it != 0.toByte() } }

    /** Signs a 32-byte hash with this account's key, as an Ethereum signed message. Returns r, s, v (65 bytes). */
    fun sign(hash: ByteArray): ByteArray {
        val sig = Sign.signPrefixedMessage(hash, credentials.ecKeyPair)
        return sig.r + sig.s + sig.v
    }

    // ---------------------------------------------------------------- writes

    /** Claims roles (1 rider, 2 driver, 4 validator) if this account does not hold them yet. */
    fun ensureRegistered(roles: Int) {
        if (rolesOf() and roles == roles) return
        send(deployment.registry, fn("register", listOf(Uint8(roles.toLong())), emptyList()))
    }

    /** Rider locks the fare plus the rider bond. Returns the new ride id. */
    fun openRide(driver: String, coin: Coin, fare: BigInteger, quoteHash: ByteArray): BigInteger {
        val (_, riderBond, _) = tokenConfig(coin)
        val approval = approveIfNeeded(coin, deployment.escrow, fare + riderBond)
        val receipt = send(
            deployment.escrow,
            fn("open", listOf(Address(driver), Address(coin.address), Uint256(fare), Bytes32(quoteHash)), listOf(uint256Ref)),
        )
        val log = receipt.logs.first { it.topics.firstOrNull() == RIDE_OPENED_TOPIC }
        return Numeric.toBigInt(log.topics[1]).also {
            seenFromTheStart += it
            paid(it, approval, receipt)
        }
    }

    /** Driver accepts and posts the driver bond. */
    fun accept(rideId: BigInteger) {
        val r = ride(rideId)
        val approval = approveIfNeeded(coinFor(r.token), deployment.escrow, r.driverBond)
        val accepted = send(deployment.escrow, fn("accept", listOf(Uint256(rideId)), emptyList()))
        seenFromTheStart += rideId
        paid(rideId, approval, accepted)
    }

    /** Driver records the pickup proof hash. */
    fun start(rideId: BigInteger, pickupProofHash: ByteArray) =
        paid(rideId, send(deployment.escrow, fn("start", listOf(Uint256(rideId), Bytes32(pickupProofHash)), emptyList())))

    /**
     * Rider confirms arrival with the hash of the trip receipt; the driver is paid, R$ 1,00 goes to Instituto
     * Klippel and the tax to its collector. If the driver already put a receipt on record, it must be the same one.
     */
    fun complete(rideId: BigInteger, receiptHash: ByteArray) =
        paid(rideId, send(deployment.escrow, fn("complete", listOf(Uint256(rideId), Bytes32(receiptHash)), emptyList())))

    /** Driver puts their trip receipt on record when the rider does not confirm. Starts the clock for [timeout]. */
    fun submitReceipt(rideId: BigInteger, receiptHash: ByteArray) =
        paid(rideId, send(deployment.escrow, fn("submitReceipt", listOf(Uint256(rideId), Bytes32(receiptHash)), emptyList())))

    /** Settles a ride whose driver's receipt has stood for the completion timeout with no dispute. */
    fun timeout(rideId: BigInteger) = paid(rideId, send(deployment.escrow, fn("timeout", listOf(Uint256(rideId)), emptyList())))

    fun cancel(rideId: BigInteger) = paid(rideId, send(deployment.escrow, fn("cancel", listOf(Uint256(rideId)), emptyList())))

    fun rate(rideId: BigInteger, stars: Int) =
        paid(rideId, send(deployment.reputation, fn("rate", listOf(Uint256(rideId), Uint8(stars.toLong())), emptyList())))

    /** Mints test reais to this account. Only the local testnet's mock coins allow it: they stand in for PIX. */
    fun mint(coin: Coin, amount: BigInteger) =
        send(coin.address, fn("mint", listOf(Address(address), Uint256(amount)), emptyList()))

    fun coinFor(token: String): Coin =
        coins.firstOrNull { it.address.equals(token, ignoreCase = true) } ?: throw ChainException("Unknown coin $token")

    /** Gives back the approval's receipt when one was needed, so its gas counts towards the ride it was for. */
    private fun approveIfNeeded(coin: Coin, spender: String, amount: BigInteger): TransactionReceipt? {
        val allowance = uint(
            call(coin.address, fn("allowance", listOf(Address(address), Address(spender)), listOf(uint256Ref)))[0],
        )
        if (allowance >= amount) return null
        return send(coin.address, fn("approve", listOf(Address(spender), Uint256(amount)), listOf(boolRef)))
    }

    // ---------------------------------------------------------------- plumbing

    private fun call(to: String, function: Function): List<Type<*>> {
        val data = FunctionEncoder.encode(function)
        val res = web3.ethCall(
            Transaction.createEthCallTransaction(address, to, data),
            DefaultBlockParameterName.LATEST,
        ).send()
        if (res.hasError() || res.isReverted) throw ChainException(describeRevert(res.error?.data, res.error?.message ?: res.revertReason))
        @Suppress("UNCHECKED_CAST")
        return FunctionReturnDecoder.decode(res.value, function.outputParameters as List<TypeReference<Type<*>>>)
    }

    /** Simulates first so a revert surfaces with its contract error name, then signs and waits for the receipt. */
    @Synchronized
    private fun send(to: String, function: Function): TransactionReceipt {
        val data = FunctionEncoder.encode(function)
        val tx = Transaction.createEthCallTransaction(address, to, data)
        val sim = web3.ethCall(tx, DefaultBlockParameterName.LATEST).send()
        if (sim.hasError() || sim.isReverted) {
            throw ChainException("${function.name}: " + describeRevert(sim.error?.data, sim.error?.message ?: sim.revertReason))
        }
        val estimate = web3.ethEstimateGas(tx).send()
        if (estimate.hasError()) throw ChainException("${function.name}: ${estimate.error.message}")
        val gasLimit = estimate.amountUsed * BigInteger.valueOf(13) / BigInteger.TEN
        val gasPrice = web3.ethGasPrice().send().gasPrice
        val sent = txManager.sendTransaction(gasPrice, gasLimit, to, data, BigInteger.ZERO)
        if (sent.hasError()) throw ChainException("${function.name}: ${sent.error.message}")
        val receipt = PollingTransactionReceiptProcessor(web3, 500, 120).waitForTransactionReceipt(sent.transactionHash)
        if (!receipt.isStatusOK) throw ChainException("${function.name}: transaction ${receipt.transactionHash} reverted")
        return receipt
    }

    private fun logs(contract: String, topic0: String): List<EthLog.LogObject> =
        getLogs(
            EthFilter(DefaultBlockParameterName.EARLIEST, DefaultBlockParameterName.LATEST, contract)
                .addSingleTopic(topic0),
        )

    private fun getLogs(filter: EthFilter): List<EthLog.LogObject> {
        val res = web3.ethGetLogs(filter).send()
        if (res.hasError()) throw ChainException(res.error.message)
        return res.logs.map { it.get() as EthLog.LogObject }
    }

    companion object {
        /**
         * Gas a ride's own transactions take on each side, from the gas report in contracts/docs/scalability.md:
         * the rider's `open` and `complete`, the driver's `accept` and `start`. Used to say ahead of the ride
         * what it will cost the network; what was actually paid is read from the receipts ([gasSpent]).
         */
        val RIDER_RIDE_GAS: BigInteger = BigInteger.valueOf(614_000)
        val DRIVER_RIDE_GAS: BigInteger = BigInteger.valueOf(170_000)

        /** How long a tax rate read from the escrow is used before it is read again. */
        private const val TAX_FRESH_MS = 10_000L

        const val ROLE_RIDER = 1
        const val ROLE_DRIVER = 2

        val RIDE_OPENED_TOPIC: String = Hash.sha3String("RideOpened(uint256,address,address,address,uint256,bytes32)")
        val REGISTERED_TOPIC: String = Hash.sha3String("Registered(address,uint8)")

        private val CREDENTIAL_CNH = Numeric.hexStringToByteArray(Hash.sha3String("klippelgo.credential.CNH"))
        private val uint256Ref = object : TypeReference<Uint256>() {}
        private val uint64Ref = object : TypeReference<Uint64>() {}
        private val uint32Ref = object : TypeReference<Uint32>() {}
        private val uint16Ref = object : TypeReference<Uint16>() {}
        private val uint8Ref = object : TypeReference<Uint8>() {}
        private val addressRef = object : TypeReference<Address>() {}
        private val bytes32Ref = object : TypeReference<Bytes32>() {}
        private val boolRef = object : TypeReference<Bool>() {}

        /** Contract errors, so a revert reads as `NotPermitted` rather than raw bytes. */
        private val ERRORS: Map<String, String> = listOf(
            "TokenNotAllowed()", "FareTooLow()", "WrongStatus(uint8)", "NotRider()", "NotDriver()", "NotParty()",
            "TooEarly()", "NoReceipt()", "ReceiptMismatch()", "NotPermitted(address,bytes4)", "NotRegistered()", "InvalidRoles()", "AlreadyRated()",
            "InvalidStars()", "ERC20InsufficientBalance(address,uint256,uint256)",
            "ERC20InsufficientAllowance(address,uint256,uint256)",
        ).associate { selectorHex(it) to it.substringBefore('(') }

        fun selector(signature: String): ByteArray = Numeric.hexStringToByteArray(Hash.sha3String(signature)).copyOf(4)

        private fun selectorHex(signature: String) = Numeric.toHexString(selector(signature))

        private fun describeRevert(data: String?, fallback: String?): String {
            val sel = data?.takeIf { it.length >= 10 }?.substring(0, 10)?.lowercase()
            return ERRORS[sel] ?: fallback ?: "reverted"
        }

        private fun topicOf(address: String) = Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(address), 64)

        /** keccak256 of the ABI encoding of fixed-size values, the same as Solidity's `keccak256(abi.encode(...))`. */
        fun abiHash(vararg values: Type<*>): ByteArray =
            Hash.sha3(Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(values.toList())))

        /** The address that signed `hash` (as an Ethereum signed message), or null for a malformed signature. */
        fun signerOf(hash: ByteArray, sig: ByteArray): String? = runCatching {
            require(sig.size == 65)
            val data = Sign.SignatureData(sig[64], sig.copyOfRange(0, 32), sig.copyOfRange(32, 64))
            "0x" + Keys.getAddress(Sign.signedPrefixedMessageToKey(hash, data))
        }.getOrNull()

        private fun fn(name: String, inputs: List<Type<*>>, outputs: List<TypeReference<*>>) =
            Function(name, inputs, outputs)

        private fun uint(t: Type<*>): BigInteger = t.value as BigInteger
    }
}
