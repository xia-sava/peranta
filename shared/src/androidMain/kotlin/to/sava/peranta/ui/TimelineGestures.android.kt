package to.sava.peranta.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * Android はメニューボタンだけでコンテキストメニューを開き、ジェスチャを持たない（§10.1）。
 * 長押しは本文の文字選択に使う。
 */
actual fun Modifier.timelineContextGesture(enabled: Boolean, onOpenMenu: (position: Offset) -> Unit): Modifier = this
