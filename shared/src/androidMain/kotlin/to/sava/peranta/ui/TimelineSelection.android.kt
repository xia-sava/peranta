package to.sava.peranta.ui

import androidx.compose.runtime.Composable

/**
 * Android は選択の仕組みが出す標準のツールバーがそのまま複写を担うため、何も差し挟まない（§10.1）。
 */
@Composable
actual fun TimelineSelectionScope(content: @Composable () -> Unit) {
    content()
}

/** Android の複写は選択のツールバーが担うため、メニューには項目を出さない（§10.1）。 */
@Composable
actual fun copySelectionOrNull(): (() -> Unit)? = null
