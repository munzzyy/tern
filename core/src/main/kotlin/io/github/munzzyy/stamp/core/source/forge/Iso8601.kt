package io.github.munzzyy.stamp.core.source.forge

/** Strict parser for the timestamps forges publish: UTC or with a numeric offset. */
internal object Iso8601 {
    private val PATTERN = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})""")

    fun parseMs(text: String): Long? {
        val m = PATTERN.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
        val offsetMs = offsetMs(g[7]) ?: return null
        val days = daysFromEpoch(year, month, day) ?: return null
        return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L - offsetMs
    }

    private fun offsetMs(zone: String): Long? {
        if (zone == "Z") return 0L
        val hours = zone.substring(1, 3).toInt()
        val minutes = zone.substring(4, 6).toInt()
        if (hours > 18 || minutes > 59) return null
        val magnitude = hours * 3_600_000L + minutes * 60_000L
        return if (zone[0] == '-') -magnitude else magnitude
    }

    private fun daysFromEpoch(year: Int, month: Int, day: Int): Long? {
        if (year !in 1..9999) return null
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        val julianDay = day + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045
        return julianDay - EPOCH_JULIAN_DAY
    }

    private const val EPOCH_JULIAN_DAY = 2_440_588L
}
