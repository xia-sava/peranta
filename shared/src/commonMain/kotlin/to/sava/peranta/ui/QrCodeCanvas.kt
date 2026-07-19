package to.sava.peranta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import to.sava.peranta.pairing.QrMatrix

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
