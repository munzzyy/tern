package io.github.munzzyy.tern.core.qr

import kotlin.math.abs

/**
 * The four penalty rules of the standard, in libqrencode's reading of them: a finder-like pattern
 * counts once however many light sides it has, in any whole multiple of its width, and one that
 * starts within three runs of the edge counts without a light side.
 */
internal object Penalty {
    private const val RUN = 3
    private const val BLOCK = 3
    private const val FINDER = 40
    private const val BALANCE = 10

    fun of(size: Int, dark: BooleanArray): Int {
        var total = balance(size, dark) + blocks(size, dark)
        val runs = IntArray(size + 1)
        for (y in 0 until size) total += runsAndFinders(runs, lengths(size, runs) { dark[y * size + it] })
        for (x in 0 until size) total += runsAndFinders(runs, lengths(size, runs) { dark[it * size + x] })
        return total
    }

    private fun balance(size: Int, dark: BooleanArray): Int {
        val squares = size * size
        val percent = (200 * dark.count { it } + squares) / squares / 2
        return abs(percent - 50) / 5 * BALANCE
    }

    private fun blocks(size: Int, dark: BooleanArray): Int {
        var total = 0
        for (y in 1 until size) for (x in 1 until size) {
            val here = dark[y * size + x]
            if (here == dark[y * size + x - 1] && here == dark[(y - 1) * size + x] && here == dark[(y - 1) * size + x - 1]) total += BLOCK
        }
        return total
    }

    /** Fills [runs] so that dark runs sit at odd places, and returns how many places are filled. */
    private inline fun lengths(size: Int, runs: IntArray, at: (Int) -> Boolean): Int {
        var head = 0
        if (at(0)) {
            runs[0] = -1
            head = 1
        }
        runs[head] = 1
        var previous = at(0)
        for (i in 1 until size) {
            val now = at(i)
            if (now != previous) {
                head++
                runs[head] = 1
                previous = now
            } else {
                runs[head]++
            }
        }
        return head + 1
    }

    private fun runsAndFinders(runs: IntArray, count: Int): Int {
        var total = 0
        for (i in 0 until count) {
            if (runs[i] >= 5) total += RUN + runs[i] - 5
            if (i and 1 == 0 || i < 3 || i >= count - 2 || runs[i] % 3 != 0) continue
            val unit = runs[i] / 3
            if (runs[i - 2] != unit || runs[i - 1] != unit || runs[i + 1] != unit || runs[i + 2] != unit) continue
            if (i == 3 || runs[i - 3] >= 4 * unit || i + 4 >= count || runs[i + 3] >= 4 * unit) total += FINDER
        }
        return total
    }
}
