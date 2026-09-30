package io.github.munzzyy.tern.core.compress

/**
 * The data of one xz block: LZMA2 chunks up to the one that ends it. A chunk is stored as it is, or
 * packed with LZMA; either way it goes through the same dictionary.
 */
internal class Lzma2(private val input: Counted, dictionary: Int, firstWindow: Int = XzInputStream.FIRST_WINDOW) {
    private val window = Window(maxOf(MIN_DICTIONARY, (dictionary + 15) and 15.inv()), firstWindow)
    private val range = RangeDecoder()
    private var lzma: Lzma? = null
    private var left = 0
    private var packed = false
    private var needReset = true
    private var needProperties = true
    private var ended = false

    /** Up to [len] bytes, or -1 once the chunk that ends the data has been read. */
    fun read(b: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len && !ended) {
            if (left == 0) {
                chunk()
                if (ended) break
            }
            val most = minOf(left, len - n)
            if (packed) {
                window.limit(most)
                lzma!!.decode()
            } else {
                window.copy(input, most)
            }
            val got = window.flush(b, off + n)
            n += got
            left -= got
            if (left == 0 && packed && (!range.finished() || window.pending())) throw damaged()
        }
        return if (n == 0 && ended) -1 else n
    }

    private fun chunk() {
        val control = input.byte()
        if (control == 0x00) {
            ended = true
            return
        }
        if (control >= 0xe0 || control == 0x01) {
            needProperties = true
            needReset = false
            window.reset()
        } else if (needReset) {
            throw damaged()
        }
        if (control >= 0x80) {
            packed = true
            left = ((control and 0x1f) shl 16) + short() + 1
            val size = short() + 1
            when {
                control >= 0xc0 -> {
                    needProperties = false
                    lzma = properties(input.byte())
                }
                needProperties -> throw damaged()
                control >= 0xa0 -> lzma!!.reset()
            }
            range.prepare(input, size)
        } else if (control > 0x02) {
            throw damaged()
        } else {
            packed = false
            left = short() + 1
        }
    }

    private fun properties(byte: Int): Lzma {
        if (byte > (4 * 5 + 4) * 9 + 8) throw damaged()
        val pb = byte / (9 * 5)
        val lp = (byte / 9) % 5
        val lc = byte % 9
        if (lc + lp > 4) throw damaged()
        return Lzma(window, range, lc, lp, pb)
    }

    private fun short(): Int = (input.byte() shl 8) or input.byte()

    private fun damaged() = CompressedDataException("The xz data is damaged")

    private companion object {
        const val MIN_DICTIONARY = 4096
    }
}

/**
 * The last [size] bytes given out, which matches copy from. What was decoded but not yet handed on
 * lies between start and pos; a match cut short by the end of what was asked for is finished first
 * the next time. The buffer starts at [first] bytes and grows as the data fills it, and it wraps
 * only once it is [size] long, so no distance within [size] is ever lost to a smaller one.
 * [allocate] makes each buffer; running out of memory for one refuses the data.
 */
internal class Window(private val size: Int, first: Int = size, private val allocate: (Int) -> ByteArray = ::ByteArray) {
    private var buf = make(minOf(size, maxOf(MIN_FIRST, (first + 15) and 15.inv())))
    private var start = 0
    var pos = 0
        private set
    private var full = 0
    private var limit = 0
    private var pendingLength = 0
    private var pendingDistance = 0

    /** How much memory the dictionary holds now. */
    val capacity: Int get() = buf.size

    fun reset() {
        start = 0
        pos = 0
        full = 0
        limit = 0
        pendingLength = 0
        buf[buf.size - 1] = 0
    }

    fun limit(most: Int) {
        room(most)
        limit = if (buf.size - pos <= most) buf.size else pos + most
    }

    fun space(): Boolean = pos < limit

    fun pending(): Boolean = pendingLength > 0

    /** The byte [distance] + 1 back. */
    fun byte(distance: Int): Int {
        var at = pos - distance - 1
        if (distance >= pos) at += buf.size
        return buf[at].toInt() and 0xff
    }

    fun put(b: Int) {
        buf[pos++] = b.toByte()
        if (full < pos) full = pos
    }

    fun repeat(distance: Int, length: Int) {
        if (distance < 0 || distance >= full) throw CompressedDataException("The xz data is damaged")
        var left = minOf(limit - pos, length)
        pendingLength = length - left
        pendingDistance = distance
        var back = pos - distance - 1
        if (distance >= pos) back += buf.size
        while (left > 0) {
            buf[pos++] = buf[back++]
            if (back == buf.size) back = 0
            left--
        }
        if (full < pos) full = pos
    }

    fun repeatPending() {
        if (pendingLength > 0) repeat(pendingDistance, pendingLength)
    }

