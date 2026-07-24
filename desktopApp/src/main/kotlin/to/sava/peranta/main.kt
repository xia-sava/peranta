package to.sava.peranta

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import to.sava.peranta.autostart.AutoStartManager
import to.sava.peranta.autostart.AutoStartStatus
import to.sava.peranta.autostart.DesktopHealthChecker
import to.sava.peranta.autostart.WindowsRunRegistry
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.pairing.pairingQrMatrix
import to.sava.peranta.platform.initLogging
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.platform.platformCapabilities
import to.sava.peranta.ui.AppFilterScreen
import to.sava.peranta.ui.HealthCheckScreen
import to.sava.peranta.ui.MessageComposer
import to.sava.peranta.ui.PairingScanScreen
import to.sava.peranta.ui.PerantaTheme
import to.sava.peranta.ui.QrCodeCanvas
import to.sava.peranta.ui.SettingsScreen
import to.sava.peranta.ui.failingHealthCheckIds
import to.sava.peranta.ui.setup.SetupAction
import to.sava.peranta.ui.setup.SetupItemUi
import to.sava.peranta.ui.setup.SetupItemsProvider
import to.sava.peranta.ui.setup.SetupStatus
import to.sava.peranta.ui.setup.WizardFlow
import to.sava.peranta.ui.setup.WizardScreen
import to.sava.peranta.ui.shell.PerantaShell
import to.sava.peranta.ui.shell.RosterDropdown
import to.sava.peranta.ui.shell.SetupWarningBanner
import to.sava.peranta.ui.shell.ShellDestination
import to.sava.peranta.ui.shell.setupBannerTarget
import to.sava.peranta.ui.shell.shellNavigate
import to.sava.peranta.ui.shell.shellReturnDestination
import to.sava.peranta.update.DesktopUpdater
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

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

/** ダーク/ライト両テーマで視認できるよう、onSurface 由来の色で明示したスクロールバー配色。 */
@Composable
private fun desktopScrollbarStyle(): ScrollbarStyle {
    val onSurface = MaterialTheme.colorScheme.onSurface
    return defaultScrollbarStyle().copy(
        unhoverColor = onSurface.copy(alpha = 0.3f),
        hoverColor = onSurface.copy(alpha = 0.6f),
    )
}

/** スクロール位置に追従する縦スクロールバー。commonMain の設定画面・ウィザードへスロットとして注入する。 */
@Composable
private fun BoxScope.DesktopScrollbar(scrollState: ScrollState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.align(Alignment.CenterEnd),
        style = desktopScrollbarStyle(),
    )
}

/** リスト位置に追従する縦スクロールバー（LazyColumn 版）。commonMain のリスト画面へスロットとして注入する。 */
@Composable
private fun BoxScope.DesktopScrollbar(listState: LazyListState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = Modifier.align(Alignment.CenterEnd),
        style = desktopScrollbarStyle(),
    )
}

/**
 * ウィザードの自動起動ページへ渡す項目供給元。[AutoStartManager] を直接使い、
 * 配布物でない開発実行（NOT_SUPPORTED）は扱えないため自動的に DONE 扱いにする。
 */
