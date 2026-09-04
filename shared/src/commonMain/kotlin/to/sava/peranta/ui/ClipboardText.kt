package to.sava.peranta.ui

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/**
 * [text] をクリップボードへ入れる。画面から値を複写する操作（§10.1 の本文中のコード・§10.3 の
 * ペアリング文字列・§10.5 の設定値）の共通の口。
 *
 * Compose は文字列を [ClipEntry] にする手立てを公開していないため、[plainTextClipEntry] で
 * プラットフォームごとの作り方を吸収する。
 */
suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(plainTextClipEntry(text))
}

/** [text] だけを持つ [ClipEntry] を作る。 */
internal expect fun plainTextClipEntry(text: String): ClipEntry
