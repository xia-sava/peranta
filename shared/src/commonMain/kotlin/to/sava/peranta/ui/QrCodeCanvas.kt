package to.sava.peranta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import to.sava.peranta.pairing.QrMatrix

/** 拡大表示の一辺の上限。これ以上大きくしても画面に収まらず、周囲の導線が押し出される。 */
private val ZOOMED_QR_MAX_SIZE = 480.dp

/** 拡大表示を閉じるボタンのラベル。 */
private const val ZOOMED_QR_CLOSE_LABEL: String = "閉じる"

/** QR ドット行列 [matrix] を白背景・黒モジュールで描画する。描画サイズは [modifier] で指定する。 */
@Composable
fun QrCodeCanvas(matrix: QrMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val moduleSize = size.minDimension / matrix.size
        drawRect(color = Color.White, size = Size(matrix.size * moduleSize, matrix.size * moduleSize))
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * moduleSize, y * moduleSize),
                        size = Size(moduleSize, moduleSize),
                    )
                }
            }
        }
    }
}

/**
 * 押すと拡大表示する QR（§10.3）。発光面を撮ると白が黒モジュールへ滲み出すため、小さく描くと
 * 孤立した黒モジュールから潰れて読み取れなくなる。滲み出す幅はモジュールの大きさに依らずほぼ
 * 一定なので、1 モジュールの実寸を稼げる拡大表示を用意する。
 */
@Composable
fun ZoomableQrCode(matrix: QrMatrix, modifier: Modifier = Modifier) {
    var zoomed by remember { mutableStateOf(false) }
    QrCodeCanvas(
        matrix = matrix,
        modifier = modifier.clickable { zoomed = true }.testTag(TAG_QR_CODE),
    )
    if (!zoomed) return
    AlertDialog(
        onDismissRequest = { zoomed = false },
        confirmButton = {
            TextButton(onClick = { zoomed = false }, modifier = Modifier.testTag(TAG_QR_ZOOM_CLOSE)) {
                Text(text = ZOOMED_QR_CLOSE_LABEL)
            }
        },
        containerColor = Color.White,
        text = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                QrCodeCanvas(
                    matrix = matrix,
                    modifier = Modifier
                        .size(minOf(maxWidth, ZOOMED_QR_MAX_SIZE))
                        .testTag(TAG_QR_ZOOMED),
                )
            }
        },
    )
}

const val TAG_QR_CODE: String = "qr-code"
const val TAG_QR_ZOOMED: String = "qr-zoomed"
const val TAG_QR_ZOOM_CLOSE: String = "qr-zoom-close"
