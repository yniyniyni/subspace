// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.failure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Covers [ConnectionRecorder], the one persistence side-effect `:bg` performs
 * (spec D4): writing a connect attempt's outcome back to the profile row named
 * by [ProfileParcel.rowId].
 *
 * These fake `ProfileRepository.recordConnected`/`recordError` with plain
 * lambdas rather than a real `ProfileRepository`: that class's constructor is
 * `internal` to `:core:data`, so `:service` cannot build a real instance, and
 * this project has no mocking library to substitute for one either — see
 * [ConnectionRecorder]'s KDoc.
 */
class ConnectionRecordingTest {
    private class RecordedCall(
        val rowId: Long,
        val value: Any,
    )

    private class FakeRecorder {
        val connectedCalls = mutableListOf<RecordedCall>()
        val errorCalls = mutableListOf<RecordedCall>()
        var failures = 0

        val recorder =
            ConnectionRecorder(
                recordConnected = { rowId, at -> connectedCalls += RecordedCall(rowId, at) },
                recordError = { rowId, detail -> errorCalls += RecordedCall(rowId, detail) },
                onFailure = { failures++ },
            )
    }

    // Block bodies throughout, deliberately: JUnit4 requires a @Test method to
    // return void, and `fun f() = runBlocking { ... }` lets the block's last
    // expression (a kotest matcher's return type) leak out as the method's
    // inferred return type instead.

    @Test
    fun `a successful connect records the timestamp and clears the error`() {
        runBlocking {
            val fake = FakeRecorder()
            val before = System.currentTimeMillis()

            fake.recorder.record(
                rowId = 7L,
                state = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808),
            )

            val after = System.currentTimeMillis()
            fake.connectedCalls.size shouldBe 1
            fake.connectedCalls.single().rowId shouldBe 7L
            val recordedAt = fake.connectedCalls.single().value as Long
            (recordedAt in before..after) shouldBe true
            // recordConnected's own query clears lastError (ProfileDao) — the
            // point here is that a successful connect must never *also* call
            // recordError, which would be a second, contradictory write.
            fake.errorCalls.isEmpty() shouldBe true
            fake.failures shouldBe 0
        }
    }

    @Test
    fun `a failed connect records the redacted detail`() {
        runBlocking {
            val fake = FakeRecorder()
            val state = failure(FailureReason.CoreStartFailed, "dial tcp 203.0.113.44:443 refused")

            fake.recorder.record(rowId = 9L, state = state)

            fake.errorCalls.size shouldBe 1
            fake.errorCalls.single().rowId shouldBe 9L
            fake.connectedCalls.isEmpty() shouldBe true
            fake.failures shouldBe 0
        }
    }

    // ── Cancellation is not a persistence failure (PR #4 review, P1 finding A) ──

    @Test
    fun `cancellation propagates rather than being swallowed as a write failure`() {
        // record() catches Exception so a Room failure cannot take down a working
        // tunnel (§10.4). CancellationException *is* an Exception, so that catch
        // also swallowed service-scope cancellation — the stale start coroutine
        // then resumed and ran the lifecycle call that followed the write, which
        // is half of how a superseded generation could reassert foreground state.
        var failures = 0
        val recorder =
            ConnectionRecorder(
                recordConnected = { _, _ -> throw CancellationException("scope cancelled") },
                recordError = { _, _ -> error("not reached") },
                onFailure = { failures++ },
            )

        shouldThrow<CancellationException> {
            runBlocking {
                recorder.record(
                    rowId = 1L,
                    state = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808),
                )
            }
        }
        // Not reported as a persistence failure: nothing failed to write, the
        // caller was cancelled.
        failures shouldBe 0
    }

    @Test
    fun `cancellation propagates from the failure-recording path too`() {
        var failures = 0
        val recorder =
            ConnectionRecorder(
                recordConnected = { _, _ -> error("not reached") },
                recordError = { _, _ -> throw CancellationException("scope cancelled") },
                onFailure = { failures++ },
            )

        shouldThrow<CancellationException> {
            runBlocking {
                recorder.record(rowId = 1L, state = failure(FailureReason.CoreStartFailed, "redacted"))
            }
        }
        failures shouldBe 0
    }

    @Test
    fun `an ordinary repository failure is reported but never escapes`() {
        // The guarantee that must survive the fix above: a Room write that fails
        // for its own reasons must not propagate into the start sequence.
        var reported: Throwable? = null
        val recorder =
            ConnectionRecorder(
                recordConnected = { _, _ -> throw IllegalStateException("disk I/O error on table profiles") },
                recordError = { _, _ -> error("not reached") },
                onFailure = { reported = it },
            )

        runBlocking {
            recorder.record(
                rowId = 1L,
                state = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808),
            )
        }

        reported.shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `the recorded error is the redacted detail, never the raw message`() {
        runBlocking {
            val fake = FakeRecorder()
            // ConnectionState.Failed.detail is redacted at construction and its
            // constructor is closed (core/model/ConnectionState.kt) — the only way
            // this string could carry the raw address is if the service somehow
            // reconstructed a different message. This asserts it persists exactly
            // what failure() produced.
            val raw = "dial tcp 203.0.113.44:443 refused"
            val state = failure(FailureReason.CoreStartFailed, raw)

            fake.recorder.record(rowId = 3L, state = state)

            val persisted = fake.errorCalls.single().value as String
            persisted shouldBe state.detail
            persisted shouldNotContain "203.0.113.44"
        }
    }
}
