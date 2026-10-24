// SPDX-License-Identifier: AGPL-3.0-only
pragma solidity ^0.8.28;

import {IERC7913SignatureVerifier} from "@openzeppelin/contracts/interfaces/IERC7913.sol";
import {P256} from "@openzeppelin/contracts/utils/cryptography/P256.sol";

/// @notice Checks a signature made by a phone's hardware key: a P-256 key in Android's StrongBox or Keystore, or
/// in the iPhone's Secure Enclave. Those chips sign only through SHA-256, so what the phone signs is the hash it
/// is asked to sign, hashed once more with SHA-256.
///
/// A signer of `KlippelGoAccount` is this verifier's address followed by the key (`qx || qy`, 64 bytes). The
/// signature is `r || s` (64 bytes), with `s` in the lower half of the curve's order, as `P256` requires.
contract DeviceKeyVerifier is IERC7913SignatureVerifier {
    function verify(bytes calldata key, bytes32 hash, bytes calldata signature)
        external
        view
        returns (bytes4)
    {
        if (key.length != 64 || signature.length != 64) return 0xffffffff;
        bytes32 digest = sha256(abi.encodePacked(hash));
        bool ok = P256.verify(
            digest,
            bytes32(signature[0:32]),
            bytes32(signature[32:64]),
            bytes32(key[0:32]),
            bytes32(key[32:64])
        );
        return ok ? IERC7913SignatureVerifier.verify.selector : bytes4(0xffffffff);
    }
}
