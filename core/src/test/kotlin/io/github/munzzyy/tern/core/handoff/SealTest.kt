package io.github.munzzyy.tern.core.handoff

import java.util.Base64
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SealTest {
    private val fixture: Map<String, String> = javaClass.getResource("/fixtures/handoff/sealed.txt")!!.readText()
        .lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
    private val code = fixture.getValue("code")
    private val things = listOf("links", "file", "empty", "blocks")

    private fun sealedOf(thing: String): ByteArray = Base64.getUrlDecoder().decode(fixture.getValue("$thing.sealed"))

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun theTagIsHmacSha256AsRfc4231HasIt() {
        val long = ByteArray(131) { 0xaa.toByte() }
        val cases = listOf(
            Triple(ByteArray(20) { 0x0b }, ascii("Hi There"), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            Triple(ascii("Jefe"), ascii("what do ya want for nothing?"), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            Triple(ByteArray(20) { 0xaa.toByte() }, ByteArray(50) { 0xdd.toByte() }, "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            Triple(ByteArray(25) { (it + 1).toByte() }, ByteArray(50) { 0xcd.toByte() }, "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
            Triple(long, ascii("Test Using Larger Than Block-Size Key - Hash Key First"), "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
            Triple(
                long,
                ascii("This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm."),
                "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            ),
        )
        for ((key, message, tag) in cases) assertEquals(tag, hex(Seal.hmac(key, message)))
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", hex(Seal.hmac(ByteArray(20) { 0x0b }, ascii("Hi There and more"), 8)))
    }

    @Test
    fun theKeysAreMadeOfTheCodeAsTheFixtureHasThem() {
        assertEquals(fixture.getValue("master"), hex(Seal.hmac(ascii(code), ascii("tern handoff v1"))))
        val keys = Seal.keysOf(code)
        assertEquals(fixture.getValue("enc"), hex(keys.enc))
        assertEquals(fixture.getValue("mac"), hex(keys.mac))
        assertEquals("ccc7a63a9f1dad8c6935b9b41b97338ea61d1e62bbc3967dd051bcb44beb7d76", hex(keys.enc))
        assertEquals("c2d6d6cace130d2e99c3a6dc834d5da46f9f89b606856e0dcb5a07b58fd536af", hex(keys.mac))
    }

    @Test
    fun whatWasSealedElsewhereOpens() {
        for (thing in things) {
            val opened = Seal(code).open(sealedOf(thing))
            assertNotNull(thing, opened)
            assertEquals(thing, fixture.getValue("$thing.kind").toInt(), opened!!.kind)
            assertEquals(thing, fixture.getValue("$thing.plain"), hex(opened.plain))
        }
    }

    @Test
    fun sealingMakesTheVeryBytesThatWereMadeElsewhere() {
        for (thing in things) {
            val sealed = Seal(code).seal(fixture.getValue("$thing.kind").toInt(), unhex(fixture.getValue("$thing.nonce")), unhex(fixture.getValue("$thing.plain")))
            assertEquals(thing, fixture.getValue("$thing.sealed"), Seal.text(sealed))
        }
    }

    @Test
    fun whatIsSealedOpensForEveryLength() {
        val random = Random(11)
        val seal = Seal(code)
        for (length in (0..130) + listOf(1000, 65_536, 2 * 1024 * 1024 + 81)) {
            val plain = ByteArray(length).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val sealed = seal.seal(length % 256, nonce, plain)
            assertEquals(1 + 12 + length + 32, sealed.size)
            assertEquals(Seal.text(sealed).length, Seal.textLength(length))
            val opened = seal.open(sealed)!!
            assertEquals(length % 256, opened.kind)
            assertArrayEquals(plain, opened.plain)
        }
    }

    @Test
    fun oneBitChangedAnywhereDoesNotOpen() {
        val sealed = sealedOf("links")
        for (i in sealed.indices) {
            for (bit in 0 until 8) {
                val changed = sealed.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
                assertNull("byte $i bit $bit", Seal(code).open(changed))
            }
        }
        assertNotNull(Seal(code).open(sealed))
    }

    @Test
    fun whatIsCutShortOrMadeLongerDoesNotOpen() {
        val sealed = sealedOf("file")
        for (length in 0 until sealed.size) assertNull("$length bytes", Seal(code).open(sealed.copyOf(length)))
        assertNull(Seal(code).open(sealed + byteArrayOf(0)))
        assertNull(Seal(code).open(byteArrayOf(0) + sealed))
        assertNull(Seal(code).open(sealed + sealed))
    }

    @Test
    fun theKindCannotBeSwapped() {
        val links = sealedOf("links")
        val asFile = links.copyOf().also { it[0] = Seal.FILE.toByte() }
        assertNull(Seal(code).open(asFile))
        val file = sealedOf("file")
        val asLinks = file.copyOf().also { it[0] = Seal.LINKS.toByte() }
        assertNull(Seal(code).open(asLinks))

        val sealedHere = Seal(code).seal(Seal.LINKS, ByteArray(12) { 7 }, ascii("example.org"))
        for (kind in 0..255) {
            val opened = Seal(code).open(sealedHere.copyOf().also { it[0] = kind.toByte() })
            assertEquals("kind $kind", kind == Seal.LINKS, opened != null)
        }
    }

    @Test
    fun anotherCodeDoesNotOpen() {
        val sealed = sealedOf("links")
        for (i in code.indices) {
            val other = code.substring(0, i) + (if (code[i] == 'a') 'b' else 'a') + code.substring(i + 1)
            assertNull(other, Seal(other).open(sealed))
        }
    }

    @Test
    fun whatHasBeenOpenedDoesNotOpenAgain() {
        val seal = Seal(code)
        val sealed = sealedOf("links")
        assertNotNull(seal.open(sealed))
        assertNull(seal.open(sealed))
        assertNull(seal.open(sealed.copyOf()))
        val sameNonce = seal.seal(Seal.LINKS, unhex(fixture.getValue("links.nonce")), ascii("example.org/another"))
        assertNull(seal.open(sameNonce))
        assertNotNull(seal.open(sealedOf("file")))
        assertNotNull(Seal(code).open(sealed))
    }

    @Test
    fun whatDidNotOpenDoesNotUseUpItsNonce() {
        val seal = Seal(code)
        val sealed = sealedOf("links")
        val changed = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() }
        assertNull(seal.open(changed))
        assertNotNull(seal.open(sealed))
    }

    @Test
    fun aCodeIsTwentyCharactersOfItsAlphabet() {
        for (not in listOf("", "k4mzq7wdx2nph5tcr3v", "k4mzq7wdx2nph5tcr3vbb", "k4mz q7wd x2np h5tc r3vb", "K4MZQ7WDX2NPH5TCR3VB", "k4mzq7wdx2nph5tcr9vb", "k4mzq7wdx2nph5tcr3v\u00e9")) {
            assertThrows(not, IllegalArgumentException::class.java) { Seal(not) }
        }
    }

    @Test
    fun theTextOfASealedThingIsBase64ForAddressesWithoutPadding() {
        assertEquals("", Seal.text(ByteArray(0)))
        assertEquals("-_8", Seal.text(byteArrayOf(-5, -1)))
        assertEquals("AA", Seal.text(byteArrayOf(0)))
        assertEquals("AAAA", Seal.text(byteArrayOf(0, 0, 0)))
    }
}
