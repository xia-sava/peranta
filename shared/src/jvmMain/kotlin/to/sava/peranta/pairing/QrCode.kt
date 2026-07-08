package to.sava.peranta.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** UI 非依存の QR ドット行列。true が黒モジュール（§10.3 の描画は呼び出し側が担う）。 */
class QrMatrix(val size: Int, private val modules: BooleanArray) {

    /** モジュール ([x], [y]) が黒か。 */
    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]
}

/** QR の周囲に確保する静穏帯（モジュール数）。読み取り安定のため QR 規格推奨の 4 とする。 */
private const val QR_MARGIN: Int = 4

/** PNG 書き出し時の 1 モジュールあたりの既定ピクセル数。 */
private const val DEFAULT_PIXELS_PER_MODULE: Int = 8

private const val RGB_BLACK: Int = 0x000000
private const val RGB_WHITE: Int = 0xFFFFFF

/** ペアリング [uri] から QR ドット行列を生成する。 */
fun pairingQrMatrix(uri: String): QrMatrix {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to QR_MARGIN,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val bitMatrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 0, 0, hints)
    val size = bitMatrix.width
    val modules = BooleanArray(size * size) { index ->
        bitMatrix.get(index % size, index / size)
    }
    return QrMatrix(size, modules)
}

/**
 * ペアリング [uri] の QR を [pixelsPerModule] 倍に拡大した PNG として [target] に書き出す。
 * GUI 無しで「生成した QR が読める」ことを担保する headless 検証用。書き出した [target] を返す。
 */
fun writePairingQrPng(
    uri: String,
    target: File,
    pixelsPerModule: Int = DEFAULT_PIXELS_PER_MODULE,
): File {
    val matrix = pairingQrMatrix(uri)
    val imageSize = matrix.size * pixelsPerModule
    val image = BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until imageSize) {
        for (x in 0 until imageSize) {
            val dark = matrix.isDark(x / pixelsPerModule, y / pixelsPerModule)
            image.setRGB(x, y, if (dark) RGB_BLACK else RGB_WHITE)
        }
    }
    target.parentFile?.mkdirs()
    ImageIO.write(image, "png", target)
    return target
}
