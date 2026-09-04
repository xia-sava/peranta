package to.sava.peranta.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Desktop ではメニューボタンに加えて右クリックでもコンテキストメニューを開ける（§10.1）。
 * 押された位置を渡し、メニューがそこへ出るようにする。
 * イベントは消費する。通すと、本文の選択機構が持つ右クリックメニューが重なって出る。
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.timelineContextGesture(enabled: Boolean, onOpenMenu: (position: Offset) -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        this.onPointerEvent(PointerEventType.Press) { event ->
            if (event.buttons.isSecondaryPressed) {
                val position = event.changes.firstOrNull()?.position
                event.changes.forEach { it.consume() }
                position?.let(onOpenMenu)
            }
        }
    }
