package org.klippelgo.chain

import org.web3j.utils.Numeric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a phone computes for a change of its account's keys, with no chain: the id matches the contract's encoding
 * (`contracts/test/Accounts.t.sol`, `test_TheKeyChangeIdVector`), a change a node describes other than it was filed
 * is not listed, and a key code is read only when it is one.
 */
class KeyChangeVectorTest {
    @Test
    fun theIdIsTheContractsEncoding() {
        val id = KlippelGoClient.keyChangeId(0, listOf(Numeric.hexStringToByteArray("0x0102")), emptyList(), true, "0x0000000000000000000000000000000000001234")
        assertEquals("0x8048e897beb13fafbd8ccc2e2748997a9403b510688b0bf30189fb97e2b7bc92", Numeric.toHexString(id))
    }

    @Test
    fun aChangeDescribedOtherThanItWasFiledIsNotListed() {
        val add = listOf("0x0102")
        val id = KlippelGoClient.keyChangeId(3, add.map(Numeric::hexStringToByteArray), emptyList(), false, null)
        assertTrue(KlippelGoClient.isFiledAs(id, 3, add, emptyList(), false, null))
        // Another key added, a key removed instead, a guardian named, or another epoch: each is another change.
        assertFalse(KlippelGoClient.isFiledAs(id, 3, listOf("0x0103"), emptyList(), false, null))
        assertFalse(KlippelGoClient.isFiledAs(id, 3, emptyList(), add, false, null))
        assertFalse(KlippelGoClient.isFiledAs(id, 3, add, emptyList(), true, "0x0000000000000000000000000000000000001234"))
        assertFalse(KlippelGoClient.isFiledAs(id, 4, add, emptyList(), false, null))
    }

    @Test
    fun aKeyCodeIsReadOnlyWhenItIsOne() {
        val key = SoftwareDeviceKey(java.math.BigInteger.valueOf(0xA11CE))
        val verifier = "0x00000000000000000000000000000000000000aa"
        val code = KeyCodes.code(key.publicKey)
        val signer = KeyCodes.signerOf(code, verifier)!!
        assertEquals(Numeric.toHexString(Numeric.hexStringToByteArray(verifier) + key.publicKey), Numeric.toHexString(signer))
        // Spaces and line breaks from a message app do not matter.
        assertEquals(Numeric.toHexString(signer), Numeric.toHexString(KeyCodes.signerOf(code.chunked(20).joinToString("\n "), verifier)!!))
        // A digit changed on the way is no point on the curve; a short code, or none, is nothing.
        val last = code.last()
        assertNull(KeyCodes.signerOf(code.dropLast(1) + (if (last == '0') '1' else '0'), verifier))
        assertNull(KeyCodes.signerOf(code.dropLast(2), verifier))
        assertNull(KeyCodes.signerOf("hello", verifier))
        assertEquals(19, KeyCodes.fingerprint(signer).length)
    }

    @Test
    fun aCodeCarriesTheDevicesNameAsALabelOnly() {
        val key = SoftwareDeviceKey(java.math.BigInteger.valueOf(0xA11CE))
        val verifier = "0x00000000000000000000000000000000000000aa"
        val named = KeyCodes.code(key.publicKey, "Tablet da Clara #2")
        // The same key, whatever the name says.
        assertEquals(Numeric.toHexString(KeyCodes.signerOf(KeyCodes.code(key.publicKey), verifier)!!), Numeric.toHexString(KeyCodes.signerOf(named, verifier)!!))
        assertEquals("Tablet da Clara #2", KeyCodes.nameOf(named))
        assertNull(KeyCodes.nameOf(KeyCodes.code(key.publicKey)))
        // Shown cleaned: no control characters, and no longer than a line.
        assertEquals("ab", KeyCodes.nameOf(KeyCodes.code(key.publicKey, "a\u0000\nb")))
        assertEquals(KeyCodes.MAX_NAME, KeyCodes.nameOf(KeyCodes.code(key.publicKey, "x".repeat(200)))!!.length)
        // No direction overrides or zero-width marks, and never half of a character.
        assertEquals("ab", KeyCodes.nameOf(KeyCodes.code(key.publicKey, "a\u202Eb\u200B")))
        assertEquals(KeyCodes.MAX_NAME, KeyCodes.nameOf(KeyCodes.code(key.publicKey, "\uD83D\uDE97".repeat(50)))!!.codePointCount(0, KeyCodes.nameOf(KeyCodes.code(key.publicKey, "\uD83D\uDE97".repeat(50)))!!.length))
    }
}
