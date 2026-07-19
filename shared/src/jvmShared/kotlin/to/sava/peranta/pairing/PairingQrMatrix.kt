package to.sava.peranta.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR の周囲に確保する静穏帯（モジュール数）。読み取り安定のため QR 規格推奨の 4 とする。 */
private const val QR_MARGIN: Int = 4

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
