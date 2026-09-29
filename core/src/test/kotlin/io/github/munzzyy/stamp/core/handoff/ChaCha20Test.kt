package io.github.munzzyy.stamp.core.handoff

import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChaCha20Test {
    private val key = ByteArray(32) { it.toByte() }

    /** The cipher of the JDK, which the tests can start at a block of their choosing and Android before API 35 cannot. */
    private fun jdk(key: ByteArray, nonce: ByteArray, firstBlock: Int, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, firstBlock))
        return cipher.doFinal(data)
    }

    @Test
    fun oneBlockOfKeyStreamIsTheOneOfRfc8439Section232() {
        val stream = ChaCha20.xor(key, unhex("000000090000004a00000000"), 1, ByteArray(64))
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            hex(stream),
        )
    }

    @Test
    fun theSealedTextIsTheOneOfRfc8439Section242() {
        val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val nonce = unhex("000000000000004a00000000")
        val sealed = ChaCha20.xor(key, nonce, 1, plain.toByteArray(Charsets.US_ASCII))
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d",
            hex(sealed),
        )
        assertEquals(plain, String(ChaCha20.xor(key, nonce, 1, sealed), Charsets.US_ASCII))
        assertEquals(hex(sealed), hex(jdk(key, nonce, 1, plain.toByteArray(Charsets.US_ASCII))))
    }

    @Test
    fun itIsTheCipherOfTheJdkForEveryLengthAroundABlock() {
        val random = Random(20260929)
        for (length in (0..200) + listOf(255, 256, 257, 1023, 1024, 1025, 4096, 65_537)) {
            val key = ByteArray(32).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val data = ByteArray(length).also(random::nextBytes)
            for (firstBlock in listOf(0, 1, 42)) {
                assertArrayEquals("$length bytes from block $firstBlock", jdk(key, nonce, firstBlock, data), ChaCha20.xor(key, nonce, firstBlock, data))
            }
        }
    }

    @Test
    fun aFileOfTwoMebibytesComesOutAsTheJdkMakesIt() {
        val random = Random(7)
        val nonce = ByteArray(12).also(random::nextBytes)
        val data = ByteArray(2 * 1024 * 1024 + 81).also(random::nextBytes)
        val sealed = ChaCha20.xor(key, nonce, 1, data)
        assertArrayEquals(jdk(key, nonce, 1, data), sealed)
        assertArrayEquals(data, ChaCha20.xor(key, nonce, 1, sealed))
    }

    @Test
    fun whatItIsGivenStaysAsItWas() {
        val data = ByteArray(100) { 5 }
        val nonce = ByteArray(12)
        ChaCha20.xor(key, nonce, 1, data)
        assertArrayEquals(ByteArray(100) { 5 }, data)
        assertArrayEquals(ByteArray(12), nonce)
        assertArrayEquals(ByteArray(32) { it.toByte() }, key)
    }

    @Test
    fun aKeyOrANonceOfTheWrongLengthIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ChaCha20.xor(ByteArray(31), ByteArray(12), 1, ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { ChaCha20.xor(ByteArray(33), ByteArray(12), 1, ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { ChaCha20.xor(ByteArray(32), ByteArray(8), 1, ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { ChaCha20.xor(ByteArray(32), ByteArray(16), 1, ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { ChaCha20.xor(ByteArray(32), ByteArray(12), -1, ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { ChaCha20.xor(ByteArray(32), ByteArray(12), Int.MAX_VALUE, ByteArray(1)) }
    }
}
