package io.github.munzzyy.stamp.core.qr

/** Check codewords over the field of 256 elements that QR codes use, with the polynomial x^8 + x^4 + x^3 + x^2 + 1. */
internal object ReedSolomon {
    private const val FIELD = 0x11D
    private val EXP = IntArray(510)
    private val LOG = IntArray(256)

    init {
        var value = 1
        for (i in 0 until 255) {
            EXP[i] = value
            EXP[i + 255] = value
            LOG[value] = i
            value = value shl 1
            if (value >= 256) value = value xor FIELD
        }
    }

    private fun multiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** The coefficients below the leading one, highest power first. */
    fun generator(degree: Int): IntArray {
        val out = IntArray(degree)
        out[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                out[j] = multiply(out[j], root)
                if (j + 1 < degree) out[j] = out[j] xor out[j + 1]
            }
            root = multiply(root, 2)
        }
        return out
    }

    fun remainder(data: IntArray, generator: IntArray): IntArray {
        val out = IntArray(generator.size)
        for (value in data) {
            val factor = value xor out[0]
            System.arraycopy(out, 1, out, 0, out.size - 1)
            out[out.size - 1] = 0
            for (i in out.indices) out[i] = out[i] xor multiply(generator[i], factor)
        }
        return out
    }
}
