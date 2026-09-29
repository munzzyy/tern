package io.github.munzzyy.tern.core.handoff

import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Seals and opens what a phone sends, with keys made from the code that both screens show and
 * the network never carries. The content is laid under ChaCha20, and HMAC-SHA256 over the kind,
 * the nonce and the sealed content says that it was made with the code and not changed since.
 *
 * Sealed, a thing is: one byte of kind, 12 bytes of nonce, the content, 32 bytes of tag.
 */
class Seal(code: String) {
    private val keys = keysOf(code)
    private val opened = HashSet<ByteBuffer>()

    class Opened(val kind: Int, val plain: ByteArray)

    internal class Keys(val enc: ByteArray, val mac: ByteArray)

    /** What the page does in the browser. On this side only tests seal. */
    fun seal(kind: Int, nonce: ByteArray, plain: ByteArray): ByteArray {
        require(kind in 0..255 && nonce.size == NONCE) { "A kind is one byte and a nonce is 12" }
        val signed = byteArrayOf(kind.toByte()) + nonce + ChaCha20.xor(keys.enc, nonce, FIRST_BLOCK, plain)
        return signed + hmac(keys.mac, signed)
    }

    /**
     * Null for what was not sealed with this code, for what was changed on the way, and for what
     * has been opened before. Nothing is decrypted until the tag has been found right.
     */
    fun open(sealed: ByteArray): Opened? {
        if (sealed.size < HEAD + TAG) return null
        val end = sealed.size - TAG
        val right = Secrets.same(hmac(keys.mac, sealed, end), sealed.copyOfRange(end, sealed.size))
        if (!right) return null
        val nonce = sealed.copyOfRange(1, HEAD)
        val first = synchronized(opened) { opened.add(ByteBuffer.wrap(nonce)) }
        if (!first) return null
        return Opened(sealed[0].toInt() and 0xFF, ChaCha20.xor(keys.enc, nonce, FIRST_BLOCK, sealed.copyOfRange(HEAD, end)))
    }

    companion object {
        const val LINKS = 1
        const val FILE = 2
        const val NONCE = 12
        internal const val TAG = 32
        internal const val HEAD = 1 + NONCE
        private const val FIRST_BLOCK = 1
        private const val HMAC = "HmacSHA256"
        private const val LABEL = "tern handoff v1"

        /** How a sealed thing is written in the form that carries it: base64 for addresses, without padding. */
        fun text(sealed: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)

        /** How long [text] is for a thing of [plain] bytes. */
        internal fun textLength(plain: Int): Int = ((HEAD + plain + TAG) * 4 + 2) / 3

        internal fun keysOf(code: String): Keys {
            require(code.length == Secrets.CODE_LENGTH && code.all { it in Secrets.ALPHABET }) { "A code is 20 characters of base32" }
            val master = hmac(code.toByteArray(Charsets.US_ASCII), LABEL.toByteArray(Charsets.US_ASCII))
            return Keys(hmac(master, "enc".toByteArray(Charsets.US_ASCII)), hmac(master, "mac".toByteArray(Charsets.US_ASCII)))
        }

        internal fun hmac(key: ByteArray, message: ByteArray, length: Int = message.size): ByteArray {
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(key, HMAC))
            mac.update(message, 0, length)
            return mac.doFinal()
        }
    }
}
