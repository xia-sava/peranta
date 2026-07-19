package to.sava.peranta.pairing

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** PNG 書き出し時の 1 モジュールあたりの既定ピクセル数。 */
private const val DEFAULT_PIXELS_PER_MODULE: Int = 8

private const val RGB_BLACK: Int = 0x000000
private const val RGB_WHITE: Int = 0xFFFFFF

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