    fun copy(input: Counted, length: Int) {
        room(length)
        val size = minOf(buf.size - pos, length)
        input.fully(buf, pos, size)
        pos += size
        if (full < pos) full = pos
    }

    fun flush(out: ByteArray, off: Int): Int {
        val size = pos - start
        System.arraycopy(buf, start, out, off, size)
        if (pos == buf.size) {
            if (buf.size < this.size) grow(buf.size + 1) else pos = 0
        }
        start = pos
        return size
    }

    /** Grows the buffer, while it has not wrapped yet, so that [length] more bytes fit after pos. */
    private fun room(length: Int) {
        if (buf.size < size && full == pos && buf.size - pos < length) grow(pos + length)
    }

    private fun grow(need: Int) {
        val next = minOf(size.toLong(), (maxOf(need.toLong(), buf.size * 2L) + 15) and 15L.inv()).toInt()
        buf = make(next).also { System.arraycopy(buf, 0, it, 0, pos) }
    }

    private fun make(length: Int): ByteArray = try {
        allocate(length)
    } catch (_: OutOfMemoryError) {
        throw CompressedDataException("This xz data needs a dictionary of ${size / (1024 * 1024)} MiB, more memory than this device gives Tern")
    }

    private companion object {
        const val MIN_FIRST = 4096
    }
}

/** The range decoder over one chunk, which is read whole first; it may not read past the chunk. */
internal class RangeDecoder {
    private val buf = ByteArray(1 shl 16)
    private var pos = 0
    private var end = 0
    private var range = 0
    private var code = 0

    fun prepare(input: Counted, size: Int) {
        if (size < 5) throw damaged()
        input.fully(buf, 0, size)
        if (buf[0].toInt() != 0) throw damaged()
        code = ((buf[1].toInt() and 0xff) shl 24) or ((buf[2].toInt() and 0xff) shl 16) or ((buf[3].toInt() and 0xff) shl 8) or (buf[4].toInt() and 0xff)
        range = -1
        pos = 5
        end = size
    }

    fun finished(): Boolean = pos == end && code == 0

    fun normalize() {
        if (range and TOP_MASK == 0) {
            if (pos >= end) throw damaged()
            code = (code shl 8) or (buf[pos++].toInt() and 0xff)
            range = range shl 8
        }
    }

    fun bit(probs: IntArray, index: Int): Int {
        normalize()
        val prob = probs[index]
        val bound = (range ushr BIT_MODEL_BITS) * prob
        // Compared as the unsigned numbers they are.
        return if ((code xor Int.MIN_VALUE) < (bound xor Int.MIN_VALUE)) {
            range = bound
            probs[index] = prob + ((BIT_MODEL_TOTAL - prob) ushr MOVE_BITS)
            0
        } else {
            range -= bound
            code -= bound
            probs[index] = prob - (prob ushr MOVE_BITS)
            1
        }
    }

    fun tree(probs: IntArray): Int {
        var symbol = 1
        do {
            symbol = (symbol shl 1) or bit(probs, symbol)
        } while (symbol < probs.size)
        return symbol - probs.size
    }

    fun reverseTree(probs: IntArray): Int {
        var symbol = 1
        var shift = 0
        var result = 0
        do {
            val b = bit(probs, symbol)
            symbol = (symbol shl 1) or b
            result = result or (b shl shift++)
        } while (symbol < probs.size)
        return result
    }

    fun direct(count: Int): Int {
        var result = 0
        repeat(count) {
            normalize()
            range = range ushr 1
            val t = (code - range) ushr 31
            code -= range and (t - 1)
            result = (result shl 1) or (1 - t)
        }
        return result
    }

    private fun damaged() = CompressedDataException("The xz data is damaged")

    companion object {
        const val BIT_MODEL_BITS = 11
        const val BIT_MODEL_TOTAL = 1 shl BIT_MODEL_BITS
        const val MOVE_BITS = 5
        private const val TOP_MASK = -0x1000000
    }
}

/** LZMA as LZMA2 uses it: its state goes on from chunk to chunk until a chunk resets it. */
internal class Lzma(private val window: Window, private val rc: RangeDecoder, private val lc: Int, lp: Int, pb: Int) {
    private val posMask = (1 shl pb) - 1
    private val literalPosMask = (1 shl lp) - 1
    private var state = 0
    private val reps = IntArray(4)
    private val isMatch = IntArray(STATES shl POS_STATES_BITS)
    private val isRep = IntArray(STATES)
    private val isRep0 = IntArray(STATES)
    private val isRep1 = IntArray(STATES)
    private val isRep2 = IntArray(STATES)
    private val isRep0Long = IntArray(STATES shl POS_STATES_BITS)
    private val distanceSlots = Array(DISTANCE_STATES) { IntArray(64) }
    private val distanceSpecial = Array(10) { IntArray(2 shl (it / 2)) }
    private val distanceAlign = IntArray(16)
    private val literals = Array(1 shl (lc + lp)) { IntArray(0x300) }
    private val matchLength = Length()
    private val repLength = Length()

