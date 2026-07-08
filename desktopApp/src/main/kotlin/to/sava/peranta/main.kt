package to.sava.peranta

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.platform.initLogging
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window as AwtWindow
import java.util.concurrent.atomic.AtomicReference

/** トレイ・ウィンドウ用の簡易アイコン。 */
private val perantaIcon: Painter = object : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawRoundRect(color = Color(0xFF4C6EF5), cornerRadius = CornerRadius(14f, 14f))
        drawCircle(color = Color.White, radius = size.minDimension / 4f)
    }
}

/** メインウィンドウをアクティブ化し、最前面に出す（トーストクリック導線で使う）。EDT 上で実行する。 */
private fun bringWindowToFront(window: AwtWindow) {
    EventQueue.invokeLater {
        if (window is Frame && window.extendedState and Frame.ICONIFIED != 0) {
            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
        }
        window.isVisible = true
        window.toFront()
        window.requestFocus()
    }
}

fun main() {
    initLogging()
    val log = Logger.withTag("Main")
    val config = loadDesktopConfig()

    val mainWindow = AtomicReference<AwtWindow?>(null)
    val receiver = if (config.isReadyForReceive) {
        DesktopReceiver(
            config,
            onToastClicked = { mainWindow.get()?.let(::bringWindowToFront) },
        )
    } else {
        null
    }

    application {
        val closeAndExit = {
            receiver?.close()
            exitApplication()
        }

        Tray(
            icon = perantaIcon,
            tooltip = "Peranta",
            menu = {
                Item("終了", onClick = closeAndExit)
            },
        )

        var errorMessage by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(receiver) {
            if (receiver == null) return@LaunchedEffect
            try {
                receiver.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "receiver stopped with error" }
                errorMessage = "受信中にエラーが発生しました。設定を確認してください。"
            }
        }

        Window(
            onCloseRequest = closeAndExit,
            icon = perantaIcon,
            title = "Peranta",
        ) {
            LaunchedEffect(window) { mainWindow.set(window) }
            when {
                errorMessage != null -> App(errorMessage!!)
                receiver != null -> App(items = receiver.items)
                else -> App()
            }
        }
    }
}
