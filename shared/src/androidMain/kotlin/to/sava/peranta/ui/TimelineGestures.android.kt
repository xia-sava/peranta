package to.sava.peranta.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Android では長押しでコンテキストメニューを開く（§10.1）。 */
actual fun Modifier.timelineContextGesture(enabled: Boolean, onOpenMenu: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        this.pointerInput(Unit) {
            detectTapGestures(onLongPress = { onOpenMenu() })
        }
    }
