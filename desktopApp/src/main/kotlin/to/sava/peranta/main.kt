package to.sava.peranta

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import to.sava.peranta.autostart.AutoStartManager
import to.sava.peranta.autostart.DesktopHealthChecker
import to.sava.peranta.autostart.WindowsRunRegistry
import to.sava.peranta.pairing.pairingQrMatrix
import to.sava.peranta.platform.initLogging
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.ui.AppFilterScreen
import to.sava.peranta.ui.HealthCheckScreen
import to.sava.peranta.ui.PerantaTheme
import to.sava.peranta.ui.QrCodeCanvas
import to.sava.peranta.ui.SettingsScreen
import to.sava.peranta.update.DesktopUpdater
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.datatransfer.StringSelection
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
    QrCodeCanvas(matrix, modifier = modifier.size(240.dp))
}

/** 設定画面のスクロール位置に追従する縦スクロールバー。commonMain の設定画面へスロットとして注入する。 */
@Composable
private fun BoxScope.DesktopScrollbar(scrollState: ScrollState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.align(Alignment.CenterEnd),
    )
}

/** ペアリング文字列をシステムクリップボードにコピーする。 */
private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
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
    val updater = DesktopUpdater(desktopSettings.config, DesktopVersion.versionCode)
    updater.checkAtStartup()

    application {
        // 設定保存のたびに増やす世代番号。受信機は世代の変化で作り直される。
        var configGeneration by remember { mutableStateOf(0) }
        var receiver by remember { mutableStateOf<DesktopReceiver?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        // アプリ終了時は次世代の受信機が起動しないため JSONL 競合の懸念が無く、close 完了を待たずに投げる。
        val appScope = rememberCoroutineScope()
        val closeAndExit = {
            receiver?.let { appScope.launch { it.close() } }
            updater.close()
            exitApplication()
        }

        var windowVisible by remember { mutableStateOf(!startMinimized) }
        var showSettings by remember { mutableStateOf(!desktopSettings.config.isReadyForReceive) }
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

        // 世代の変化を collectLatest で直列化する。新しい世代が来ると旧受信機の run() を
        // キャンセルし、その close()（finally）完了を待ってから次の受信機を組み立てるため、
        // 新旧受信機の並行動作（JSONL 追記の競合・エラー表示の取りこぼし）が構造的に起きない。
        LaunchedEffect(Unit) {
            snapshotFlow { configGeneration }.collectLatest {
                errorMessage = null
                val freshConfig = withContext(ioDispatcher) { desktopSettings.reloadConfig() }
                // 構築（トースター初期化の同期 I/O）中に世代切替でキャンセルされても必ず close するため、
                // try は DesktopReceiver の構築から run() までを覆う。
                var newReceiver: DesktopReceiver? = null
                try {
                    if (freshConfig.isReadyForReceive) {
                        newReceiver = withContext(ioDispatcher) {
                            DesktopReceiver(
                                freshConfig,
                                repository = desktopSettings.repository,
                                onToastClicked = bringToFront,
                            )
                        }
                    }
                    receiver = newReceiver
                    val started = newReceiver ?: return@collectLatest
                    started.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(e) { "receiver stopped with error" }
                    errorMessage = "受信中にエラーが発生しました。設定を確認してください。"
                } finally {
                    newReceiver?.let { withContext(NonCancellable) { it.close() } }
                }
            }
        }

        Window(
            onCloseRequest = closeAndExit,
            visible = windowVisible,
            icon = perantaIcon,
            title = "Peranta",
        ) {
            LaunchedEffect(window) { mainWindow.set(window) }
            val currentReceiver = receiver
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
                        onOpenTimeline = if (currentReceiver != null) {
                            { showSettings = false }
                        } else {
                            null
                        },
                        scrollbarContent = { scrollState -> DesktopScrollbar(scrollState) },
                        onCopyPairingUri = ::copyToClipboard,
                        onSaved = { configGeneration++ },
                    )
                }
                showAppFilter && currentReceiver != null -> PerantaTheme {
                    AppFilterScreen(
                        controller = currentReceiver.appFilterController(),
                        items = currentReceiver.items,
                        onBack = { showAppFilter = false },
                    )
                }
                errorMessage != null -> App(errorMessage!!)
                currentReceiver != null -> App(
                    items = currentReceiver.items,
                    updateController = updater.controller,
                    onInstallUpdate = { url -> updater.install(url) },
                    onOpenSettings = { showSettings = true },
                    onOpenAppFilter = { showAppFilter = true },
                    onOpenHealthCheck = { showHealthCheck = true },
                    timelineActions = currentReceiver.timelineActions(),
                    attachmentUi = currentReceiver.attachmentUi(),
                    fullTextUi = currentReceiver.fullTextUi(),
                )
                else -> App()
            }
        }
    }
}
