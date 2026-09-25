// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.service.log.LogcatReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
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
 * write end has closed behaves: what was written before it is still read
 * first. That is what a SIGTERM'd `logcat` leaves behind (R47).
 *
 * [closeParentEnd] is the other thing that can happen to a pipe, and the one
 * Android's `Process.destroy()` does (`UNIXProcess.java:221-234`: kill, then
 * close stdin/stdout/stderr — *our* end). Everything unread is discarded, and
 * every read after it — including one already blocked — throws
 * `IOException("Stream closed")`. The fake at 6ea559a modelled `destroy()` as
 * [eof] instead, which is why the watchdog's data loss (review I-1) passed.
 */
internal class LinePipe(pidLine: Int? = PipeProcess.FAKE_PID) : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray? = null
    private var position = 0

    @Volatile
    private var parentEndClosed = false

    /** The name of the thread that closed this stream, or null if nothing has. */
    @Volatile
    var closedBy: String? = null
        private set

    fun feed(line: String) = chunks.put((line + "\n").toByteArray())

    fun eof() = chunks.put(EOF)

    /** What Android's `Process.destroy()` does to this stream: discard everything unread. */
    fun closeParentEnd() {
        parentEndClosed = true
        chunks.clear()
        chunks.put(CLOSED)
    }

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
        if (parentEndClosed) throw IOException("Stream closed")
        val chunk = current
        if (chunk != null && position < chunk.size) return chunk
        val next = chunks.take()
        if (next === CLOSED || parentEndClosed) {
            chunks.put(CLOSED)
            throw IOException("Stream closed")
        }
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

    init {
        // R47: the shell's `echo $$` is the first thing on the pipe, before
        // anything logcat writes. null: a shell that never got that far.
        if (pidLine != null) feed(pidLine.toString())
    }

    private companion object {
        val EOF = ByteArray(0)
        val CLOSED = ByteArray(0)
        const val BYTE_MASK = 0xff
    }
}

/**
 * A [Process] over a [LinePipe]. The R47 PID line is the pipe's first line
 * (see [LinePipe]'s constructor).
 *
 * [sigterm] is the watchdog's `Os.kill(pid, SIGTERM)`: `logcat` exits cleanly
 * — [eof] queued *behind* what it already wrote — unless [exitsOnSigterm] is
 * false, a `logcat` wedged hard enough to ignore it (R18). [destroy] is
 * Android's: the process is gone and the parent's end of the pipe is closed,
 * unread data and all ([LinePipe.closeParentEnd]). [exitValue] throws
 * `IllegalThreadStateException` while the process is running, as the real one
 * does — the liveness guard R47 relies on.
 */
internal class PipeProcess(
    val pipe: LinePipe,
    private val exitsOnSigterm: Boolean = true,
    private val events: MutableList<String>? = null,
) : Process() {
    @Volatile
    private var exited = false

    /** The name of the thread that destroyed this process *first*; a later, redundant destroy does not overwrite it. */
    @Volatile
    var destroyedBy: String? = null
        private set

    /** The names of the threads that SIGTERM'd this process, in order. */
    val signalledBy = CopyOnWriteArrayList<String>()

    val destroyed: Boolean get() = destroyedBy != null

    fun sigterm() {
        signalledBy += Thread.currentThread().name
        events?.add("process-signalled")
        if (exitsOnSigterm && !exited) {
            exited = true
            pipe.eof()
        }
    }

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = pipe

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = if (exited) 0 else throw IllegalThreadStateException("process hasn't exited")

    override fun destroy() {
        synchronized(this) { if (destroyedBy == null) destroyedBy = Thread.currentThread().name }
        events?.add("process-destroyed")
        exited = true
        pipe.closeParentEnd()
    }

    companion object {
        const val FAKE_PID = 4242
    }
}

/**
 * One `logcat` over a [FakeLogd]: a [LogcatReader] whose spawn produces a
 * [PipeProcess] on [logd]'s pipe (after [beforeSpawn], a hook for holding the
 * spawn), and whose R47 signal seam SIGTERMs that process.
 */
internal class FakeLogcat(
    val logd: FakeLogd,
    exitsOnSigterm: Boolean = true,
    beforeSpawn: () -> Unit = {},
) {
    @Volatile
    var process: PipeProcess? = null
        private set

    val signalled = CopyOnWriteArrayList<Int>()

    val reader =
        LogcatReader(signal = { pid ->
            signalled += pid
            process?.sigterm()
        }) {
            beforeSpawn()
            PipeProcess(logd.pipe, exitsOnSigterm).also { process = it }
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
