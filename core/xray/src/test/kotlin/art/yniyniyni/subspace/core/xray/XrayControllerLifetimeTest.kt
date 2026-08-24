// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.ContinuationInterceptor

class XrayControllerLifetimeTest {
    private object ForcedStartFailure : Throwable("TASK4_FORCED_RUN_XRAY_FAILURE")

    private object ForcedRegistrationFailure : Throwable("TASK4_FORCED_CONTROLLER_REGISTRATION_FAILURE")

    @Test
    fun `protector lifetime follows failed start successful start and stop`() =
        runTest {
            val startFailure = ForcedStartFailure
            var failureToThrow: Throwable? = startFailure
            val invocation =
                XrayControllerInvocation(
                    registerControllers = {},
                    invokeRunXray = { _, _ -> failureToThrow?.let { throw it } },
                    invokeStopXray = {},
                )
            val testDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val controller = XrayController(invocation = invocation, io = testDispatcher)
            val configFile = File("TASK4_UNUSED_CONFIG_PATH.json")
            val protector = SocketProtector { true }

            try {
                val thrown =
                    shouldThrow<ForcedStartFailure> {
                        controller.start(configFile, protector)
                    }

                assertSame("start must rethrow the original exception", startFailure, thrown)
                assertFalse("failed start retained its protector", invocation.hasProtectorTarget())

                failureToThrow = null
                controller.start(configFile, protector)
                assertTrue("successful start dropped its live protector", invocation.hasProtectorTarget())

                controller.stopBlocking()
                assertFalse("stop retained its protector", invocation.hasProtectorTarget())
            } finally {
                invocation.clearProtector()
            }
        }

    @Test
    fun `registration failure clears a previously retained protector`() =
        runTest {
            var registrationFailure: Throwable? = null
            val invocation =
                XrayControllerInvocation(
                    registerControllers = { registrationFailure?.let { throw it } },
                    invokeRunXray = { _, _ -> },
                    invokeStopXray = {},
                )
            val testDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val controller = XrayController(invocation = invocation, io = testDispatcher)
            val configFile = File("TASK4_UNUSED_REGISTRATION_CONFIG_PATH.json")
            val protector = SocketProtector { true }

            try {
                controller.start(configFile, protector)
                assertTrue("test precondition did not retain a protector", invocation.hasProtectorTarget())

                registrationFailure = ForcedRegistrationFailure
                val thrown =
                    shouldThrow<ForcedRegistrationFailure> {
                        controller.start(configFile, protector)
                    }

                assertSame("start must rethrow the registration exception", ForcedRegistrationFailure, thrown)
                assertFalse("registration failure retained the stale protector", invocation.hasProtectorTarget())
            } finally {
                invocation.clearProtector()
            }
        }
}
