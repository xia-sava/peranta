package to.sava.peranta.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 画面で使う記号をベクタ画像として持つ。
 *
 * 記号を文字で描くとフォントに字形が無いときに何も出ない（`✓` のような Dingbats は環境で欠ける）。
 * アイコンライブラリは Compose Multiplatform 1.7 以降で提供が止まっているため、形だけを自前で持つ。
 * 色は [androidx.compose.material3.Icon] の tint が決めるので、ここでは塗りを黒で置く。
 */
private fun icon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
    }.build()

private val ICON_SIZE = 24.dp

private const val ICON_VIEWPORT = 24f

/** チェック。元通知がまだ残っていることを示す（§10.1）。 */
val CheckIcon: ImageVector = icon(
    name = "Check",
    pathData = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
)

/** 罰点。元通知が既に消えていることを示す（§10.1）。 */
val CloseIcon: ImageVector = icon(
    name = "Close",
    pathData = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 " +
        "17.59,19 19,17.59 13.41,12z",
)
