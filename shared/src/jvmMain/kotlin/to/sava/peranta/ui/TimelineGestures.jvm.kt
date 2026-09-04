package to.sava.peranta.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.onClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton

/** Desktop ではメニューボタンに加えて右クリックでもコンテキストメニューを開ける（§10.1）。 */
@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.timelineContextGesture(enabled: Boolean, onOpenMenu: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        this.onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) { onOpenMenu() }
    }
