// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class QrScannerTest {
    @Test
    fun decodesAVlessLinkFromAGeneratedQrBitmap() {
        val link = "vless://8f2c4a1e-0000-4000-8000-000000000001@198.51.100.1:443?security=reality#Frankfurt"

        QrAnalyzer.decodeLuminance(renderQr(link)) shouldBe link
    }

    @Test
    fun returnsNullOnAFrameWithNoCodeRatherThanThrowing() {
        QrAnalyzer.decodeLuminance(blankLuminance()).shouldBeNull()
    }
}

private const val FIXTURE_SIZE = 300
private const val LUMINANCE_WHITE: Byte = 0xFF.toByte()
private const val LUMINANCE_BLACK: Byte = 0x00

private fun renderQr(text: String): LuminanceFrame {
    val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, FIXTURE_SIZE, FIXTURE_SIZE)
    val data = ByteArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            data[(y * matrix.width) + x] = if (matrix.get(x, y)) LUMINANCE_BLACK else LUMINANCE_WHITE
        }
    }
    return LuminanceFrame(data = data, width = matrix.width, height = matrix.height)
}

private fun blankLuminance(): LuminanceFrame {
    val data = ByteArray(FIXTURE_SIZE * FIXTURE_SIZE) { LUMINANCE_WHITE }
    return LuminanceFrame(data = data, width = FIXTURE_SIZE, height = FIXTURE_SIZE)
}
