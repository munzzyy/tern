package io.github.munzzyy.tern.core.handoff

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
 * until [take] is called. It adds nothing anywhere by itself, and it takes nothing that was not
 * sealed with [code].
 *
 * It listens on one address, answers two requests and nothing else, serves [HandoffLimits.connections]
 * connections at a time and lets the others wait, and closes for good after [HandoffLimits.lifeMs],
 * after [HandoffLimits.requests] connections, or on [close].
 */
class HandoffServer private constructor(
    private val listener: ServerSocket,
    private val limits: HandoffLimits,
    random: SecureRandom,
    nowMs: () -> Long,
    private val onChange: () -> Unit,
) : Closeable {
    private val host = "${listener.inetAddress.hostAddress}:${listener.localPort}"
    private val plainCode = Secrets.code(random)
    private val seal = Seal(plainCode)

    val address: String = "http://$host"

    /** What seals everything the phone sends, in groups for typing by hand. It never travels over the network. */
    val code: String = Secrets.grouped(plainCode)

    /** For the QR code: the page with the code behind a number sign, which is the part of an address a browser keeps to itself. */
    val addressWithCode: String = "$address/#$plainCode"

    val closesAtMs: Long = nowMs() + limits.lifeMs

    private val endsAt = System.nanoTime() + limits.lifeMs * NANOS_PER_MS
    private val tickMs = (limits.lifeMs / 4).coerceIn(5, 200)
    private val slots = Semaphore(limits.connections)
    private val lock = Any()
    private val accepting = Any()
    private var ended: End? = null
    private var taken = 0
    private val serving = HashSet<Connection>()
    private val queue = ArrayList<HandoffItem>()
    private var queuedBytes = 0L
    private val acceptor = Thread(::run, "tern-handoff").apply { isDaemon = true }

    /** Why a handoff is no longer open. */
    enum class End { CLOSED, EXPIRED, USED_UP }

    val isOpen: Boolean get() = synchronized(lock) { ended == null }

    /** Null while the handoff is open. */
    val end: End? get() = synchronized(lock) { ended }

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
    override fun close() = shut(End.CLOSED, null)

    private class Connection(val socket: Socket, val due: Long)

    private fun run() {
        try {
            while (true) {
                val now = System.nanoTime()
                sweep(now)
                val (listening, busy) = synchronized(lock) { (ended == null) to serving.isNotEmpty() }
                when {
                    listening && now - endsAt >= 0 -> shut(End.EXPIRED, null)
                    listening -> acceptOne()
                    busy -> Thread.sleep(tickMs)
                    else -> return
                }
            }
        } catch (_: Exception) {
            shut(End.CLOSED, null)
        }
    }

    private fun acceptOne() {
        if (!slots.tryAcquire(tickMs, TimeUnit.MILLISECONDS)) return
        val socket = try {
            synchronized(accepting) { listener.accept() }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            shut(End.CLOSED, null)
            null
        }
        if (socket == null) {
            slots.release()
            return
        }
        val connection = Connection(socket, System.nanoTime() + (limits.headMs + limits.bodyMs + limits.lingerMs + SLACK_MS) * NANOS_PER_MS)
        val over = synchronized(lock) {
            if (ended != null) return@synchronized null
            serving += connection
            ++taken > limits.requests
        }
        if (over == null) {
            closeQuietly(socket)
            slots.release()
            return
        }
        if (over) shut(End.USED_UP, connection)
        try {
            Thread({ serve(connection, over) }, "tern-handoff-request").apply { isDaemon = true }.start()
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
            val answer = if (over) {
                Pages.usedUp()
            } else {
                try {
                    answerTo(wire)
                } catch (refused: Refused) {
                    refused.answer
                } catch (_: OutOfMemoryError) {
                    Pages.busy()
                }
            }
            socket.getOutputStream().apply {
                write(answer.bytes())
                flush()
            }
            socket.shutdownOutput()
            wire.drain(DRAIN_BODIES * limits.sendBytes, limits.lingerMs)
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

    private fun answerTo(wire: Wire): Answer {
        val head = wire.head(limits.headBytes, limits.headMs)
        val length = lengthOf(head, wire)
        return when {
            head.header("host") != host -> Pages.notFound()
            head.method == "GET" && head.target == "/" -> Pages.page(null, limits)
            head.method == "POST" && head.target == "/send" -> send(wire, head, length)
            else -> Pages.notFound()
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

    private fun send(wire: Wire, head: Head, length: Int): Answer {
        if (Forms.typeOf(head.header("content-type")) != Forms.TYPE) return said(Notice.UNREADABLE)
        if (length > limits.sendBytes) return said(Notice.TOO_LARGE)
        val sealed = Forms.sealed(wire.body(length, limits.bodyMs)) ?: return said(Notice.DID_NOT_OPEN)
        val opened = seal.open(sealed) ?: return said(Notice.DID_NOT_OPEN)
        return when (opened.kind) {
            Seal.LINKS -> links(opened.plain)
            Seal.FILE -> file(opened.plain)
            else -> said(Notice.DID_NOT_OPEN)
        }
    }

    private fun said(notice: Notice) = Pages.page(notice, limits)

    private fun links(plain: ByteArray): Answer {
        if (plain.size > limits.linksBytes) return said(Notice.LINKS_TOO_LARGE)
        val text = Forms.utf8(plain) ?: return said(Notice.UNREADABLE)
        return when (val read = Forms.links(text, limits)) {
            is Forms.Links.Refused -> said(read.notice)
            is Forms.Links.Taken -> said(offer(read.links.map { HandoffItem.Link(it) }, 0, Notice.LINKS_SENT))
        }
    }

    private fun file(plain: ByteArray): Answer {
        val file = Forms.file(plain) ?: return said(Notice.UNREADABLE)
        return when {
            file.content.isEmpty() -> said(Notice.NO_FILE)
            file.content.size > limits.fileBytes -> said(Notice.FILE_TOO_LARGE)
            else -> said(offer(listOf(HandoffItem.ExportFile(file.name, file.content)), file.content.size, Notice.FILE_SENT))
        }
    }

    private fun offer(items: List<HandoffItem>, bytes: Int, sent: Notice): Notice {
        synchronized(lock) {
            if (ended != null) throw Refused(Pages.closed())
            if (queue.size + items.size > limits.waiting || queuedBytes + bytes > limits.waitingBytes) return Notice.TOO_MUCH_WAITS
            queue += items
            queuedBytes += bytes
        }
        tell()
        return sent
    }

    /** Closes for good, for the first reason that comes. The connection in [keep] stays, so that it can still be given the answer that says so. */
    private fun shut(why: End, keep: Connection?) {
        val others = synchronized(lock) {
            if (ended != null) return
            ended = why
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
        private const val MAX_LENGTH_DIGITS = 9
        private const val DRAIN_BODIES = 8L

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
