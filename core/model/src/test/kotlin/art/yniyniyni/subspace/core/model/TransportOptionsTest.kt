// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class TransportOptionsTest {
    @Test
    fun `websocket options carry path and headers`() {
        val ws = TransportOptions.WebSocket(path = "/ray", headers = mapOf("Host" to "cdn.example"))

        ws.path shouldBe "/ray"
        ws.headers["Host"] shouldBe "cdn.example"
    }

    @Test
    fun `grpc options carry the service name`() {
        TransportOptions.Grpc(serviceName = "GunService").serviceName shouldBe "GunService"
    }

    @Test
    fun `stream settings default to no transport options`() {
        val stream = StreamSettings(network = "tcp", security = Security.None)

        stream.transport shouldBe TransportOptions.None
    }
}
