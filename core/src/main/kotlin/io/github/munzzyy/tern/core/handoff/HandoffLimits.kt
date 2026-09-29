package io.github.munzzyy.tern.core.handoff

/** Everything a handoff is bounded by. The defaults are the limits the app runs with; tests shorten the times. */
data class HandoffLimits(
    val lifeMs: Long = 10 * 60_000L,
    /** Request line and headers together, with the empty line that ends them. */
    val headBytes: Int = 8 * 1024,
    val headMs: Long = 10_000,
    val bodyMs: Long = 30_000,
    /** How long a connection that has its answer is kept for the other side to read it. */
    val lingerMs: Long = 2_000,
    val connections: Int = 4,
    /** Connections taken in the life of one handoff, whether a request came over them or not. */
    val requests: Int = 200,
    val links: Int = 20,
    val linkLength: Int = 2000,
    /** The links of one request as text, which is more bytes than characters where a link is not ASCII. */
    val linksBytes: Int = 128 * 1024,
    val fileBytes: Int = 2 * 1024 * 1024,
    val waiting: Int = 40,
    /** All files that wait, taken together. */
    val waitingBytes: Int = 8 * 1024 * 1024,
) {
    init {
        require(lifeMs > 0 && headMs > 0 && bodyMs > 0 && lingerMs >= 0) { "Times have to be positive" }
        require(headBytes >= 256 && linksBytes in 1..MAX_FILE && fileBytes in 1..MAX_FILE && waitingBytes >= fileBytes) { "Sizes are out of range" }
        require(connections > 0 && requests > 0 && links > 0 && linkLength > 0 && waiting > 0) { "Counts have to be positive" }
    }

    /** The longest body of a request that sends: the field's name, and the largest thing sealed and written as text. */
    internal val sendBytes: Int get() = Forms.FIELD.length + 1 + Seal.textLength(maxOf(linksBytes, 1 + Forms.MAX_NAME + fileBytes))

    private companion object {
        const val MAX_FILE = 64 * 1024 * 1024
    }
}

/** Something a phone sent. It is a suggestion: nothing is done with it until a person has looked at it. */
sealed interface HandoffItem {
    data class Link(val text: String) : HandoffItem

    class ExportFile(val name: String, val bytes: ByteArray) : HandoffItem
}
