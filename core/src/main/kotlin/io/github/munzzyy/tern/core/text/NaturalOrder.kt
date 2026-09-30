package io.github.munzzyy.tern.core.text

/**
 * Text in the order a person expects: runs of digits compare as numbers, so build-9 comes before
 * build-10, and other text compares without regard to case. Where digits meet other text at the
 * same place the digits sort after it, and a text that runs out first sorts first.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigits = isDigit(a[i])
            val bDigits = isDigit(b[j])
            val aEnd = runEnd(a, i, aDigits)
            val bEnd = runEnd(b, j, bDigits)
            val order = when {
                aDigits && bDigits -> compareNumbers(a.substring(i, aEnd), b.substring(j, bEnd))
                aDigits -> 1
                bDigits -> -1
                else -> a.substring(i, aEnd).compareTo(b.substring(j, bEnd), ignoreCase = true)
            }
            if (order != 0) return order
            i = aEnd
            j = bEnd
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun runEnd(s: String, from: Int, digits: Boolean): Int {
        var i = from
        while (i < s.length && isDigit(s[i]) == digits) i++
        return i
    }

    /** By value, however long: leading zeros set aside, a longer number is a larger one. */
    private fun compareNumbers(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        if (x.length != y.length) return x.length.compareTo(y.length)
        return x.compareTo(y)
    }
}
