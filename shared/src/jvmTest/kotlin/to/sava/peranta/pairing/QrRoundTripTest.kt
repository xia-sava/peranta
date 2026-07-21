package to.sava.peranta.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QrRoundTripTest {

    private fun decodePng(file: File): String {
        val image = ImageIO.read(file)
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        return MultiFormatReader().decode(bitmap, hints).text
    }

    /** PairingData → URI → QR PNG → 再デコードで元 URI に戻る（GUI 無しで「生成した QR が読める」担保）。 */
    @Test
    fun generatedPngDecodesBackToOriginalUri() {
        val data = PairingData(
            host = "peranta.sava.to",
            token = "tk_secret_ABC-123",
            keyId = "k1",
            key = ByteArray(32) { (it * 7 + 1).toByte() },
            port = 8443,
        )
        val uri = PairingUri.encode(data)

        val target = writePairingQrPng(uri, File("build/qr-roundtrip/pairing-qr.png"))

        assertEquals(uri, decodePng(target))
        val decoded = PairingUri.decode(uri)
        assertIs<PairingResult.Success>(decoded)
        assertEquals(data, decoded.data)
        println("QR round-trip PNG: ${target.absolutePath}")
    }
}
