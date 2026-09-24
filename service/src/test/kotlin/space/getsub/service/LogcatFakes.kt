// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A blocking stand-in for `logcat`'s stdout pipe (N1). Unlike a
 * [ByteArrayInputStream], a read on an empty pipe *blocks* until a line is fed
 * or [eof] is signalled — the shape the real subprocess has, and the one every
 * N1 loss mechanism depends on.
 *
 * Each [feed] is delivered by exactly one `read` call, so a capture thread
 * that has consumed line 1 has not, by that fact alone, consumed line 2: the
 * rest of the pipe is genuinely still "in the pipe", the state mechanism (c)
 * throws away.
 *
 * [eof] is sticky — every read after it returns -1 — the way a pipe whose
 * write end has closed behaves.
 */
internal class LinePipe : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray? = null
    private var position = 0

    /** The name of the thread that closed this stream, or null if nothing has. */
    @Volatile
    var closedBy: String? = null
        private set

    fun feed(line: String) = chunks.put((line + "\n").toByteArray())

    fun eof() = chunks.put(EOF)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val chunk = if (len == 0) null else currentOrNext()
        return when {
            len == 0 -> 0
            chunk == null -> -1
            else -> {
                val n = minOf(len, chunk.size - position)
                System.arraycopy(chunk, position, b, off, n)
                position += n
                n
            }
        }
    }

    /** The chunk being read, or the next one (blocking); null once [eof] has been signalled. */
    private fun currentOrNext(): ByteArray? {
        val chunk = current
        if (chunk != null && position < chunk.size) return chunk
        val next = chunks.take()
        if (next === EOF) {
            chunks.put(EOF)
            current = null
        } else {
            current = next
            position = 0
        }
        return current
    }

    override fun close() {
        closedBy = Thread.currentThread().name
    }

    private companion object {
        val EOF = ByteArray(0)
        const val BYTE_MASK = 0xff
    }
}

/**
 * A [Process] over a [LinePipe]. [destroy] closes the pipe's write end —
 * EOF — the way killing the real subprocess does, unless [eofOnDestroy] is
 * false, which models a subprocess that is wedged and never lets go (R18).
 */
internal class PipeProcess(
    val pipe: LinePipe,
    private val eofOnDestroy: Boolean = true,
    private val events: MutableList<String>? = null,
) : Process() {
    /** The name of the thread that destroyed this process *first*; a later, redundant destroy does not overwrite it. */
    @Volatile
    var destroyedBy: String? = null
        private set

    val destroyed: Boolean get() = destroyedBy != null

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = pipe

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() {
        synchronized(this) { if (destroyedBy == null) destroyedBy = Thread.currentThread().name }
        events?.add("process-destroyed")
        if (eofOnDestroy) pipe.eof()
    }
}

/**
 * The log daemon, as far as one `logcat` reader can see it: lines are
 * [log]ged in order, and reach the reader's pipe only when [deliver] says so.
 * That split is exactly what the device measurements show — a line logged is
 * not a line delivered, by up to ~3.9 s on a freshly spawned `logcat`.
 */
internal class FakeLogd(val pipe: LinePipe = LinePipe()) {
    private val pending = mutableListOf<String>()

    @Synchronized
    fun log(
        body: String,
        tag: String = "TunnelService",
    ) {
        pending += logcatLine(tag, body)
    }

    @Synchronized
    fun deliver() {
        pending.forEach(pipe::feed)
        pending.clear()
    }
}

/** A `-v threadtime` line, the real shape `LogcatReader.lines()` emits. */
internal fun logcatLine(
    tag: String,
    body: String,
) = "09-17 19:40:00.123  1234  5678 I $tag: $body"

/**
 * Waits — without sleeping — until [thread] is parked in a wait. Bounded, so
 * a regression fails the test instead of hanging the build.
 */
internal fun awaitParked(
    thread: Thread,
    timeoutMillis: Long = 5_000,
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val parked = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
    var state = thread.state
    while (state !in parked && state != Thread.State.TERMINATED && System.nanoTime() < deadline) {
        Thread.onSpinWait()
        state = thread.state
    }
    return state in parked
}
