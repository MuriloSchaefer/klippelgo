package org.klippelgo.chain

import org.klippelgo.core.EscrowRef
import org.klippelgo.core.OfferTerms
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Bytes4
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.generated.Uint192
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthCall
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Talks to the KlippelGo contracts as one person's account. Every call is blocking, so run it off the main thread.
 *
 * The account is a smart account owned by the phone's [DeviceKey]. Every write is an ERC-4337 operation the key
 * signs and a [Bundler] puts on chain; its gas is paid in reais through the paymaster, so the account never holds
 * the chain's own coin. Several calls that belong together (an approval and the call that needs it) go in one
 * operation, all or nothing.
 *
 * Rider and driver never talk to each other here: they both read and write the same RideEscrow, so a ride the
 * rider opens shows up for the driver on chain, and each step the driver takes shows up for the rider.
 *
 * Finding each other and agreeing a fare happens off chain, over gossip: see [Matchmaker]. There the account
 * speaks through a [SessionKey] its device key certified, so the chip's key signs once a day, not per message.
 */
class KlippelGoClient(
    rpcUrl: String,
    key: DeviceKey,
    val deployment: Deployment,
    bundlerUrl: String = defaultBundler(rpcUrl),
) {
    private val web3: Web3j = Web3j.build(HttpService(rpcUrl))
    val account = SmartAccount(key, deployment)
    val address: String = account.address.lowercase()
    private val bundler = Bundler(bundlerUrl, deployment.entryPoint)

    val brz = Coin("BRZ", deployment.brz, 4)
    val brla = Coin("BRLA", deployment.brla, 18)
    val coins = listOf(brz, brla)

    // ---------------------------------------------------------------- reads

    fun chainId(): Long = web3.ethChainId().send().chainId.toLong()

    fun blockNumber(): BigInteger = web3.ethBlockNumber().send().blockNumber

    fun balanceOf(coin: Coin, owner: String = address): BigInteger =
        uint(call(coin.address, fn("balanceOf", listOf(Address(owner)), listOf(uint256Ref)))[0])

    fun rolesOf(who: String = address): Int = uint(call(deployment.registry, rolesFn(who))[0]).toInt()

    /** Rides that reached a final state, by id. They never change again. */
    private val finished = java.util.concurrent.ConcurrentHashMap<BigInteger, Ride>()

    /** One ride as it is now, read from the chain. */
    fun ride(id: BigInteger): Ride = rideOf(id, call(deployment.escrow, rideFn(id)))

    /**
     * Several rides at once, in the order asked: those in a final state from memory, the rest in one batch of
     * reads, so a screen of rides costs one round trip to the node however many it shows.
     */
    fun rides(ids: List<BigInteger>): List<Ride> {
        val unknown = ids.filterNot(finished::containsKey).distinct()
        val read = callAll(unknown.map { deployment.escrow to rideFn(it) }).mapIndexed { i, out -> rideOf(unknown[i], out) }.associateBy { it.id }
        read.values.filter { it.status.isFinal }.forEach { finished[it.id] = it }
        return ids.map { finished[it] ?: read.getValue(it) }
    }

    private fun rideFn(id: BigInteger) = fn(
        "ride",
        listOf(Uint256(id)),
        listOf(addressRef, addressRef, addressRef, uint8Ref, uint64Ref, uint64Ref) +
            List(4) { uint256Ref } + listOf(bytes32Ref, bytes32Ref, bytes32Ref, uint64Ref, uint256Ref, addressRef),
    )

    private fun rideOf(id: BigInteger, out: List<Type<*>>): Ride {
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

    /**
     * What one native coin of the chain is worth in centavos, as governance set it in `FareGuide`. Zero: not set.
     * It prices the gas the paymaster charges, so it is read again like the tariff, and a failed read keeps the
     * last one.
     */
    val nativeCoinCentavos: BigInteger
        get() {
            val now = System.nanoTime()
            val last = coinRead
            if (last != null && now - last.second <= TARIFF_FRESH_MS * 1_000_000) return last.first
            val read = try {
                uint(call(deployment.fares, fn("nativeCoinCentavos", emptyList(), listOf(uint256Ref)))[0])
            } catch (e: Exception) {
                if (last == null) throw e
                last.first
            }
            coinRead = read to now
            return read
        }

    @Volatile private var coinRead: Pair<BigInteger, Long>? = null

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

    private fun paid(rideId: BigInteger, receipt: Bundler.Receipt) {
        spent.merge(rideId, receipt.actualGasCost, BigInteger::add)
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

    /** Whether the policy lets `rider` lock a ride: the rider role, and not blocked by Instituto Klippel. */
    fun canOpenRides(rider: String = address): Boolean =
        hasPermission(rider, deployment.escrow, selector("open(address,address,uint256,bytes32)"))

    /**
     * Every address that registered with the driver role, with its reputation. Registrations are found by
     * scanning only the blocks not scanned before ([scan]); roles and reputations are read in batches.
     */
    fun drivers(): List<DriverInfo> {
        val registered = scan("registered", deployment.registry, listOf(REGISTERED_TOPIC)) { Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(it.topics[1]), 40) }
            .toList()
        val roles = callAll(registered.map { deployment.registry to rolesFn(it) }).map { uint(it[0]).toInt() }
        return driverInfos(registered.filterIndexed { i, _ -> roles[i] and ROLE_DRIVER != 0 })
    }

    private fun rolesFn(who: String) = fn("rolesOf", listOf(Address(who)), listOf(uint8Ref))

    /** One driver's reputation and whether the policy lets them accept rides. */
    fun driverInfo(d: String = address): DriverInfo = driverInfos(listOf(d)).single()

    /** Reputations of several drivers, in one batch of reads. */
    private fun driverInfos(drivers: List<String>): List<DriverInfo> {
        val accept = selector("accept(uint256)")
        val out = callAll(
            drivers.flatMap { d ->
                listOf(
                    deployment.reputation to fn("scoreOf", listOf(Address(d)), listOf(uint256Ref)),
                    deployment.reputation to fn("ratingCount", listOf(Address(d)), listOf(uint256Ref)),
                    deployment.reputation to fn("completedRides", listOf(Address(d)), listOf(uint256Ref)),
                    deployment.governor to fn("hasPermission", listOf(Address(d), Address(deployment.escrow), Bytes4(accept)), listOf(boolRef)),
                )
            },
        )
        return drivers.mapIndexed { i, d ->
            val (score, count, rides, may) = out.subList(4 * i, 4 * i + 4)
            DriverInfo(d, uint(score[0]).toInt(), uint(count[0]).toInt(), uint(rides[0]).toInt(), (may[0] as Bool).value)
        }
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
        // A blocked account passes no policy whatever else it has, so nothing else is worth listing.
        // A registry deployed before the block existed has no such function: there, nobody is blocked.
        val blocked = runCatching {
            (call(deployment.registry, fn("blocked", listOf(Address(driver)), listOf(boolRef)))[0] as Bool).value
        }.getOrDefault(false)
        if (blocked) return listOf(Blocker.Blocked)
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

    /** Completed rides this account has rated. A rating cannot be taken back, so each is asked until it is there. */
    private val ratedRides = java.util.concurrent.ConcurrentHashMap.newKeySet<BigInteger>()

    /** Which of these completed rides this account already rated the other party of, in one batch of reads. */
    fun rated(rideIds: Collection<BigInteger>): Set<BigInteger> {
        val ask = rideIds.filter { it !in ratedRides }.distinct()
        // The ledger knows a ride by its escrow and its id there.
        val out = callAll(ask.map { deployment.reputation to fn("rides", listOf(Address(deployment.escrow), Uint256(it)), listOf(addressRef, addressRef, boolRef, boolRef)) })
        ask.forEachIndexed { i, id ->
            val asRider = (out[i][0] as Address).value.lowercase() == address
            if ((out[i][if (asRider) 2 else 3] as Bool).value) ratedRides += id
        }
        return rideIds.filterTo(HashSet()) { it in ratedRides }
    }

    /** Whether this account already rated the other party of a completed ride. */
    fun hasRated(rideId: BigInteger): Boolean = rideId in rated(listOf(rideId))

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
     * that ended are remembered, so each is read from the chain once, and the rest are read in one batch.
     */
    fun underWay(driver: String = address): List<Ride> = rides(rideIds(driver = driver))
        // After a reorganisation of the chain an id found earlier can belong to someone else's ride.
        .filter { it.driver.equals(driver, ignoreCase = true) }
        .filter { it.status == RideStatus.Accepted || it.status == RideStatus.Started }
        .sortedWith(turn)

    private val turn = compareBy<Ride>({ it.status != RideStatus.Started }, { it.id })

    /** Ride ids where this account is the driver, newest first. */
    fun ridesAsDriver(): List<BigInteger> = rideIds(driver = address)

    /** Ride ids where this account is the rider, newest first. */
    fun ridesAsRider(): List<BigInteger> = rideIds(rider = address)

    /** Rides naming `driver` that this client has not seen reach a final state: the ones that can still change. */
    fun unfinished(driver: String): Set<BigInteger> = rideIds(driver = driver).filterNotTo(HashSet(), finished::containsKey)

    private fun rideIds(rider: String? = null, driver: String? = null): List<BigInteger> {
        val topics = listOf(RIDE_OPENED_TOPIC, null, rider?.let(::topicOf), driver?.let(::topicOf))
        val key = "opened/" + (rider?.lowercase() ?: "") + "/" + (driver?.lowercase() ?: "")
        return scan(key, deployment.escrow, topics) { Numeric.toBigInt(it.topics[1]).toString() }
            .map(::BigInteger).sortedDescending()
    }

    // ---------------------------------------------------------------- event scans

    /** What one kind of event scan has found, and the newest block it has looked at. */
    private class Scan(@Volatile var to: Long, val found: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet())

    private val scans = java.util.concurrent.ConcurrentHashMap<String, Scan>()

    /**
     * The block the scans were known to be on, as (number, hash), to tell after a restart whether the chain is
     * still the one they read: a local testnet started again has the same addresses and none of the history.
     */
    @Volatile private var anchor: Pair<Long, String>? = null
    @Volatile private var anchorChecked = false

    /**
     * Everything `pick` takes from the logs of `contract` matching `topics` (null for any), from the block the
     * contracts were deployed in. Each call reads only the blocks after the last one it read, in ranges a public
     * node accepts, and the last few again in case the chain reorganised. What it found is kept, and can be kept
     * on the phone across restarts ([savedScans]). Each kind is scanned by one caller at a time; different kinds
     * at once, so a long first scan of one does not hold up the others.
     */
    private fun scan(key: String, contract: String, topics: List<String?>, pick: (EthLog.LogObject) -> String): Set<String> {
        val head = blockNumber().toLong()
        val s = synchronized(scans) {
            if (!anchorChecked) {
                val a = anchor
                if (a != null && (a.first > head || blockHash(a.first) != a.second)) {
                    scans.clear()
                    anchor = null
                }
                // Only once the comparison was made: a read that failed asks again next time.
                anchorChecked = true
            }
            scans.getOrPut(key) { Scan(deployment.startBlock - 1) }
        }
        synchronized(s) {
            // A chain that went backwards is another chain: read it from the start.
            if (s.to > head) {
                s.to = deployment.startBlock - 1
                s.found.clear()
            }
            var from = maxOf(deployment.startBlock, s.to - REORG_BLOCKS + 1)
            while (from <= head) {
                val to = minOf(head, from + LOG_RANGE - 1)
                val filter = EthFilter(DefaultBlockParameter.valueOf(BigInteger.valueOf(from)), DefaultBlockParameter.valueOf(BigInteger.valueOf(to)), contract)
                topics.forEach { if (it == null) filter.addNullTopic() else filter.addSingleTopic(it) }
                getLogs(filter).forEach { if (!it.isRemoved) s.found += pick(it) }
                s.to = to
                from = to + 1
            }
            // Deep enough that a reorganisation will not change it.
            if (anchor == null && head > 0) maxOf(1, head - REORG_BLOCKS).let { n -> blockHash(n)?.let { anchor = n to it } }
            return HashSet(s.found)
        }
    }

    /**
     * Forgets the scans of rides naming anyone but `accounts`: a rider stops keeping the rides of a driver they
     * no longer wait for, which anyone could have filled with rides opened naming that driver.
     */
    fun keepScansOf(accounts: Set<String>) {
        val keep = accounts.mapTo(HashSet()) { it.lowercase() }
        synchronized(scans) {
            scans.keys.removeIf { key -> key.startsWith("opened/") && key.removePrefix("opened/").split('/').none { it in keep } }
        }
    }

    private fun blockHash(number: Long): String? =
        web3.ethGetBlockByNumber(DefaultBlockParameter.valueOf(BigInteger.valueOf(number)), false).send().block?.hash

    /** What the scans found, to keep on the phone and give back to [restoreScans] after a restart. */
    fun savedScans(): String = synchronized(scans) {
        val a = anchor ?: return ""
        json.writeValueAsString(
            mapOf(
                "escrow" to deployment.escrow.lowercase(),
                "anchor" to listOf(a.first, a.second),
                "scans" to scans.mapValues { (_, s) -> mapOf("to" to s.to, "found" to s.found.toList()) },
            ),
        )
    }

    /** Takes back what [savedScans] gave. Checked against the chain before it is used; ignored when malformed. */
    fun restoreScans(saved: String) {
        if (saved.isBlank()) return
        runCatching {
            val n = json.readTree(saved)
            require(n.get("escrow").asText() == deployment.escrow.lowercase())
            val a = n.get("anchor") ?: return
            synchronized(scans) {
                n.get("scans").fields().forEach { (key, s) ->
                    scans[key] = Scan(s.get("to").asLong()).apply { s.get("found").forEach { found += it.asText() } }
                }
                anchor = a.get(0).asLong() to a.get(1).asText()
                anchorChecked = false
            }
        }
    }

    private val json = com.fasterxml.jackson.databind.ObjectMapper()

    // ---------------------------------------------------------------- what changed

    /**
     * Whether anything this account's screen shows may have changed on chain since block `after`, and the newest
     * block looked at. One batch of log queries over the new blocks: the escrow's events (rides in `rides` or
     * opened naming one of `accounts`, payments to this account, a change of its settings), money arriving in
     * this account, any governance change to policies, the tariff or the map packs, and the registry's events
     * about this account. Changes it does not cover, such as a rating someone else gave, wait for a full read.
     * A chain that went backwards, or more new blocks than one query covers, count as a change.
     *
     * The last [RECHECK_BLOCKS] blocks before the new ones are read again each time, and an event counts once:
     * an event a node behind the others left out, or that a short reorganisation of the chain put in later, is
     * still seen at a later check, as long as it is no older than that.
     */
    fun changedSince(after: Long, rides: Set<BigInteger>, accounts: Set<String>): Pair<Long, Boolean> {
        val head = blockNumber().toLong()
        if (head == after) return head to false
        if (head < after || head - after > LOG_RANGE) return head to true
        val from = DefaultBlockParameter.valueOf(BigInteger.valueOf(maxOf(deployment.startBlock, after - RECHECK_BLOCKS + 1)))
        val to = DefaultBlockParameter.valueOf(BigInteger.valueOf(head))
        val me = topicOf(address)
        val filters = listOf(
            EthFilter(from, to, deployment.escrow),
            EthFilter(from, to, listOf(deployment.brz, deployment.brla)).addSingleTopic(TRANSFER_TOPIC).addNullTopic().addSingleTopic(me),
            EthFilter(from, to, listOf(deployment.governor, deployment.fares, deployment.maps)),
            EthFilter(from, to, deployment.registry).addNullTopic().addSingleTopic(me),
        )
        val logs = batch(filters.map { web3.ethGetLogs(it) }).map { r ->
            if (r.hasError()) throw ChainException(r.error.message)
            r.logs.map { it.get() as EthLog.LogObject }
        }
        val watched = accounts.mapTo(HashSet()) { topicOf(it) }
        val escrow = logs[0].filter { log ->
            val t = log.topics
            when (t.firstOrNull()) {
                RIDE_OPENED_TOPIC -> Numeric.toBigInt(t[1]) in rides || t[2] in watched || t[3] in watched
                in RIDE_EVENTS -> Numeric.toBigInt(t[1]) in rides
                in PAYMENT_EVENTS -> t.getOrNull(2) == me
                // Anything else the escrow says is a change of its settings: tax, coins, caps.
                else -> true
            }
        }
        val fresh = (escrow + logs.drop(1).flatten()).filter { !it.isRemoved }.map { it.blockHash + ":" + it.logIndex }
            .filter { synchronized(reported) { reported.add(it) } }
        synchronized(reported) { while (reported.size > REPORTED_KEPT) reported.remove(reported.first()) }
        return head to fresh.isNotEmpty()
    }

    /** Events [changedSince] already reported, by block hash and position, so the blocks it reads again do not count them twice. */
    private val reported = LinkedHashSet<String>()

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

    /**
     * The price guide in force. Finanças can change it at any time, so it is read again when the last read is
     * older than [TARIFF_FRESH_MS]: a running phone prices on the new tariff within that, without a restart.
     * When that read fails, the last tariff read is used and the next attempt waits as long again, so a chain
     * node that stops answering does not hold up matching, which needs the tariff, on every call.
     */
    val tariff: Tariff
        get() {
            // A monotonic clock: a phone's wall clock can step backwards and keep a stale tariff fresh.
            val now = System.nanoTime()
            val last = tariffRead
            if (last != null && now - last.second <= TARIFF_FRESH_MS * 1_000_000) return last.first
            val read = try {
                val t = call(
                    deployment.fares,
                    fn("tariff", emptyList(), listOf(uint32Ref, uint32Ref, uint32Ref, uint32Ref, uint16Ref, uint16Ref)),
                )
                Tariff(uint(t[0]).toLong(), uint(t[1]).toLong(), uint(t[2]).toLong(), uint(t[3]).toLong(), uint(t[4]).toInt(), uint(t[5]).toInt())
            } catch (e: Exception) {
                if (last == null) throw e
                last.first
            }
            tariffRead = read to now
            return read
        }

    @Volatile private var tariffRead: Pair<Tariff, Long>? = null

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
        // One tariff for both ends of the band, even if it is read again in between.
        val t = tariff
        val ceiling = coinFor(request.token).fromCentavos(t.driverCeiling(request.route.distanceM, request.route.durationS))
        return fare >= t.driverFloor(request.maxFare) && fare <= ceiling
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

    // ---------------------------------------------------------------- the session key, for gossip

    @Volatile private var session: SessionKey? = null

    /** Called with each new session key, so the app can keep it across restarts. */
    var onNewSession: ((SessionKey) -> Unit)? = null

    /** Uses a session key kept from before, if its certificate is this account's and still has time left. */
    fun resumeSession(saved: SessionKey) {
        if (saved.certificate.account.equals(address, ignoreCase = true) && !saved.expiresSoon()) session = saved
    }

    /** The session key in use, made (and certified by the device key) when there is none or it expires soon. */
    @Synchronized
    fun sessionKey(): SessionKey {
        session?.takeIf { !it.expiresSoon() }?.let { return it }
        return SessionKey.create(account, deployment.chainId).also {
            session = it
            onNewSession?.invoke(it)
        }
    }

    /**
     * Signs a 32-byte hash for gossip, with the session key, as an Ethereum signed message. Returns r, s, v
     * (65 bytes). A peer recovers the session key's address from it and checks its certificate ([certified]).
     */
    fun sign(hash: ByteArray): ByteArray = sessionKey().sign(hash)

    /** Certificates the chain confirmed, by hash of certificate and signature: kept for good, up to a bound. */
    private val certified = boundedSet(CERTIFICATES_KEPT)

    /** Certificates the chain refused, and when: asked again after [REFUSAL_KEPT_MS]. */
    private val refused = boundedMap<Long>(CERTIFICATES_KEPT)

    /** When each session key last had its certificate looked up. */
    private val lookups = boundedMap<ArrayDeque<Long>>(CERTIFICATES_KEPT)

    /** Whether the account already confirmed this certificate, so that [certified] will ask the chain nothing. */
    fun confirmed(certificate: SessionCertificate): Boolean {
        val id = Numeric.toHexString(Hash.sha3(certificate.hash(deployment.chainId) + certificate.signature))
        return synchronized(certified) { id in certified }
    }

    /**
     * Whether `certificate` really lets its session key speak for its account: the account's own keys signed it,
     * as the account says on chain (`isValidSignature`), and it has not expired nor claims to last more than two
     * days. A confirmation is kept, so each certificate costs one read; a refusal is kept a minute, and a read
     * that failed (the node did not answer) is not kept at all. Uncached lookups are limited per session key,
     * so the same forged certificate sent again costs nothing more; a forged certificate with a fresh session key
     * costs one read (limit P4). Limiting them per claimed account instead would let a stranger's forgeries use up
     * an honest account's lookups and silence it for as long as the flood lasted.
     */
    fun certified(certificate: SessionCertificate, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        if (certificate.expiresAt <= nowSeconds || certificate.expiresAt > nowSeconds + 2 * SessionKey.LIFETIME_SECONDS) return false
        val hash = certificate.hash(deployment.chainId)
        val id = Numeric.toHexString(Hash.sha3(hash + certificate.signature))
        if (synchronized(certified) { id in certified }) return true
        val now = System.currentTimeMillis()
        synchronized(refused) { refused[id]?.let { if (now - it < REFUSAL_KEPT_MS) return false } }
        val session = certificate.account.lowercase() + "/" + certificate.session.lowercase()
        synchronized(lookups) {
            val recent = lookups.getOrPut(session) { ArrayDeque() }
            while (recent.isNotEmpty() && now - recent.first() > LOOKUP_WINDOW_MS) recent.removeFirst()
            if (recent.size >= LOOKUPS_PER_WINDOW) return false
            recent.addLast(now)
        }
        val answer = runCatching {
            val out = call(
                certificate.account,
                fn("isValidSignature", listOf(Bytes32(hash), DynamicBytes(certificate.signature)), listOf(bytes4Ref)),
            )
            (out[0] as Bytes4).value.contentEquals(ERC1271_MAGIC)
        }.getOrNull() ?: return false
        if (answer) synchronized(certified) { certified += id } else synchronized(refused) { refused[id] = now }
        return answer
    }

    // ---------------------------------------------------------------- writes

    /** Claims roles (1 rider, 2 driver, 4 validator) if this account does not hold them yet. */
    fun ensureRegistered(roles: Int) {
        if (rolesOf() and roles == roles) return
        send(deployment.registry, fn("register", listOf(Uint8(roles.toLong())), emptyList()))
    }

    /** Stakes `amount` of the stake coin (BRLA) in the registry, as a driver must to accept rides. */
    fun addStake(amount: BigInteger) {
        val stakeCoin = brla
        sendCalls(
            listOfNotNull(
                approval(stakeCoin, deployment.registry, amount),
                Call(deployment.registry, FunctionEncoder.encode(fn("addStake", listOf(Uint256(amount)), emptyList()))),
            ),
        )
    }

    /** Rider locks the fare plus the rider bond. Returns the new ride id. */
    fun openRide(driver: String, coin: Coin, fare: BigInteger, quoteHash: ByteArray): BigInteger {
        val (_, riderBond, _) = tokenConfig(coin)
        val open = fn("open", listOf(Address(driver), Address(coin.address), Uint256(fare), Bytes32(quoteHash)), listOf(uint256Ref))
        val receipt = sendCalls(
            listOfNotNull(approval(coin, deployment.escrow, fare + riderBond), Call(deployment.escrow, FunctionEncoder.encode(open))),
            gasCoin = coin,
        )
        val log = receipt.logs.first {
            it.address.equals(deployment.escrow, ignoreCase = true) && it.topics.firstOrNull() == RIDE_OPENED_TOPIC
        }
        return Numeric.toBigInt(log.topics[1]).also {
            seenFromTheStart += it
            paid(it, receipt)
        }
    }

    /** Driver accepts and posts the driver bond. */
    fun accept(rideId: BigInteger) {
        val r = ride(rideId)
        val coin = coinFor(r.token)
        val accept = FunctionEncoder.encode(fn("accept", listOf(Uint256(rideId)), emptyList()))
        val accepted = sendCalls(
            listOfNotNull(approval(coin, deployment.escrow, r.driverBond), Call(deployment.escrow, accept)),
            gasCoin = coin,
        )
        seenFromTheStart += rideId
        paid(rideId, accepted)
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

    /** Driver: turns down a ride not yet accepted. Reverts once it is accepted, so it never costs the bond. */
    fun decline(rideId: BigInteger) = paid(rideId, send(deployment.escrow, fn("decline", listOf(Uint256(rideId)), emptyList())))

    fun rate(rideId: BigInteger, stars: Int) =
        paid(rideId, send(deployment.reputation, fn("rate", listOf(Address(deployment.escrow), Uint256(rideId), Uint8(stars.toLong())), emptyList())))

    /**
     * Test reais for this account, on the local testnet only, where they stand in for a Pix: the mock coins mint
     * to anyone, and the faucet (anvil's public deployer account) sends the transaction, since a new account has
     * nothing yet to pay its own gas with.
     */
    fun mint(coin: Coin, amount: BigInteger) = Faucet(web3, deployment).mint(coin, address, amount)

    fun coinFor(token: String): Coin =
        coins.firstOrNull { it.address.equals(token, ignoreCase = true) } ?: throw ChainException("Unknown coin $token")

    /** The approval `spender` needs to take `amount`, or null when it has it already. */
    private fun approval(coin: Coin, spender: String, amount: BigInteger): Call? {
        val allowance = uint(
            call(coin.address, fn("allowance", listOf(Address(address), Address(spender)), listOf(uint256Ref)))[0],
        )
        if (allowance >= amount) return null
        return Call(coin.address, FunctionEncoder.encode(fn("approve", listOf(Address(spender), Uint256(amount)), listOf(boolRef))))
    }

    // ---------------------------------------------------------------- plumbing

    private fun call(to: String, function: Function): List<Type<*>> = decode(ethCall(to, function).send(), function)

    private fun ethCall(to: String, function: Function) = web3.ethCall(
        Transaction.createEthCallTransaction(address, to, FunctionEncoder.encode(function)),
        DefaultBlockParameterName.LATEST,
    )

    private fun decode(res: EthCall, function: Function): List<Type<*>> {
        if (res.hasError() || res.isReverted) throw ChainException(describeRevert(res.error?.data, res.error?.message ?: res.revertReason))
        @Suppress("UNCHECKED_CAST")
        return FunctionReturnDecoder.decode(res.value, function.outputParameters as List<TypeReference<Type<*>>>)
    }

    /** Several reads in JSON-RPC batches: one round trip to the node per [BATCH] reads, answers in the order asked. */
    private fun callAll(calls: List<Pair<String, Function>>): List<List<Type<*>>> {
        val responses = batch(calls.map { (to, f) -> ethCall(to, f) })
        return calls.mapIndexed { i, (_, f) -> decode(responses[i], f) }
    }

    /** Sends requests to the node in batches and gives each its own answer, matched by id, in the order asked. */
    private fun <R : Response<*>> batch(requests: List<Request<*, R>>): List<R> = requests.chunked(BATCH).flatMap { chunk ->
        val b = web3.newBatch()
        chunk.forEach { b.add(it) }
        val byId = b.send().responses.associateBy { it.id }
        @Suppress("UNCHECKED_CAST")
        chunk.map { r -> (byId[r.id] ?: throw ChainException("the node did not answer every request in a batch")) as R }
    }

    private fun send(to: String, function: Function): Bundler.Receipt =
        sendCalls(listOf(Call(to, FunctionEncoder.encode(function))))

    /**
     * Sends `calls` as one operation of this account, all or nothing, and waits until it is on chain. Gas is paid
     * through the paymaster in `gasCoin`, or else in the coin the account holds most of. The bundler simulates it
     * first, so a call that would revert fails here with the contract's error name and costs nothing.
     */
    @Synchronized
    fun sendCalls(calls: List<Call>, gasCoin: Coin? = null): Bundler.Receipt {
        val coin = gasCoin?.takeIf { balanceOf(it).signum() > 0 } ?: coins.maxByOrNull { it.toReais(balanceOf(it)) }
            ?.takeIf { balanceOf(it).signum() > 0 }
            ?: throw ChainException("This account has no reais to pay the network with. Add money first.")
        val deployed = web3.ethGetCode(address, DefaultBlockParameterName.LATEST).send().code.let { it != null && it != "0x" }
        val nonce = uint(call(deployment.entryPoint, fn("getNonce", listOf(Address(address), Uint192(BigInteger.ZERO)), listOf(uint256Ref)))[0])
        val block = web3.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().block
        val priority = runCatching { web3.ethMaxPriorityFeePerGas().send().maxPriorityFeePerGas }.getOrNull() ?: BigInteger.valueOf(1_000_000)
        val maxFee = (block.baseFeePerGas ?: BigInteger.ZERO) * BigInteger.TWO + priority
        var op = UserOperation(
            sender = address,
            nonce = nonce,
            factory = if (deployed) null else deployment.accountFactory,
            factoryData = if (deployed) null else account.factoryData,
            callData = SmartAccount.execute(calls),
            maxFeePerGas = maxFee,
            maxPriorityFeePerGas = priority,
            paymaster = deployment.paymaster,
            paymasterVerificationGasLimit = BigInteger.valueOf(300_000),
            paymasterPostOpGasLimit = BigInteger.valueOf(100_000),
            paymasterData = coin.address,
            // A signature of the right shape, so the bundler's simulation measures the real path's gas.
            signature = Numeric.toHexString(SmartAccount.encodeSignature(account.signer, ByteArray(64) { 1 })),
        )
        val estimate = bundler.estimate(op)
        // The bundler's word is not taken for what the account pays: limits above these, or a worst case above
        // what any of this account's operations should cost, are refused before anything is signed.
        fun ceiling(name: String, v: BigInteger?, max: Long) {
            if (v != null && v > BigInteger.valueOf(max)) throw ChainException("the bundler asked for $name $v, more than the $max an operation here needs: refused")
        }
        ceiling("preVerificationGas", estimate.preVerificationGas, MAX_PRE_VERIFICATION_GAS)
        ceiling("verificationGasLimit", estimate.verificationGasLimit, MAX_VERIFICATION_GAS)
        ceiling("callGasLimit", estimate.callGasLimit, MAX_CALL_GAS)
        ceiling("paymasterVerificationGasLimit", estimate.paymasterVerificationGasLimit, MAX_PAYMASTER_VERIFICATION_GAS)
        ceiling("paymasterPostOpGasLimit", estimate.paymasterPostOpGasLimit, MAX_POST_OP_GAS)
        if (maxFee > BigInteger.valueOf(MAX_FEE_PER_GAS)) throw ChainException("the network asks $maxFee wei per gas, over the $MAX_FEE_PER_GAS this app pays: try again later")
        fun margin(v: BigInteger) = v * BigInteger.valueOf(12) / BigInteger.TEN
        op = op.copy(
            preVerificationGas = margin(estimate.preVerificationGas),
            // The real signature is checked by the chip's curve, which the placeholder never reaches.
            verificationGasLimit = margin(estimate.verificationGasLimit) + BigInteger.valueOf(100_000),
            callGasLimit = margin(estimate.callGasLimit),
            paymasterVerificationGasLimit = estimate.paymasterVerificationGasLimit?.let(::margin) ?: op.paymasterVerificationGasLimit,
            paymasterPostOpGasLimit = estimate.paymasterPostOpGasLimit?.let(::margin) ?: op.paymasterPostOpGasLimit,
        )
        // The most this operation can cost the account, in reais at the rate the paymaster charges.
        val worst = (op.preVerificationGas + op.verificationGasLimit + op.callGasLimit + op.paymasterVerificationGasLimit + op.paymasterPostOpGasLimit) * op.maxFeePerGas
        val worstReais = infrastructureCost(worst)
        if (worstReais > MAX_OPERATION_REAIS) throw ChainException("this operation could cost up to R$ $worstReais in network fees: refused")
        op = op.copy(signature = Numeric.toHexString(account.signature(op.hash(deployment.chainId, deployment.entryPoint))))
        val receipt = bundler.receipt(bundler.send(op))
        if (!receipt.success) throw ChainException("the operation reverted on chain" + (receipt.reason?.let { ": " + describeRevert(it, it) } ?: ""))
        return receipt
    }

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
        val RIDER_RIDE_GAS: BigInteger = BigInteger.valueOf(678_000)
        val DRIVER_RIDE_GAS: BigInteger = BigInteger.valueOf(236_000)

        /** How long a tax rate read from the escrow is used before it is read again. */
        private const val TAX_FRESH_MS = 10_000L

        /** How long the tariff read from `FareGuide` is used before it is read again. */
        private const val TARIFF_FRESH_MS = 30_000L

        const val ROLE_RIDER = 1
        const val ROLE_DRIVER = 2

        // What one operation of this app may ask for. Its heaviest (creating the account, then a ride's lock) stays
        // well under each; a bundler asking more is refused rather than trusted (see sendCalls).
        private const val MAX_PRE_VERIFICATION_GAS = 200_000L
        private const val MAX_VERIFICATION_GAS = 1_500_000L
        private const val MAX_CALL_GAS = 3_000_000L
        private const val MAX_PAYMASTER_VERIFICATION_GAS = 300_000L
        private const val MAX_POST_OP_GAS = 150_000L
        /** 1 000 gwei: far above what Polygon PoS charges in normal times. */
        private const val MAX_FEE_PER_GAS = 1_000_000_000_000L
        /** No operation of a ride should cost the account more than this in network fees, at the worst. */
        private val MAX_OPERATION_REAIS = BigDecimal("5.00")

        private const val CERTIFICATES_KEPT = 4_096
        private const val REFUSAL_KEPT_MS = 60_000L
        private const val LOOKUP_WINDOW_MS = 60_000L
        private const val LOOKUPS_PER_WINDOW = 4

        private fun boundedSet(max: Int): MutableSet<String> = java.util.Collections.newSetFromMap(boundedMap<Boolean>(max))

        /** A map that forgets its oldest entry beyond `max`: what strangers can fill is bounded. */
        private fun <V> boundedMap(max: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
        }

        /** `isValidSignature`'s answer for a good signature (ERC-1271). */
        private val ERC1271_MAGIC = Numeric.hexStringToByteArray("0x1626ba7e")

        /** The bundler a local testnet runs, next to the chain node (`testnet/docker-compose.yml`). */
        fun defaultBundler(rpcUrl: String): String {
            val uri = java.net.URI(rpcUrl.trim())
            return "${uri.scheme}://${uri.host}:4337/rpc"
        }

        /** A bundler's refusal, with the contract's error name when its data carries one. */
        fun describeBundlerError(message: String?, data: String?): String {
            val named = data?.let { Regex("0x[0-9a-fA-F]{8,}").findAll(it).firstNotNullOfOrNull { m -> ERRORS[m.value.take(10).lowercase()] } }
                ?: message?.let { Regex("0x[0-9a-fA-F]{8,}").findAll(it).firstNotNullOfOrNull { m -> ERRORS[m.value.take(10).lowercase()] } }
            return named ?: message ?: "the bundler refused the operation"
        }

        val RIDE_OPENED_TOPIC: String = Hash.sha3String("RideOpened(uint256,address,address,address,uint256,bytes32)")
        val REGISTERED_TOPIC: String = Hash.sha3String("Registered(address,uint8)")
        private val TRANSFER_TOPIC: String = Hash.sha3String("Transfer(address,address,uint256)")

        /** The escrow's events about one ride, which name it first. */
        private val RIDE_EVENTS: Set<String> = listOf(
            "RideAccepted(uint256)", "RideStarted(uint256,bytes32)", "ReceiptSubmitted(uint256,address,bytes32)",
            "RideCompleted(uint256,uint256,uint256,uint256)", "CompletionNotRecorded(uint256)", "RideCancelled(uint256,address)",
            "RideDisputed(uint256)", "RideResolved(uint256,uint256,uint256,uint256,uint256,uint256)",
        ).mapTo(HashSet(), Hash::sha3String)

        /** The escrow's events about money held for someone, which name them second. */
        private val PAYMENT_EVENTS: Set<String> = listOf("PaymentHeld(address,address,uint256)", "Withdrawn(address,address,uint256)")
            .mapTo(HashSet(), Hash::sha3String)

        /** Blocks one log query covers at most: under what public nodes accept. */
        private const val LOG_RANGE = 2_000L

        /** Blocks read again by every scan, in case the chain reorganised them since. */
        private const val REORG_BLOCKS = 64L

        /** Reads sent to the node in one request: within what public nodes accept in a batch. */
        private const val BATCH = 20

        /** Blocks the change check reads again each time. */
        private const val RECHECK_BLOCKS = 16L

        /** Events the change check remembers having reported: far more than 16 blocks hold for one account. */
        private const val REPORTED_KEPT = 2_048

        private val CREDENTIAL_CNH = Numeric.hexStringToByteArray(Hash.sha3String("klippelgo.credential.CNH"))
        private val uint256Ref = object : TypeReference<Uint256>() {}
        private val uint64Ref = object : TypeReference<Uint64>() {}
        private val uint32Ref = object : TypeReference<Uint32>() {}
        private val uint16Ref = object : TypeReference<Uint16>() {}
        private val uint8Ref = object : TypeReference<Uint8>() {}
        private val addressRef = object : TypeReference<Address>() {}
        private val bytes32Ref = object : TypeReference<Bytes32>() {}
        private val bytes4Ref = object : TypeReference<Bytes4>() {}
        private val boolRef = object : TypeReference<Bool>() {}

        /** Contract errors, so a revert reads as `NotPermitted` rather than raw bytes. */
        private val ERRORS: Map<String, String> = listOf(
            "TokenNotAllowed()", "FareTooLow()", "WrongStatus(uint8)", "DriverBusy(uint256)", "NotRider()", "NotDriver()", "NotParty()",
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
