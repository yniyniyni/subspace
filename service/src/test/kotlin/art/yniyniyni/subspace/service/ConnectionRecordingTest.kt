// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.failure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