private fun desktopWizardSetupProvider(autoStart: AutoStartManager): SetupItemsProvider =
    SetupItemsProvider {
        val status = autoStart.status()
        listOf(
            SetupItemUi(
                id = WizardFlow.ITEM_AUTOSTART,
                title = "ログオン時の自動起動",
                description = "サインイン後すぐに受信を始められるよう、ログオン時にトレイ常駐で自動起動します。",
                status = if (status == AutoStartStatus.DISABLED) SetupStatus.TODO else SetupStatus.DONE,
                statusDetail = when (status) {
                    AutoStartStatus.NOT_SUPPORTED -> "この実行環境では自動起動を設定できません。"
                    AutoStartStatus.ENABLED -> "サインイン時にトレイ常駐で自動起動します。"
                    AutoStartStatus.DISABLED -> null
                },
                action = if (status == AutoStartStatus.DISABLED) {
                    SetupAction(
                        label = "登録する",
                        run = {
                            if (!autoStart.enable()) {
                                Logger.withTag("Wizard").w { "自動起動の登録に失敗しました。" }
                            }
                        },
                    )
                } else {
                    null
                },
            ),
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

/** 終了時に受信機の close（JSONL 書き込みの完了）を待つ上限。 */
private const val RECEIVER_CLOSE_TIMEOUT_MILLIS: Long = 2_000L

fun main(args: Array<String>) {
    initLogging()
    val log = Logger.withTag("Main")
    val desktopSettings = DesktopSettings()
    val settingsController = desktopSettings.controller
    // QR 参加経路（貼り付け取り込み）。カメラは無いため onRequestScan は注入せず貼り付けのみで動く。
    val pairingImportController = PairingImportController(desktopSettings.repository)

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
    // トースト本体クリックでウィンドウを前面化したうえ、タイムラインの元アイテムまでスクロールする
    // 要求を橋渡しする（トースト経由の呼び出しは Compose 外のスレッドから起きるため）。
    val scrollToItemRequest = AtomicReference<(itemId: String) -> Unit>({})
    val bringToFrontAndScrollToItem: (itemId: String) -> Unit = { itemId ->
        bringToFront()
        scrollToItemRequest.get().invoke(itemId)
    }
    val updater = DesktopUpdater(desktopSettings.config, DesktopVersion.versionCode)

    application {
        // 設定保存のたびに増やす世代番号。受信機は世代の変化で作り直される。
        var configGeneration by remember { mutableStateOf(0) }
        var receiver by remember { mutableStateOf<DesktopReceiver?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        // エラー停止した受信機（run() が例外終了しても receiver 変数には残る）を除外して渡す。
        // 稼働中でない受信機を渡すと受信テストが必ず Timeout になり誤診断になるため（§10.5）。
        val selfTestProvider: () -> DesktopSelfTest? = { receiver.takeIf { errorMessage == null } }

        // 終了時は受信機の close（JSONL 書き込みの完了）を上限つきで待ってからアプリを閉じる。
        val appScope = rememberCoroutineScope()
        val closeAndExit = {
            appScope.launch {
                receiver?.let { withTimeoutOrNull(RECEIVER_CLOSE_TIMEOUT_MILLIS) { it.close() } }
                updater.close()
                exitApplication()
            }
            Unit
        }

        var windowVisible by remember { mutableStateOf(!startMinimized) }
        // 初回起動（未ペアリング）はウィザードを自動で開始する。ウィザードは画面シェルの外に置く。
        var showWizard by remember { mutableStateOf(!desktopSettings.config.hasSharedKey) }
        // 画面シェル内の現在地。
        var destination by remember { mutableStateOf(ShellDestination.Timeline) }
        // 設定サブ画面（受信のセットアップ・動作チェック・接続設定と暗号キーの取り込み）へ入ったときの
        // 遷移元を1段だけ覚え、戻る操作をその画面へ戻す（§10.0）。destination と同じ場所に保持する。
        var subScreenOrigin by remember { mutableStateOf<ShellDestination?>(null) }
        val windowState = rememberWindowState()

        // タイムラインを表示するたびに動作チェックを実行し、対処の要る未達があればタイムライン上部の
        // 警告バナーの誘導先を確定する（§10.5）。取得が済むまで・未達が無いときは null でバナーを出さない。
        // 誘導先の画面で未達を直して戻ったときにバナーが実態へ追従するよう、表示のたびに再評価する。
        var bannerTarget by remember { mutableStateOf<ShellDestination?>(null) }
        LaunchedEffect(destination, showWizard) {
            if (!showWizard && destination == ShellDestination.Timeline &&
                withContext(ioDispatcher) { desktopSettings.reloadConfig() }.hasSharedKey
            ) {
                bannerTarget = setupBannerTarget(
                    failingHealthCheckIds(DesktopHealthChecker(autoStart, selfTestProvider).check()),
                )
            }
        }

        // シェル内の遷移を一元化する。設定・取り込みを離れるときは遷移先に依らず設定反映（受信機の
        // 再生成）を通し（§10.2）、反映後も遷移先を保つため先に遷移先を確定してから世代を進める。
        val onNavigate: (ShellDestination) -> Unit = { target ->
            val nav = shellNavigate(from = destination, to = target)
            destination = nav.destination
            subScreenOrigin = nav.subScreenOrigin
            if (nav.reflectSettings) configGeneration++
        }

        // トースト経由など Compose 外からの「ウィンドウを出す」要求を可視状態へ橋渡しする。
        LaunchedEffect(Unit) { showWindowRequest.set { windowVisible = true } }

        // トースト本体クリックで届いたスクロール先アイテム id。タイムライン表示中に消費されると null に戻り、
        // 同じアイテムを続けてクリックしてもスクロールし直せる。
        var pendingScrollItemId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { scrollToItemRequest.set { itemId -> pendingScrollItemId = itemId } }

        // 最小化はタスクバーでなくトレイへ格納する。復帰時に備えて最小化フラグは戻しておく。
        LaunchedEffect(Unit) {
            snapshotFlow { windowState.isMinimized }.collectLatest { minimized ->
                if (minimized) {
                    windowVisible = false
                    windowState.isMinimized = false
                }
            }
        }

        PerantaTray(
            onActivate = bringToFront,
            onOpenSettings = {
                onNavigate(ShellDestination.Settings)
                bringToFront()
            },
            onExit = closeAndExit,
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
                                onToastClicked = bringToFrontAndScrollToItem,
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

        // トレイ常駐アプリの作法に合わせ、×はトレイ格納にする。終了はトレイメニューから行う。
        Window(
            onCloseRequest = { windowVisible = false },
            state = windowState,
            visible = windowVisible,
            icon = perantaIcon,
            title = "Peranta",
        ) {
            LaunchedEffect(window) { mainWindow.set(window) }
            val currentReceiver = receiver
            // アプリバー・ドロワーのラベルは config 由来（ネットワーク不要）。受信機があればその設定を、
            // 無ければ起動時設定を使う。
            val labelConfig = currentReceiver?.config ?: desktopSettings.config
            if (showWizard) {
                PerantaTheme {
                    WizardScreen(
                        caps = platformCapabilities(),
                        controller = settingsController,
                        provider = desktopWizardSetupProvider(autoStart),
                        healthChecker = DesktopHealthChecker(autoStart, selfTestProvider),
                        importController = pairingImportController,
                        qrContent = { uri -> DesktopQrCode(uri) },
                        onCopyPairingUri = ::copyToClipboard,
                        scrollbarContent = { scrollState -> DesktopScrollbar(scrollState) },
                        onClose = { showWizard = false },
                        onSaved = { configGeneration++ },
                    )
                }
            } else {
                PerantaTheme {
                    PerantaShell(
                        destination = destination,
                        onNavigate = onNavigate,
                        backDestination = shellReturnDestination(destination, subScreenOrigin),
                        serverLabel = labelConfig.host.takeIf { it.isNotBlank() },
                        deviceLabel = labelConfig.deviceName?.takeIf { it.isNotBlank() },
                        serverTrailing = currentReceiver?.rosterUi()?.let { rosterUi ->
                            { RosterDropdown(rosterUi) }
                        },
                    ) { shellDestination ->
                        when (shellDestination) {
                            ShellDestination.Timeline -> Column(modifier = Modifier.fillMaxSize()) {
                                bannerTarget?.let { target ->
                                    SetupWarningBanner(onConfirm = { onNavigate(target) })
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    when {
                                        errorMessage != null -> App(errorMessage!!)
                                        currentReceiver != null -> App(
                                            items = currentReceiver.items,
                                            timelineActions = currentReceiver.timelineActions(),
                                            attachmentUi = currentReceiver.attachmentUi(),
                                            fullTextUi = currentReceiver.fullTextUi(),
                                            lazyScrollbarContent = { listState -> DesktopScrollbar(listState) },
                                            scrollToItemId = pendingScrollItemId,
                                            onScrollToItemHandled = { pendingScrollItemId = null },
                                        )
                                        else -> App()
                                    }
                                }
                                currentReceiver?.composerUi()?.let { MessageComposer(it, sendOnEnter = true) }
                            }

                            ShellDestination.Settings -> SettingsScreen(
                                controller = settingsController,
                                qrContent = { uri -> DesktopQrCode(uri) },
                                scrollbarContent = { scrollState -> DesktopScrollbar(scrollState) },
                                onCopyPairingUri = ::copyToClipboard,
                                onOpenWizard = { showWizard = true },
                                loadHealthItems = { DesktopHealthChecker(autoStart, selfTestProvider).check() },
                                onOpenHealthCheck = { onNavigate(ShellDestination.HealthCheck) },
                                onOpenPairingImport = { onNavigate(ShellDestination.PairingImport) },
                                updateController = updater.controller,
                                onInstallUpdate = { url -> updater.install(url) },
                                // 鍵の作成は例外として即時反映する（§10.2）。画面を離れたときの反映は onNavigate が担う。
                                onSaved = { configGeneration++ },
                                showHeader = false,
                            )

                            ShellDestination.AppFilter -> when {
                                currentReceiver != null -> AppFilterScreen(
                                    controller = currentReceiver.appFilterController(),
                                    items = currentReceiver.items,
                                    showHeader = false,
                                    lazyScrollbarContent = { listState -> DesktopScrollbar(listState) },
                                )
                                errorMessage != null -> App(errorMessage!!)
                                else -> App()
                            }

                            ShellDestination.HealthCheck -> HealthCheckScreen(
                                checker = DesktopHealthChecker(autoStart, selfTestProvider),
                                showHeader = false,
                                scrollbarContent = { scrollState -> DesktopScrollbar(scrollState) },
                            )

                            ShellDestination.PairingImport -> PairingScanScreen(
                                controller = pairingImportController,
                                onImported = { destination = ShellDestination.Timeline; configGeneration++ },
                                showHeader = false,
                                showDescription = true,
                                scrollbarContent = { scrollState -> DesktopScrollbar(scrollState) },
                            )

                            // Desktop に受信のセットアップ画面は無い（セットアップ状況にも受信経路の行を出さない）。
                            ShellDestination.ReceiveSetup -> App()
                        }
                    }
                }
            }
        }
    }

    // トレイ（AWT）関連の非デーモンスレッドが残って JVM が終了しないことがあるため、明示的に終える。
    exitProcess(0)
}
