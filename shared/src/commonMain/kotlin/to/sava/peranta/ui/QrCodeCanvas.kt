package to.sava.peranta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import to.sava.peranta.pairing.QrMatrix

/**
 * QR の描画サイズ。拡大表示でも同じ大きさで描き、広げるのは周囲の白い余白だけにする。
 * 読み取りに効くのはモジュールの実寸ではなくカメラ視野の明るさで、QR を大きくすると
 * かえって撮影距離を取らされる。
 */
private val QR_SIZE = 240.dp

/** 拡大表示の閉じ方の案内。白い面を汚さないよう、ボタンではなく文字だけを置く。 */
private const val ZOOMED_QR_CLOSE_HINT: String = "画面を押すと閉じます"

/** 案内文と画面端の間隔。 */
private val ZOOMED_QR_HINT_PADDING = 24.dp

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
 * 押すと画面いっぱいの白い面へ載せ替える QR（§10.3）。読み取る側のカメラは視野全体の明るさに
 * 露出を合わせるため、暗い画面に出すと露出が上がって白が黒モジュールへ滲み出し、孤立した
 * 黒モジュールから潰れて読めなくなる。効くのは QR の大きさではなく**周囲を含めた画面の白さ**で、
 * 載せ替えても QR 自体の大きさは変えない（実機で確認）。
 */
@Composable
fun ZoomableQrCode(matrix: QrMatrix, modifier: Modifier = Modifier) {
    var zoomed by remember { mutableStateOf(false) }
    QrCodeCanvas(
        matrix = matrix,
        modifier = modifier.size(QR_SIZE).clickable { zoomed = true }.testTag(TAG_QR_CODE),
    )
    if (!zoomed) return
    // ダイアログの中身には内容に合わせた制約しか渡らず fillMaxSize が伸びないため、
    // ウィンドウの実寸を外側で測って要求する。ダイアログの中で測ると自分自身の寸法になる。
    val windowSize = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    Dialog(
        onDismissRequest = { zoomed = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            // 押した波紋も白い面を乱すため、視覚効果は付けずに閉じる操作だけを受ける。
            modifier = Modifier
                .requiredSize(windowSize)
                .background(Color.White)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { zoomed = false }
                .testTag(TAG_QR_ZOOM_SURFACE),
            contentAlignment = Alignment.Center,
        ) {
            QrCodeCanvas(matrix = matrix, modifier = Modifier.size(QR_SIZE).testTag(TAG_QR_ZOOMED))
            Text(
                text = ZOOMED_QR_CLOSE_HINT,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(ZOOMED_QR_HINT_PADDING),
            )
        }
    }
}

const val TAG_QR_CODE: String = "qr-code"
const val TAG_QR_ZOOMED: String = "qr-zoomed"
const val TAG_QR_ZOOM_SURFACE: String = "qr-zoom-surface"
