package org.klippelgo.chain

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Bytes4
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
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
import java.math.BigInteger

/**
 * Talks to the KlippelGo contracts as one account. Every call is blocking, so run it off the main thread.
 *
 * Rider and driver never talk to each other here: they both read and write the same RideEscrow, so a ride the
 * rider opens shows up for the driver on chain, and each step the driver takes shows up for the rider. The P2P
 * quote handshake from the architecture plan comes later; for now the rider names the fare.
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

    fun ride(id: BigInteger): Ride {
        val out = call(
            deployment.escrow,
            fn(
                "ride",
                listOf(Uint256(id)),
                listOf(addressRef, addressRef, addressRef, uint8Ref, uint64Ref, uint64Ref) +
                    List(4) { uint256Ref } + listOf(bytes32Ref, bytes32Ref),
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
        )
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
        return registered.filter { rolesOf(it) and ROLE_DRIVER != 0 }.map { d ->
            val score = uint(call(deployment.reputation, fn("scoreOf", listOf(Address(d)), listOf(uint256Ref)))[0])
            val count = uint(call(deployment.reputation, fn("ratingCount", listOf(Address(d)), listOf(uint256Ref)))[0])
            val rides = uint(call(deployment.reputation, fn("completedRides", listOf(Address(d)), listOf(uint256Ref)))[0])
            DriverInfo(d, score.toInt(), count.toInt(), rides.toInt(), canAcceptRides(d))
        }
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

    // ---------------------------------------------------------------- writes

    /** Claims roles (1 rider, 2 driver, 4 validator) if this account does not hold them yet. */
    fun ensureRegistered(roles: Int) {
        if (rolesOf() and roles == roles) return
        send(deployment.registry, fn("register", listOf(Uint8(roles.toLong())), emptyList()))
    }

    /** Rider locks the fare plus the rider bond. Returns the new ride id. */
    fun openRide(driver: String, coin: Coin, fare: BigInteger, quoteHash: ByteArray): BigInteger {
        val (_, riderBond, _) = tokenConfig(coin)
        approveIfNeeded(coin, deployment.escrow, fare + riderBond)
        val receipt = send(
            deployment.escrow,
            fn("open", listOf(Address(driver), Address(coin.address), Uint256(fare), Bytes32(quoteHash)), listOf(uint256Ref)),
        )
        val log = receipt.logs.first { it.topics.firstOrNull() == RIDE_OPENED_TOPIC }
        return Numeric.toBigInt(log.topics[1])
    }

    /** Driver accepts and posts the driver bond. */
    fun accept(rideId: BigInteger) {
        val r = ride(rideId)
        approveIfNeeded(coinFor(r.token), deployment.escrow, r.driverBond)
        send(deployment.escrow, fn("accept", listOf(Uint256(rideId)), emptyList()))
    }

    /** Driver records the pickup proof hash. */
    fun start(rideId: BigInteger, pickupProofHash: ByteArray) =
        send(deployment.escrow, fn("start", listOf(Uint256(rideId), Bytes32(pickupProofHash)), emptyList()))

    /** Rider confirms arrival; the driver is paid and R$ 1,00 goes to Instituto Klippel. */
    fun complete(rideId: BigInteger) = send(deployment.escrow, fn("complete", listOf(Uint256(rideId)), emptyList()))

    fun cancel(rideId: BigInteger) = send(deployment.escrow, fn("cancel", listOf(Uint256(rideId)), emptyList()))

    fun rate(rideId: BigInteger, stars: Int) =
        send(deployment.reputation, fn("rate", listOf(Uint256(rideId), Uint8(stars.toLong())), emptyList()))

    fun coinFor(token: String): Coin =
        coins.firstOrNull { it.address.equals(token, ignoreCase = true) } ?: throw ChainException("Unknown coin $token")

    private fun approveIfNeeded(coin: Coin, spender: String, amount: BigInteger) {
        val allowance = uint(
            call(coin.address, fn("allowance", listOf(Address(address), Address(spender)), listOf(uint256Ref)))[0],
        )
        if (allowance < amount) {
            send(coin.address, fn("approve", listOf(Address(spender), Uint256(amount)), listOf(boolRef)))
        }
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
        const val ROLE_RIDER = 1
        const val ROLE_DRIVER = 2

        val RIDE_OPENED_TOPIC: String = Hash.sha3String("RideOpened(uint256,address,address,address,uint256,bytes32)")
        val REGISTERED_TOPIC: String = Hash.sha3String("Registered(address,uint8)")

        private val uint256Ref = object : TypeReference<Uint256>() {}
        private val uint64Ref = object : TypeReference<Uint64>() {}
        private val uint8Ref = object : TypeReference<Uint8>() {}
        private val addressRef = object : TypeReference<Address>() {}
        private val bytes32Ref = object : TypeReference<Bytes32>() {}
        private val boolRef = object : TypeReference<Bool>() {}

        /** Contract errors, so a revert reads as `NotPermitted` rather than raw bytes. */
        private val ERRORS: Map<String, String> = listOf(
            "TokenNotAllowed()", "FareTooLow()", "WrongStatus(uint8)", "NotRider()", "NotDriver()", "NotParty()",
            "TooEarly()", "NotPermitted(address,bytes4)", "NotRegistered()", "InvalidRoles()", "AlreadyRated()",
            "InvalidStars()", "NotParty()", "ERC20InsufficientBalance(address,uint256,uint256)",
            "ERC20InsufficientAllowance(address,uint256,uint256)",
        ).associate { selectorHex(it) to it.substringBefore('(') }

        fun selector(signature: String): ByteArray = Numeric.hexStringToByteArray(Hash.sha3String(signature)).copyOf(4)

        private fun selectorHex(signature: String) = Numeric.toHexString(selector(signature))

        private fun describeRevert(data: String?, fallback: String?): String {
            val sel = data?.takeIf { it.length >= 10 }?.substring(0, 10)?.lowercase()
            return ERRORS[sel] ?: fallback ?: "reverted"
        }

        private fun topicOf(address: String) = Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(address), 64)

        private fun fn(name: String, inputs: List<Type<*>>, outputs: List<TypeReference<*>>) =
            Function(name, inputs, outputs)

        private fun uint(t: Type<*>): BigInteger = t.value as BigInteger
    }
}
