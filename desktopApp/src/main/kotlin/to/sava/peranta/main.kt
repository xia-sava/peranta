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
import to.sava.peranta.autostart.AutoStartManager
import to.sava.peranta.autostart.DesktopHealthChecker
import to.sava.peranta.autostart.WindowsRunRegistry
import to.sava.peranta.pairing.pairingQrMatrix
import to.sava.peranta.platform.initLogging
import to.sava.peranta.ui.AppFilterScreen
import to.sava.peranta.ui.HealthCheckScreen
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

fun main(args: Array<String>) {
    initLogging()
    val log = Logger.withTag("Main")
    val desktopSettings = DesktopSettings()
    val config = desktopSettings.config
    val settingsController = desktopSettings.controller

    // ログオン自動起動から --minimized で起動された場合はウィンドウを出さずトレイ常駐で開始する（§3.3）。
    val startMinimized = args.contains(AutoStartManager.MINIMIZED_ARGUMENT)
    // jpackage.app-path が無い開発実行では自動起動を扱わない（java 起動コマンドの誤登録を防ぐ）。
    val autoStart = AutoStartManager(WindowsRunRegistry(), System.getProperty("jpackage.app-path"))
    autoStart.reconcile()

    val mainWindow = AtomicReference<AwtWindow?>(null)
    val showWindowRequest = AtomicReference<() -> Unit>({})
    val bringToFront: () -> Unit = {
        showWindowRequest.get().invoke()
        mainWindow.get()?.let(::bringWindowToFront)
    }
    val receiver = if (config.isReadyForReceive) {
        DesktopReceiver(
            config,
            repository = desktopSettings.repository,
            onToastClicked = bringToFront,
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

        var windowVisible by remember { mutableStateOf(!startMinimized) }
        var showSettings by remember { mutableStateOf(receiver == null) }
        var showAppFilter by remember { mutableStateOf(false) }
        var showHealthCheck by remember { mutableStateOf(false) }

        // トースト経由など Compose 外からの「ウィンドウを出す」要求を可視状態へ橋渡しする。
        LaunchedEffect(Unit) { showWindowRequest.set { windowVisible = true } }

        Tray(
            icon = perantaIcon,
            tooltip = "Peranta",
            onAction = bringToFront,
            menu = {
                Item("設定", onClick = {
                    showSettings = true
                    bringToFront()
                })
                Item("健康診断", onClick = {
                    showHealthCheck = true
                    bringToFront()
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
            visible = windowVisible,
            icon = perantaIcon,
            title = "Peranta",
        ) {
            LaunchedEffect(window) { mainWindow.set(window) }
            when {
                showHealthCheck -> PerantaTheme {
                    HealthCheckScreen(
                        checker = DesktopHealthChecker(autoStart),
                        onBack = { showHealthCheck = false },
                    )
                }
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
                showAppFilter && receiver != null -> PerantaTheme {
                    AppFilterScreen(
                        controller = receiver.appFilterController(),
                        items = receiver.items,
                        onBack = { showAppFilter = false },
                    )
                }
                errorMessage != null -> App(errorMessage!!)
                receiver != null -> App(
                    items = receiver.items,
                    updateController = updater.controller,
                    onInstallUpdate = { url -> updater.install(url) },
                    onOpenSettings = { showSettings = true },
                    onOpenAppFilter = { showAppFilter = true },
                    onOpenHealthCheck = { showHealthCheck = true },
                    timelineActions = receiver.timelineActions(),
                    attachmentUi = receiver.attachmentUi(),
                )
                else -> App()
            }
        }
    }
}
