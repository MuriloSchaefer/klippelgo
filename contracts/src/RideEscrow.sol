// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Governed} from "./Governed.sol";
import {IPBACGovernor} from "./interfaces/IPBACGovernor.sol";
import {Reputation} from "./Reputation.sol";

/// @notice Holds a ride's fare between pickup and arrival. The chain sees only addresses, the token, the fare
/// and hashes of the quote, the pickup proof and the trip receipt; exact places stay in the riders' and drivers'
/// encrypted channel.
///
/// Open -> Accepted -> Started -> Completed, with Cancelled from Open/Accepted and Disputed -> Resolved from
/// Accepted/Started. On completion the driver gets the fare minus a flat fee (R$ 1,00 in that token's units),
/// which goes to the FeeVault for Instituto Klippel, and minus the government's tax, a share of the fare that
/// goes to the tax collector. The rider pays the fare and nothing on top of it.
contract RideEscrow is Governed, ReentrancyGuard {
    using SafeERC20 for IERC20;

    enum Status {
        None,
        Open,
        Accepted,
        Started,
        Completed,
        Cancelled,
        Disputed,
        Resolved
    }

    struct Ride {
        address rider;
        address driver;
        IERC20 token;
        Status status;
        uint64 openedAt;
        uint64 startedAt;
        uint256 fare;
        uint256 fee;
        uint256 riderBond;
        uint256 driverBond;
        bytes32 quoteHash;
        bytes32 pickupProofHash;
        bytes32 receiptHash;
        uint64 receiptAt;
        uint256 tax; // the government's share of the fare, fixed when the ride opened
        address taxCollector; // who it is paid to, fixed then too
    }

    /// @dev Per-token settings. A token is on the allow-list when `fee` is non-zero.
    struct TokenConfig {
        uint256 fee;
        uint256 riderBond;
        uint256 driverBond;
    }

    uint256 public constant BPS = 10_000;

    Reputation public reputation;
    address public feeVault;
    address public court;
    uint64 public completionTimeout;
    /// @notice The government's tax on a ride, as a share of the fare, and the account it is paid to.
    uint16 public taxBps;
    address public taxCollector;

    uint256 public nextRideId = 1;
    mapping(uint256 => Ride) internal _rides;
    mapping(IERC20 => TokenConfig) public tokenConfig;

    event RideOpened(
        uint256 indexed rideId,
        address indexed rider,
        address indexed driver,
        address token,
        uint256 fare,
        bytes32 quoteHash
    );
    event RideAccepted(uint256 indexed rideId);
    event RideStarted(uint256 indexed rideId, bytes32 pickupProofHash);
    event ReceiptSubmitted(uint256 indexed rideId, address indexed by, bytes32 receiptHash);
    event RideCompleted(uint256 indexed rideId, uint256 driverPayout, uint256 fee, uint256 tax);
    event RideCancelled(uint256 indexed rideId, address indexed by);
    event RideDisputed(uint256 indexed rideId);
    event RideResolved(
        uint256 indexed rideId,
        uint256 riderBps,
        uint256 riderPayout,
        uint256 driverPayout,
        uint256 fee,
        uint256 tax
    );
    event TokenConfigured(address indexed token, uint256 fee, uint256 riderBond, uint256 driverBond);
    event ModulesSet(address reputation, address feeVault, address court);
    event CompletionTimeoutSet(uint64 timeout);
    event TaxSet(uint16 taxBps, address collector);

    error TokenNotAllowed();
    error FareTooLow();
    error WrongStatus(Status status);
    error NotRider();
    error NotDriver();
    error NotParty();
    error NotCourt();
    error TooEarly();
    error NoReceipt();
    error ReceiptMismatch();
    error InvalidBps();
    error NoTaxCollector();

    constructor(IPBACGovernor governor_, uint64 completionTimeout_) Governed(governor_) {
        completionTimeout = completionTimeout_;
    }

    // ---------------------------------------------------------------- ride lifecycle

    /// @notice Rider locks the fare for an off-chain quote the driver signed in the P2P handshake.
    function open(address driver, IERC20 token, uint256 fare, bytes32 quoteHash)
        external
        permitted
        nonReentrant
        returns (uint256 rideId)
    {
        TokenConfig memory cfg = tokenConfig[token];
        if (cfg.fee == 0) revert TokenNotAllowed();
        uint256 tax = fare * taxBps / BPS;
        if (fare <= cfg.fee + tax) revert FareTooLow();

        rideId = nextRideId++;
        _rides[rideId] = Ride({
            rider: msg.sender,
            driver: driver,
            token: token,
            status: Status.Open,
            openedAt: uint64(block.timestamp),
            startedAt: 0,
            fare: fare,
            fee: cfg.fee,
            riderBond: cfg.riderBond,
            driverBond: cfg.driverBond,
            quoteHash: quoteHash,
            pickupProofHash: bytes32(0),
            receiptHash: bytes32(0),
            receiptAt: 0,
            tax: tax,
            taxCollector: taxCollector
        });
        token.safeTransferFrom(msg.sender, address(this), fare + cfg.riderBond);
        emit RideOpened(rideId, msg.sender, driver, address(token), fare, quoteHash);
    }

    /// @notice Driver accepts and posts a cancel bond. Gated by policy (stake, score, CNH credential).
    function accept(uint256 rideId) external permitted nonReentrant {
        Ride storage r = _rides[rideId];
        if (r.status != Status.Open) revert WrongStatus(r.status);
        if (msg.sender != r.driver) revert NotDriver();
        r.status = Status.Accepted;
        r.token.safeTransferFrom(msg.sender, address(this), r.driverBond);
        emit RideAccepted(rideId);
    }

    /// @notice Driver records the hash of the pickup proof both phones signed over BLE or QR.
    function start(uint256 rideId, bytes32 pickupProofHash) external {
        Ride storage r = _rides[rideId];
        if (r.status != Status.Accepted) revert WrongStatus(r.status);
        if (msg.sender != r.driver) revert NotDriver();
        r.status = Status.Started;
        r.startedAt = uint64(block.timestamp);
        r.pickupProofHash = pickupProofHash;
        emit RideStarted(rideId, pickupProofHash);
    }

    /// @notice Rider confirms arrival with the hash of the trip receipt both phones signed. If the driver already
    /// put a receipt on record it must be the same one: a rider who disagrees with it raises a dispute instead.
    function complete(uint256 rideId, bytes32 receiptHash) external nonReentrant {
        Ride storage r = _rides[rideId];
        if (r.status != Status.Started) revert WrongStatus(r.status);
        if (msg.sender != r.rider) revert NotRider();
        if (receiptHash == bytes32(0)) revert NoReceipt();
        if (r.receiptAt == 0) {
            r.receiptHash = receiptHash;
            r.receiptAt = uint64(block.timestamp);
            emit ReceiptSubmitted(rideId, msg.sender, receiptHash);
        } else if (r.receiptHash != receiptHash) {
            revert ReceiptMismatch();
        }
        _settle(rideId, r);
    }

    /// @notice Driver puts their trip receipt on record when the rider does not confirm arrival. This starts
    /// the clock for `timeout` and commits the driver to their evidence before any dispute.
    function submitReceipt(uint256 rideId, bytes32 receiptHash) external {
        Ride storage r = _rides[rideId];
        if (r.status != Status.Started) revert WrongStatus(r.status);
        if (msg.sender != r.driver) revert NotDriver();
        if (receiptHash == bytes32(0)) revert NoReceipt();
        if (r.receiptAt != 0) revert ReceiptMismatch();
        r.receiptHash = receiptHash;
        r.receiptAt = uint64(block.timestamp);
        emit ReceiptSubmitted(rideId, msg.sender, receiptHash);
    }

    /// @notice Anyone can settle a ride once the driver's receipt has stood for the timeout with no dispute.
    function timeout(uint256 rideId) external nonReentrant {
        Ride storage r = _rides[rideId];
        if (r.status != Status.Started) revert WrongStatus(r.status);
        if (r.receiptAt == 0) revert NoReceipt();
        if (block.timestamp < r.receiptAt + completionTimeout) revert TooEarly();
        _settle(rideId, r);
    }

    /// @notice Before acceptance the rider can withdraw and the chosen driver can decline; either way the rider
    /// gets everything back and nobody pays. After acceptance the canceller's bond goes to the other party.
    function cancel(uint256 rideId) external nonReentrant {
        Ride storage r = _rides[rideId];
        if (r.status == Status.Open) {
            if (msg.sender != r.rider && msg.sender != r.driver) revert NotParty();
            r.status = Status.Cancelled;
            r.token.safeTransfer(r.rider, r.fare + r.riderBond);
        } else if (r.status == Status.Accepted) {
            r.status = Status.Cancelled;
            if (msg.sender == r.rider) {
                r.token.safeTransfer(r.rider, r.fare);
                r.token.safeTransfer(r.driver, r.driverBond + r.riderBond);
            } else if (msg.sender == r.driver) {
                r.token.safeTransfer(r.rider, r.fare + r.riderBond + r.driverBond);
            } else {
                revert NotParty();
            }
        } else {
            revert WrongStatus(r.status);
        }
        emit RideCancelled(rideId, msg.sender);
    }

    // ---------------------------------------------------------------- disputes

    function freeze(uint256 rideId) external {
        if (msg.sender != court) revert NotCourt();
        Ride storage r = _rides[rideId];
        if (r.status != Status.Accepted && r.status != Status.Started) revert WrongStatus(r.status);
        r.status = Status.Disputed;
        emit RideDisputed(rideId);
    }

    /// @notice Court splits the fare: `riderBps` of it goes back to the rider. Tax is due on the part the driver
    /// keeps, in the proportion the ride opened with. The fee is charged only when what is left of the driver's
    /// share covers it. Both bonds are returned.
    function resolve(uint256 rideId, uint256 riderBps) external nonReentrant {
        if (msg.sender != court) revert NotCourt();
        if (riderBps > BPS) revert InvalidBps();
        Ride storage r = _rides[rideId];
        if (r.status != Status.Disputed) revert WrongStatus(r.status);
        r.status = Status.Resolved;

        uint256 riderPart = r.fare * riderBps / BPS;
        uint256 driverPart = r.fare - riderPart;
        uint256 tax = r.tax * driverPart / r.fare;
        uint256 fee = driverPart - tax >= r.fee ? r.fee : 0;

        if (fee > 0) r.token.safeTransfer(feeVault, fee);
        if (tax > 0) r.token.safeTransfer(r.taxCollector, tax);
        r.token.safeTransfer(r.rider, riderPart + r.riderBond);
        r.token.safeTransfer(r.driver, driverPart - tax - fee + r.driverBond);
        emit RideResolved(rideId, riderBps, riderPart, driverPart - tax - fee, fee, tax);
    }

    // ---------------------------------------------------------------- views

    function ride(uint256 rideId) external view returns (Ride memory) {
        return _rides[rideId];
    }

    function parties(uint256 rideId) external view returns (address rider, address driver) {
        Ride storage r = _rides[rideId];
        return (r.rider, r.driver);
    }

    // ---------------------------------------------------------------- governance

    /// @notice Add or update a BRL token on the allow-list. `fee` is R$ 1,00 in the token's own decimals.
    /// A zero fee removes the token for new rides.
    function setToken(IERC20 token, uint256 fee, uint256 riderBond, uint256 driverBond)
        external
        onlyGovernor
    {
        tokenConfig[token] = TokenConfig(fee, riderBond, driverBond);
        emit TokenConfigured(address(token), fee, riderBond, driverBond);
    }

    function setModules(Reputation reputation_, address feeVault_, address court_) external onlyGovernor {
        reputation = reputation_;
        feeVault = feeVault_;
        court = court_;
        emit ModulesSet(address(reputation_), feeVault_, court_);
    }

    /// @notice Set the government's tax as a share of the fare, and who collects it. It applies to rides opened
    /// from now on: a ride keeps the tax and the collector it opened with. Zero switches it off.
    function setTax(uint16 taxBps_, address collector) external onlyGovernor {
        if (taxBps_ >= BPS) revert InvalidBps();
        if (taxBps_ > 0 && collector == address(0)) revert NoTaxCollector();
        taxBps = taxBps_;
        taxCollector = collector;
        emit TaxSet(taxBps_, collector);
    }

    function setCompletionTimeout(uint64 timeout_) external onlyGovernor {
        completionTimeout = timeout_;
        emit CompletionTimeoutSet(timeout_);
    }

    // ---------------------------------------------------------------- internal

    function _settle(uint256 rideId, Ride storage r) internal {
        r.status = Status.Completed;
        uint256 driverPayout = r.fare - r.fee - r.tax;
        r.token.safeTransfer(feeVault, r.fee);
        if (r.tax > 0) r.token.safeTransfer(r.taxCollector, r.tax);
        r.token.safeTransfer(r.driver, driverPayout + r.driverBond);
        if (r.riderBond > 0) r.token.safeTransfer(r.rider, r.riderBond);
        reputation.recordCompletion(rideId, r.rider, r.driver);
        emit RideCompleted(rideId, driverPayout, r.fee, r.tax);
    }
}
