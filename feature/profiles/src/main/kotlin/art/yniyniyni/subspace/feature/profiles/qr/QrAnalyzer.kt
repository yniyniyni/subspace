// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.qr

import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * One camera frame's Y (luminance) plane, laid out the way ZXing's
 * [PlanarYUVLuminanceSource] expects: [rowStride] bytes per row (which can
 * exceed [width] when the camera pads each row) and [height] rows of [data].
 */
internal class LuminanceFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int = width,
)

/**
 * Decodes a QR code from a single camera frame's luminance plane.
 *
 * [decodeLuminance] is what makes this JVM-testable without a device
 * (ARCHITECTURE.md §11): it takes no Android/CameraX type, so
 * [QrAnalyzerTest][art.yniyniyni.subspace.feature.profiles.qr.QrAnalyzerTest]
 * can hand it a frame rendered by ZXing's own
 * [com.google.zxing.qrcode.QRCodeWriter] and assert on the result. [decode]
 * is the thin CameraX shell around it — the only member here that touches
 * [ImageProxy] — used by [QrScanScreen]'s `ImageAnalysis.Analyzer`.
 */
internal object QrAnalyzer {
    /**
     * @return the decoded text, or `null` if [frame] carries no readable
     *   code. A live camera feed shows no QR code in the overwhelming
     *   majority of frames — that is ordinary input, not a failure, so this
     *   never throws (ARCHITECTURE.md §10.4 is about swallowing a *real*
     *   error, not a blank frame).
     */
    fun decodeLuminance(frame: LuminanceFrame): String? {
        val source =
            PlanarYUVLuminanceSource(
                frame.data,
                frame.rowStride,
                frame.height,
                0,
                0,
                frame.width,
                frame.height,
                false,
            )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            // A fresh reader per call: QRCodeReader carries per-decode hint
            // state, and CameraX may call this from a single background
            // executor thread but concurrently with nothing else touching
            // it — cheaper and simpler than reasoning about reuse safety.
            QRCodeReader().decode(bitmap).text
        } catch (ignored: NotFoundException) {
            null
        } catch (ignored: FormatException) {
            null
        } catch (ignored: ChecksumException) {
            null
        }
    }

    /**
     * [ImageProxy.getPlanes]`[0]` *is* the Y/luminance plane for
     * `ImageAnalysis.Analyzer`'s default `YUV_420_888` output — no colour
     * conversion needed, a QR code is read from luminance alone. Assumes the
     * Y plane's `pixelStride` is 1, true for every Android camera this was
     * verified against (§11) and for `YUV_420_888` Y planes generally; a
     * `pixelStride` other than 1 would degrade to "no code found" rather
     * than crash, since [decodeLuminance] never throws.
     */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return decodeLuminance(
            LuminanceFrame(
                data = bytes,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
            ),
        )
    }
}
