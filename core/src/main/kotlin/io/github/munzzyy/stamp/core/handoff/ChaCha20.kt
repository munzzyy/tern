package io.github.munzzyy.stamp.core.handoff

/**
 * ChaCha20 as RFC 8439 describes it, with a nonce of 96 bits and a block counter of 32.
 * It is written out here because Android has no way to start javax.crypto's ChaCha20 at a
 * block of one's choosing before API 35.
 */
internal object ChaCha20 {
    const val KEY = 32
    const val NONCE = 12
    private const val BLOCK = 64

    /** [data] with the key stream laid over it, which seals what is plain and opens what is sealed. */
    fun xor(key: ByteArray, nonce: ByteArray, firstBlock: Int, data: ByteArray): ByteArray {
        require(key.size == KEY && nonce.size == NONCE) { "ChaCha20 takes a key of 32 bytes and a nonce of 12" }
        require(firstBlock >= 0 && firstBlock <= Int.MAX_VALUE - data.size / BLOCK - 1) { "The block counter would run over" }
        val start = IntArray(16)
        start[0] = 0x61707865
        start[1] = 0x3320646e
        start[2] = 0x79622d32
        start[3] = 0x6b206574
        for (i in 0 until 8) start[4 + i] = word(key, 4 * i)
        start[12] = firstBlock
        for (i in 0 until 3) start[13 + i] = word(nonce, 4 * i)

        val out = ByteArray(data.size)
        val s = IntArray(16)
        var at = 0
        while (at < data.size) {
            start.copyInto(s)
            repeat(10) {
                quarter(s, 0, 4, 8, 12)
                quarter(s, 1, 5, 9, 13)
                quarter(s, 2, 6, 10, 14)
                quarter(s, 3, 7, 11, 15)
                quarter(s, 0, 5, 10, 15)
                quarter(s, 1, 6, 11, 12)
                quarter(s, 2, 7, 8, 13)
                quarter(s, 3, 4, 9, 14)
            }
            for (i in 0 until 16) s[i] += start[i]
            val n = minOf(BLOCK, data.size - at)
            for (i in 0 until n) out[at + i] = (data[at + i].toInt() xor (s[i / 4] ushr (8 * (i % 4)))).toByte()
            at += BLOCK
            start[12]++
        }
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]
        s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]
        s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]
        s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]
        s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun word(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
