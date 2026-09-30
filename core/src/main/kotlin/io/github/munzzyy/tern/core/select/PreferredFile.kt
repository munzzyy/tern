package io.github.munzzyy.tern.core.select

/**
 * Finds again, in a later release, the file a person picked for an app. Obtainium keeps the place
 * of the file in the list, which points elsewhere once the list changes. Here a file is known by
 * the shape of its name instead: its words and the processors it names, with each stretch of
 * numbers standing for any number. So app-arm64-v8a-2.1.apk picked today is app-arm64-v8a-2.2.1.apk
 * tomorrow, and never app-x86_64-2.2.1.apk.
 */
object PreferredFile {
    private const val MAX_NAME = 512
    private val NUMBERS = Regex("[0-9]+")

    /**
     * The one of [among] that is the file picked as [picked], in the order given; null when none
     * has the shape of that name. Of several that have it, the one whose numbers agree most often
     * with the picked name's wins, and then the first.
     */
    fun <T> find(picked: String, among: List<T>, name: (T) -> String): T? {
        val shape = shape(picked)
        val numbers = numbers(picked)
        var best: T? = null
        var bestAgreement = -1
        for (candidate in among) {
            val candidateName = name(candidate)
            if (shape(candidateName) != shape) continue
            val theirs = numbers(candidateName)
            val agreement = numbers.indices.count { it < theirs.size && theirs[it] == numbers[it] }
            if (agreement > bestAgreement) {
                best = candidate
                bestAgreement = agreement
            }
        }
        return best
    }

    /** The processors a name names, and its words in order with every stretch of numbers made one mark. */
    internal fun shape(name: String): Pair<Set<String>, List<String>> {
        val trimmed = name.take(MAX_NAME)
        val abis = AssetPicker.abisIn(trimmed).toSet()
        val words = ArrayList<String>()
        for (token in AssetPicker.tokens(trimmed)) {
            if (AssetPicker.isAbiToken(token)) continue
            for (piece in PIECES.findAll(token)) {
                val word = if (piece.value[0].isDigit()) NUMBER else piece.value
                // 1.2 and 1.2.1 are both a version: numbers one after the other count once.
                if (word == NUMBER && words.lastOrNull() == NUMBER) continue
                words += word
            }
        }
        return abis to words
    }

    private fun numbers(name: String): List<String> =
        AssetPicker.tokens(name.take(MAX_NAME)).filterNot(AssetPicker::isAbiToken).flatMap { token -> NUMBERS.findAll(token).map { it.value.trimStart('0').ifEmpty { "0" } }.toList() }

    private const val NUMBER = "#"
    private val PIECES = Regex("[a-z]+|[0-9]+")
}
