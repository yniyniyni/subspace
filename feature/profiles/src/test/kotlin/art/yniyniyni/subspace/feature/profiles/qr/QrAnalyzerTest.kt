// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * [QrAnalyzer.decodeLuminance] is a pure function over a luminance plane —
 * no Android/CameraX type in its signature — so this runs as a plain JVM
 * unit test, without a device (ARCHITECTURE.md §11). Fixtures are rendered
 * with ZXing's own [QRCodeWriter], the same round trip a camera + decoder
 * pair performs on device, minus the camera.
 *
 * Backtick-free names: this is `:feature:profiles:testDebugUnitTest`, a
 * plain JVM test, so the DEX-040 camelCase restriction that applies to this
 * repo's *instrumented* tests does not technically apply here — camelCase is
 * used anyway for consistency with every other test file this module's
 * `androidTest` and `test` source sets carry.
 */
class QrAnalyzerTest {
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

// PlanarYUVLuminanceSource (and the binarizers built on it) reads each byte
// as an UNSIGNED luminance value (`row[x] and 0xff`), so 255 — full
// brightness / white — must be encoded as the signed byte -1, not 255
// (which does not fit a Kotlin Byte at all). 0 is 0 in both signed and
// unsigned, so black needs no such conversion.
private const val LUMINANCE_WHITE: Byte = 0xFF.toByte()
private const val LUMINANCE_BLACK: Byte = 0x00

/**
 * Renders [text] to a QR bitmap with ZXing's own writer, then converts the
 * resulting module grid into the luminance layout [QrAnalyzer.decodeLuminance]
 * consumes: one byte per pixel, dark module = low luminance, light module =
 * high luminance. [QRCodeWriter] already bakes in the quiet-zone margin the
 * decoder needs to find the finder patterns, so nothing else is added here.
 */
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

/** A blank, all-white frame: a real preview frame the instant before a code enters it. */
private fun blankLuminance(): LuminanceFrame {
    val data = ByteArray(FIXTURE_SIZE * FIXTURE_SIZE) { LUMINANCE_WHITE }
    return LuminanceFrame(data = data, width = FIXTURE_SIZE, height = FIXTURE_SIZE)
}
