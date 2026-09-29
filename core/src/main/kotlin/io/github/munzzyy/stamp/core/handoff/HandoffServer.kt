package io.github.munzzyy.stamp.core.handoff

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A page on the local network that takes links and one export file from a phone and keeps them
 * until [take] is called. It adds nothing anywhere by itself.
 *
 * It listens on one address, answers five requests and nothing else, serves [HandoffLimits.connections]
 * connections at a time and lets the others wait, and closes for good after [HandoffLimits.lifeMs],
 * after [HandoffLimits.wrongPins] wrong PINs, after [HandoffLimits.requests] connections, or on [close].
 */
class HandoffServer private constructor(
    private val listener: ServerSocket,
    private val limits: HandoffLimits,
    random: SecureRandom,
    nowMs: () -> Long,
    private val onChange: () -> Unit,
) : Closeable {
    private val host = "${listener.inetAddress.hostAddress}:${listener.localPort}"
    private val secret = Secrets.secret(random)
    private val pagePath = "/$secret"
    private val linksPath = "/$secret/links"
    private val filePath = "/$secret/file"

    /** The page that asks for [pin]. */
    val address: String = "http://$host"

    /** The page itself, for the QR code. It is never shown as text. */
    val secretAddress: String = address + pagePath

    val pin: String = Secrets.pin(random)

    val closesAtMs: Long = nowMs() + limits.lifeMs

    private val endsAt = System.nanoTime() + limits.lifeMs * NANOS_PER_MS
    private val tickMs = (limits.lifeMs / 4).coerceIn(5, 200)
    private val slots = Semaphore(limits.connections)
    private val lock = Any()
    private val accepting = Any()
    private var open = true
    private var taken = 0
    private var wrongPins = 0
    private val serving = HashSet<Connection>()
    private val queue = ArrayList<HandoffItem>()
    private var queuedBytes = 0L
    private val acceptor = Thread(::run, "stamp-handoff").apply { isDaemon = true }

    val isOpen: Boolean get() = synchronized(lock) { open }

    /** How many things have arrived and wait to be taken. */
    fun waiting(): Int = synchronized(lock) { queue.size }

    /** What has arrived, oldest first. It can still be taken after the handoff has closed. */
    fun take(): List<HandoffItem> = synchronized(lock) {
        val all = queue.toList()
        queue.clear()
        queuedBytes = 0
        all
    }

    /** Frees the port at once and ends every request that is open. */
    override fun close() = shut(null)

    private class Connection(val socket: Socket, val due: Long)

    private class Outcome(val answer: Answer, val closes: Boolean = false)

    private enum class Route { PIN_PAGE, PIN, PAGE, LINKS, FILE, NOT_FOUND }

    private enum class Guess { RIGHT, WRONG, LAST_WRONG, TOO_LATE }

    private fun run() {
        try {
            while (true) {
                val now = System.nanoTime()
                sweep(now)
                val (listening, busy) = synchronized(lock) { open to serving.isNotEmpty() }
                when {
                    listening && now - endsAt >= 0 -> shut(null)
                    listening -> acceptOne()
                    busy -> Thread.sleep(tickMs)
                    else -> return
                }
            }
        } catch (_: Exception) {
            shut(null)
        }
    }

    private fun acceptOne() {
        if (!slots.tryAcquire(tickMs, TimeUnit.MILLISECONDS)) return
        val socket = try {
            synchronized(accepting) { listener.accept() }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            shut(null)
            null
        }
        if (socket == null) {
            slots.release()
            return
        }
        val connection = Connection(socket, System.nanoTime() + (limits.headMs + limits.bodyMs + limits.lingerMs + SLACK_MS) * NANOS_PER_MS)
        val over = synchronized(lock) {
            if (!open) return@synchronized null
            serving += connection
            ++taken > limits.requests
        }
        if (over == null) {
            closeQuietly(socket)
            slots.release()
            return
        }
        if (over) shut(connection)
        try {
            Thread({ serve(connection, over) }, "stamp-handoff-request").apply { isDaemon = true }.start()
        } catch (_: OutOfMemoryError) {
            finish(connection)
        }
    }

    /** Ends what has run past every time limit it could have, such as an answer that the other side does not read. */
    private fun sweep(now: Long) {
        val late = synchronized(lock) { serving.filter { now - it.due >= 0 } }
        late.forEach { closeQuietly(it.socket) }
    }

    private fun serve(connection: Connection, over: Boolean) {
        try {
            val socket = connection.socket
            socket.tcpNoDelay = true
            val wire = Wire(socket)
            val outcome = if (over) {
                Outcome(Pages.usedUp())
            } else {
                try {
                    answerTo(wire)
                } catch (refused: Refused) {
                    Outcome(refused.answer)
                }
            }
            if (outcome.closes) shut(connection)
            socket.getOutputStream().apply {
                write(outcome.answer.bytes())
                flush()
            }
            socket.shutdownOutput()
            wire.drain(DRAIN_FILES * limits.fileBodyBytes, limits.lingerMs)
        } catch (_: Exception) {
            // Nobody is left to tell, and an exception that leaves a thread ends the whole app on Android.
        } finally {
            finish(connection)
        }
    }

    private fun finish(connection: Connection) {
        closeQuietly(connection.socket)
        synchronized(lock) { serving -= connection }
        slots.release()
    }

    private fun answerTo(wire: Wire): Outcome {
        val head = wire.head(limits.headBytes, limits.headMs)
        val length = lengthOf(head, wire)
        return when (route(head)) {
            Route.NOT_FOUND -> Outcome(Pages.notFound())
            Route.PIN_PAGE -> Outcome(Pages.pinPage(null, limits))
            Route.PAGE -> page(null)
            Route.PIN -> pin(wire, head, length)
            Route.LINKS -> links(wire, head, length)
            Route.FILE -> file(wire, head, length)
        }
    }

    private fun lengthOf(head: Head, wire: Wire): Int {
        val announced = head.header("content-length")
        if (announced != null && (announced.isEmpty() || announced.any { it !in '0'..'9' })) throw Refused(Pages.badRequest())
        val length = when {
            announced == null -> null
            announced.length > MAX_LENGTH_DIGITS -> Int.MAX_VALUE
            else -> announced.toInt()
        }
        val chunked = head.header("transfer-encoding") != null
        if (head.method == "POST") {
            if (chunked || length == null) throw Refused(Pages.lengthRequired())
            return length
        }
        if (chunked || (length ?: 0) != 0 || wire.hasMore()) throw Refused(Pages.badRequest())
        return 0
    }

    private fun route(head: Head): Route {
        val target = head.target
        val page = Secrets.same(pagePath, target)
        val links = Secrets.same(linksPath, target)
        val file = Secrets.same(filePath, target)
        val get = head.method == "GET"
        val post = head.method == "POST"
        return when {
            head.header("host") != host -> Route.NOT_FOUND
            get && target == "/" -> Route.PIN_PAGE
            post && target == "/pin" -> Route.PIN
            get && page -> Route.PAGE
            post && links -> Route.LINKS
            post && file -> Route.FILE
            else -> Route.NOT_FOUND
        }
    }

    private fun page(notice: Notice?) = Outcome(Pages.page(secret, notice, limits))

    private fun pin(wire: Wire, head: Head, length: Int): Outcome {
        if (Forms.typeOf(head.header("content-type")) != Forms.TYPE || length > MAX_PIN_BODY) throw Refused(Pages.badRequest())
        val given = Forms.field(wire.body(length, limits.bodyMs), Pages.PIN_FIELD) ?: throw Refused(Pages.badRequest())
        val guess = synchronized(lock) {
            when {
                !open || wrongPins >= limits.wrongPins -> Guess.TOO_LATE
                Secrets.same(pin, given.trim()) -> Guess.RIGHT
                ++wrongPins >= limits.wrongPins -> Guess.LAST_WRONG
                else -> Guess.WRONG
            }
        }
        return when (guess) {
            Guess.RIGHT -> Outcome(Pages.pinRight(secret))
            Guess.WRONG -> Outcome(Pages.pinPage(Notice.WRONG_PIN, limits))
            Guess.LAST_WRONG -> Outcome(Pages.pinClosed(), closes = true)
            Guess.TOO_LATE -> Outcome(Pages.closed())
        }
    }

    private fun links(wire: Wire, head: Head, length: Int): Outcome {
        if (Forms.typeOf(head.header("content-type")) != Forms.TYPE) return page(Notice.LINKS_UNREADABLE)
        if (length > limits.formBytes) return page(Notice.LINKS_TOO_LARGE)
        val text = Forms.field(wire.body(length, limits.bodyMs), Pages.LINKS_FIELD) ?: return page(Notice.LINKS_UNREADABLE)
        return when (val read = Forms.links(text, limits)) {
            is Forms.Links.Refused -> page(read.notice)
            is Forms.Links.Taken -> page(offer(read.links.map { HandoffItem.Link(it) }, 0, Notice.LINKS_SENT))
        }
    }

    private fun file(wire: Wire, head: Head, length: Int): Outcome {
        val boundary = Multipart.boundary(head.header("content-type")) ?: return page(Notice.FILE_UNREADABLE)
        if (length > limits.fileBodyBytes) return page(Notice.FILE_TOO_LARGE)
        val part = Multipart.single(wire.body(length, limits.bodyMs), boundary, Pages.FILE_FIELD) ?: return page(Notice.FILE_UNREADABLE)
        return when {
            part.content.isEmpty() -> page(Notice.NO_FILE)
            part.content.size > limits.fileBytes -> page(Notice.FILE_TOO_LARGE)
            else -> page(offer(listOf(HandoffItem.ExportFile(part.fileName, part.content)), part.content.size, Notice.FILE_SENT))
        }
    }

    private fun offer(items: List<HandoffItem>, bytes: Int, sent: Notice): Notice {
        synchronized(lock) {
            if (!open) throw Refused(Pages.closed())
            if (queue.size + items.size > limits.waiting || queuedBytes + bytes > limits.waitingBytes) return Notice.TOO_MUCH_WAITS
            queue += items
            queuedBytes += bytes
        }
        tell()
        return sent
    }

    /** Closes for good. The connection in [keep] stays, so that it can still be given the answer that says so. */
    private fun shut(keep: Connection?) {
        val others = synchronized(lock) {
            if (!open) return
            open = false
            serving.filter { it !== keep }
        }
        closeQuietly(listener)
        // A thread that waits in accept keeps the port open until it has left, so this waits for it to leave.
        if (Thread.currentThread() !== acceptor) synchronized(accepting) {}
        others.forEach { closeQuietly(it.socket) }
        tell()
    }

    private fun tell() {
        try {
            onChange()
        } catch (_: RuntimeException) {
            // A listener that fails must not take a request or the handoff with it.
        }
    }

    private fun closeQuietly(what: Closeable?) {
        try {
            what?.close()
        } catch (_: IOException) {
            // Closing is all that was wanted.
        }
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val SLACK_MS = 5_000L
        private const val BACKLOG = 8
        private const val MAX_PIN_BODY = 64
        private const val MAX_LENGTH_DIGITS = 9
        private const val DRAIN_FILES = 8L

        /**
         * Opens a handoff on [bind], which has to be one IPv4 address of this device and never all of
         * them, on a port the system picks. [onChange] is called when something has arrived and when
         * the handoff has closed, on the thread where that happened. Throws IOException when the
         * address cannot be listened on.
         */
        fun open(
            bind: InetAddress,
            limits: HandoffLimits = HandoffLimits(),
            random: SecureRandom = SecureRandom(),
            nowMs: () -> Long = System::currentTimeMillis,
            onChange: () -> Unit = {},
        ): HandoffServer {
            require(bind is Inet4Address && !bind.isAnyLocalAddress && !bind.isMulticastAddress) { "A handoff listens on one IPv4 address" }
            val listener = ServerSocket()
            try {
                listener.bind(InetSocketAddress(bind, 0), BACKLOG)
                listener.soTimeout = (limits.lifeMs / 4).coerceIn(5, 200).toInt()
            } catch (e: IOException) {
                listener.close()
                throw e
            }
            return HandoffServer(listener, limits, random, nowMs, onChange).also { it.acceptor.start() }
        }
    }
}
