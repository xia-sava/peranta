package to.sava.peranta

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.pairing.pairingQrMatrix
import to.sava.peranta.platform.initLogging
import to.sava.peranta.ui.PerantaTheme
import to.sava.peranta.ui.SettingsScreen
import to.sava.peranta.update.DesktopUpdater
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

/** ペアリング URI を QR として描画する。commonMain の設定画面へスロットとして注入する。 */
@Composable
private fun DesktopQrCode(uri: String, modifier: Modifier = Modifier) {
    val matrix = remember(uri) { pairingQrMatrix(uri) }
    Canvas(modifier = modifier.size(240.dp)) {
        val moduleSize = size.minDimension / matrix.size
        drawRect(color = Color.White, size = Size(matrix.size * moduleSize, matrix.size * moduleSize))
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * moduleSize, y * moduleSize),
                        size = Size(moduleSize, moduleSize),
                    )
                }
            }
        }
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
    val desktopSettings = DesktopSettings()
    val config = desktopSettings.config
    val settingsController = desktopSettings.controller

    val mainWindow = AtomicReference<AwtWindow?>(null)
    val receiver = if (config.isReadyForReceive) {
        DesktopReceiver(
            config,
            onToastClicked = { mainWindow.get()?.let(::bringWindowToFront) },
        )
    } else {
        null
    }

    val updater = DesktopUpdater(config, DesktopVersion.versionCode)
    updater.checkAtStartup()

    application {
        val closeAndExit = {
            receiver?.close()
            updater.close()
            exitApplication()
        }

        var showSettings by remember { mutableStateOf(receiver == null) }

        Tray(
            icon = perantaIcon,
            tooltip = "Peranta",
            menu = {
                Item("設定", onClick = {
                    showSettings = true
                    mainWindow.get()?.let(::bringWindowToFront)
                })
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
                showSettings -> PerantaTheme {
                    SettingsScreen(
                        controller = settingsController,
                        qrContent = { uri -> DesktopQrCode(uri) },
                        onOpenTimeline = if (receiver != null) {
                            { showSettings = false }
                        } else {
                            null
                        },
                        devMode = desktopSettings.devMode,
                    )
                }
                errorMessage != null -> App(errorMessage!!)
                receiver != null -> App(
                    items = receiver.items,
                    updateController = updater.controller,
                    onInstallUpdate = { url -> updater.install(url) },
                    onOpenSettings = { showSettings = true },
                    timelineActions = receiver.timelineActions(),
                )
                else -> App()
            }
        }
    }
}