    init {
        reset()
    }

    fun reset() {
        state = 0
        reps.fill(0)
        val all = listOf(isMatch, isRep, isRep0, isRep1, isRep2, isRep0Long, distanceAlign) + distanceSlots + distanceSpecial + literals
        for (probs in all) probs.fill(RangeDecoder.BIT_MODEL_TOTAL / 2)
        matchLength.reset()
        repLength.reset()
    }

    fun decode() {
        window.repeatPending()
        while (window.space()) {
            val posState = window.pos and posMask
            if (rc.bit(isMatch, (state shl POS_STATES_BITS) + posState) == 0) {
                literal()
            } else {
                val length = if (rc.bit(isRep, state) == 0) match(posState) else repMatch(posState)
                window.repeat(reps[0], length)
            }
        }
        rc.normalize()
    }

    private fun literal() {
        val probs = literals[(window.byte(0) ushr (8 - lc)) + ((window.pos and literalPosMask) shl lc)]
        var symbol = 1
        if (state < LITERAL_STATES) {
            do {
                symbol = (symbol shl 1) or rc.bit(probs, symbol)
            } while (symbol < 0x100)
        } else {
            var matchByte = window.byte(reps[0])
            var offset = 0x100
            do {
                matchByte = matchByte shl 1
                val matchBit = matchByte and offset
                val b = rc.bit(probs, offset + matchBit + symbol)
                symbol = (symbol shl 1) or b
                offset = offset and ((0 - b) xor matchBit.inv())
            } while (symbol < 0x100)
        }
        window.put(symbol and 0xff)
        state = when {
            state <= 3 -> 0
            state <= 9 -> state - 3
            else -> state - 6
        }
    }

    private fun match(posState: Int): Int {
        state = if (state < LITERAL_STATES) 7 else 10
        reps[3] = reps[2]
        reps[2] = reps[1]
        reps[1] = reps[0]
        val length = matchLength.decode(posState)
        val slot = rc.tree(distanceSlots[if (length < DISTANCE_STATES + MIN_MATCH) length - MIN_MATCH else DISTANCE_STATES - 1])
        reps[0] = if (slot < 4) {
            slot
        } else {
            val bits = (slot ushr 1) - 1
            var distance = (2 or (slot and 1)) shl bits
            if (slot < 14) {
                distance = distance or rc.reverseTree(distanceSpecial[slot - 4])
            } else {
                distance = distance or (rc.direct(bits - 4) shl 4)
                distance = distance or rc.reverseTree(distanceAlign)
            }
            distance
        }
        return length
    }

    private fun repMatch(posState: Int): Int {
        if (rc.bit(isRep0, state) == 0) {
            if (rc.bit(isRep0Long, (state shl POS_STATES_BITS) + posState) == 0) {
                state = if (state < LITERAL_STATES) 9 else 11
                return 1
            }
        } else {
            val distance: Int
            if (rc.bit(isRep1, state) == 0) {
                distance = reps[1]
            } else {
                if (rc.bit(isRep2, state) == 0) {
                    distance = reps[2]
                } else {
                    distance = reps[3]
                    reps[3] = reps[2]
                }
                reps[2] = reps[1]
            }
            reps[1] = reps[0]
            reps[0] = distance
        }
        state = if (state < LITERAL_STATES) 8 else 11
        return repLength.decode(posState)
    }

    private inner class Length {
        private val choice = IntArray(2)
        private val low = Array(1 shl POS_STATES_BITS) { IntArray(8) }
        private val mid = Array(1 shl POS_STATES_BITS) { IntArray(8) }
        private val high = IntArray(256)

        fun reset() {
            for (probs in listOf(choice, high) + low + mid) probs.fill(RangeDecoder.BIT_MODEL_TOTAL / 2)
        }

        fun decode(posState: Int): Int = when {
            rc.bit(choice, 0) == 0 -> rc.tree(low[posState]) + MIN_MATCH
            rc.bit(choice, 1) == 0 -> rc.tree(mid[posState]) + MIN_MATCH + 8
            else -> rc.tree(high) + MIN_MATCH + 16
        }
    }

    private companion object {
        const val STATES = 12
        const val LITERAL_STATES = 7
        const val POS_STATES_BITS = 4
        const val DISTANCE_STATES = 4
        const val MIN_MATCH = 2
    }
}
