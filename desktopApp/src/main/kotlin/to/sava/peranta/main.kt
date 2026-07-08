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

/** トレイ・ウィンドウ用の簡易アイコン。 */
private val perantaIcon: Painter = object : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawRoundRect(color = Color(0xFF4C6EF5), cornerRadius = CornerRadius(14f, 14f))
        drawCircle(color = Color.White, radius = size.minDimension / 4f)
    }
}

fun main() {
    initLogging()
    val log = Logger.withTag("Main")
    val config = loadDesktopConfig()
    val receiver = if (config.isReadyForReceive) DesktopReceiver(config) else null

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
            when {
                errorMessage != null -> App(errorMessage!!)
                receiver != null -> App(receiver.items)
                else -> App()
            }
        }
    }
}
