package to.sava.peranta.ui

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/** 選択の仕組みから受け取った、選択中の本文を扱う口。選択の範囲の外では null。 */
@OptIn(ExperimentalFoundationApi::class)
private val LocalTimelineTextManager = compositionLocalOf<TextContextMenu.TextManager?> { null }

/**
 * Desktop は選択の仕組みが持つ右クリックメニューを出さず、選択の情報だけを内側へ渡す（§10.1）。
 * 右クリックにはタイムライン自身のメニューが応じ、そこに「コピー」を並べる。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun TimelineSelectionScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextContextMenu provides TimelineTextContextMenu, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private object TimelineTextContextMenu : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalTimelineTextManager provides textManager, content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun copySelectionOrNull(): (() -> Unit)? {
    val copy = LocalTimelineTextManager.current?.copy ?: return null
    return copy.execute.takeIf { copy.enabled }
}
