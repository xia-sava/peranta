package to.sava.peranta

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import to.sava.peranta.android.AndroidAttachmentActions
import to.sava.peranta.android.AndroidAttachmentReceive
import to.sava.peranta.android.AndroidHealthChecker
import to.sava.peranta.android.AndroidInstalledAppsProvider
import to.sava.peranta.android.AndroidReceiveSetupProvider
import to.sava.peranta.android.AndroidSetupProbe
import to.sava.peranta.android.AndroidWizardSetupProvider
import to.sava.peranta.android.AttachmentTransferService
import to.sava.peranta.android.CompanionAssociation
import to.sava.peranta.android.EXTRA_SCROLL_ITEM_ID
import to.sava.peranta.android.PerantaReceive
import to.sava.peranta.android.PerantaSend
import to.sava.peranta.android.PerantaUnifiedPush
import to.sava.peranta.android.androidConfigRepository
import to.sava.peranta.android.normalizeScrollItemId
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.platform.platformCapabilities
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.pairing.pairingQrMatrix
import to.sava.peranta.send.MESSAGE_SEND_FAILED_MESSAGE
import to.sava.peranta.send.sharedStreamItems
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.AppFilterScreen
import to.sava.peranta.ui.AttachmentUi
import to.sava.peranta.ui.DEFAULT_EMPTY_TIMELINE_MESSAGE
import to.sava.peranta.ui.HealthCheckScreen
import to.sava.peranta.ui.failingHealthCheckIds
import to.sava.peranta.ui.MessageComposer
import to.sava.peranta.ui.MessageComposerUi
import to.sava.peranta.ui.PairingScanScreen
import to.sava.peranta.ui.PerantaTheme
import to.sava.peranta.ui.SettingsScreen
import to.sava.peranta.ui.ShareScreen
import to.sava.peranta.ui.UpdateUi
import to.sava.peranta.ui.ZoomableQrCode
import to.sava.peranta.ui.setup.ReceiveSetupScreen
import to.sava.peranta.ui.shell.PerantaShell
import to.sava.peranta.ui.shell.RosterDropdown
import to.sava.peranta.ui.shell.SetupWarningBanner
import to.sava.peranta.ui.shell.ShellDestination
import to.sava.peranta.ui.shell.setupBannerTarget
import to.sava.peranta.ui.shell.shellNavigate
import to.sava.peranta.ui.shell.shellReturnDestination
import to.sava.peranta.ui.setup.WizardScreen
import to.sava.peranta.update.AndroidUpdater

/** 通知権限が拒否されたときにタイムラインへ出す文言（§10.5）。 */
private const val NOTIFICATIONS_DENIED_MESSAGE =
    "通知の権限が許可されていません。受信した通知は表示されません"

/** カメラ権限が拒否されたときに案内する文言（手動貼り付けへのフォールバックを明示する）。 */
private const val CAMERA_DENIED_MESSAGE =
    "カメラの権限が許可されていません。ペアリング文字列を貼り付けて取り込んでください"

/** コンパニオン機器の登録をユーザーが取りやめたときの文言。 */
private const val COMPANION_CANCELLED_MESSAGE =
    "登録しなかったため、一部の通知は本文を受け取れません"

/** コンパニオン機器の一覧を出せなかったときの文言。 */
private const val COMPANION_FAILED_MESSAGE =
    "機器の一覧を取得できませんでした。Bluetooth を有効にしてからもう一度お試しください"

/** コンパニオン機器の登録に対応していない端末で出す文言。 */
private const val COMPANION_UNAVAILABLE_MESSAGE =
    "この端末はコンパニオン機器の登録に対応していません"

/** 受信設定が未完了の端末で空のタイムラインに出す文言（設定の完了を促す）。 */
private const val RECEIVE_NOT_READY_MESSAGE =
    "受信の設定が完了すると通知が表示されます。設定から続きを行ってください"

/** SAF 保存要求中の blobId を Activity 再生成越しに引き継ぐための保存キー。 */
private const val KEY_PENDING_SAVE_BLOB_ID = "pendingSaveBlobId"

/**
 * 画面シェルの外にあるモーダルなタスク（§10.7）。null のときは [PerantaShell] を表示する。
 * [Wizard] は設定・診断をページ列で案内するウィザード、[PairingLanding] は未ペアリング端末の
 * 取り込み待機画面、[Share] は共有シートから渡されたファイルの送信画面を出す。
 */
private sealed interface Overlay {
    data object Wizard : Overlay
    data object PairingLanding : Overlay
    data class Share(val files: List<Uri>, val text: String?) : Overlay
}

/** [Overlay] の保存表現。どのタスクを開いていたかだけを識別する。 */
private const val OVERLAY_NONE = "none"
private const val OVERLAY_WIZARD = "wizard"
private const val OVERLAY_PAIRING_LANDING = "pairingLanding"
private const val OVERLAY_SHARE = "share"

/**
 * 開いているモーダルなタスクを Activity 再生成越しに保つ Saver。共有のファイル一覧は起動 Intent から
 * 作り直せるため、保存するのはどのタスクかだけにして [share] で復元する。
 */
private fun overlaySaver(share: Overlay.Share?): Saver<Overlay?, String> = Saver(
    save = { overlay ->
        when (overlay) {
            Overlay.Wizard -> OVERLAY_WIZARD
            Overlay.PairingLanding -> OVERLAY_PAIRING_LANDING
            is Overlay.Share -> OVERLAY_SHARE
            null -> OVERLAY_NONE
        }
    },
    restore = { saved ->
        when (saved) {
            OVERLAY_WIZARD -> Overlay.Wizard
            OVERLAY_PAIRING_LANDING -> Overlay.PairingLanding
            OVERLAY_SHARE -> share
            else -> null
        }
    },
)

class MainActivity : ComponentActivity() {

    private var updater: AndroidUpdater? = null

    /**
     * 画面復帰（ON_RESUME）ごとに進めるカウンタ。動作チェック画面はこれを再チェックの契機にし、
     * システム設定から戻った直後の権限・設定状態を反映する（§10.5）。
     */
    private var resumeTick by mutableStateOf(0)

    /** スキャン結果の受け取り先。スキャン開始のたびに差し替える（キャンセル時は null が渡る）。 */
    private var pendingScanResult: ((String?) -> Unit)? = null

    /**
     * ミラー通知タップで渡された、タイムラインの対象アイテム id（§3.2）。タイムライン表示時に
     * 一度だけ消費され、消費後は null に戻る（Desktop の pendingScrollItemId と同型）。
     */
    private var pendingScrollItemId by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) reportNotificationsDenied()
        }

    private val scanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val callback = pendingScanResult
            pendingScanResult = null
            callback?.invoke(result.contents)
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchScanner()
            } else {
                Toast.makeText(this, CAMERA_DENIED_MESSAGE, Toast.LENGTH_LONG).show()
                pendingScanResult?.invoke(null)
                pendingScanResult = null
            }
        }

    /** 受信添付の「開く」「保存」「共有」を担う。受信設定が揃ったときだけ生成する（§4.3）。 */
    private var attachmentActions: AndroidAttachmentActions? = null

    /** 添付保存（SAF）のドキュメント作成ランチャー。返った Uri へキャッシュからコピーする。 */
    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val actions = attachmentActions ?: return@registerForActivityResult
            lifecycleScope.launch { actions.copyToDocument(uri) }
        }

    /**
     * コンパニオン機器の登録ダイアログのランチャー。登録できたら通知リスナーを張り直す
     * （信頼済みかの判定はバインド時に効くため、張り直すまで通知の本文は伏せられたままになる）。
     */
    private val companionAssociationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                CompanionAssociation.rebindNotificationListener(this)
            } else {
                Toast.makeText(this, COMPANION_CANCELLED_MESSAGE, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingScrollItemId = consumeScrollItemId(intent)

        val config = androidConfigRepository().load()
        val updater = AndroidUpdater(this, currentVersionCode()).also { this.updater = it }

        val importController = PairingImportController(androidConfigRepository())
        // コマンド受信のための UnifiedPush 登録は役割を問わない（送信ロールのスマホも受け口を持つ、§3.4）。
        if (config.isReadyForUnifiedPushReceive) {
            PerantaUnifiedPush.register(this)
        }
        // 受信タイムライン・添付表示・通知権限は受信設定が揃えば送信の可否を問わず有効化する（§10）。
        val canReceive = config.isReadyForUnifiedPushReceive
        val attachmentUi = if (canReceive) {
            attachmentActions = buildAttachmentActions(config).also {
                it.restorePendingSaveState(savedInstanceState?.getString(KEY_PENDING_SAVE_BLOB_ID))
            }
            AndroidAttachmentReceive.attachmentUi(this, attachmentActions!!, autoDisplayImages = config.autoDisplayImages)
        } else {
            null
        }
        if (canReceive) {
            requestNotificationsPermissionIfNeeded()
            lifecycleScope.launch { PerantaReceive.prime(this@MainActivity) }
            primeAttachmentCache(config)
        }

        val receiveSetupProvider = AndroidReceiveSetupProvider(this)
        val wizardSetupProvider = AndroidWizardSetupProvider(this, ::requestCompanionAssociation)
        // 受信のセットアップは受信ロールの設定が揃うか、UnifiedPush 登録済みのとき使えるようにする。
        // 登録済みなら config が欠けても修復の作業台へ戻れるようにする。入口は設定画面のセットアップ状況の受信経路の行。
        val receiveSetupAvailable =
            config.isReadyForUnifiedPushReceive || AndroidSetupProbe(this).unifiedPushRegistered()
        val sharedFiles = extractSharedFiles(intent)
        val sharedText = extractSharedText(intent)
        val rosterUi = PerantaReceive.rosterUi(this)
        // composer は送信設定が揃っていれば端末の役割を問わず出す（deviceId は PerantaSend.sendMessage が
        // 確定するため不問、§4.4）。満たさなければ null（非表示。設定導線は既存の警告バナー等が担う）。
        val composerUi = if (config.isReadyForSend) {
            MessageComposerUi(send = { text -> PerantaSend.sendMessage(this, text) })
        } else {
            null
        }

        setContent {
            // 更新の適用（ダウンロード・照合・インストーラ起動）の進み具合（§12）。
            val updateInstallState by updater.installState.collectAsState()
            // シェル内の現在地。設定を離れる遷移でも再生成後に生存させるため rememberSaveable で保持する。
            var destination: ShellDestination by rememberSaveable { mutableStateOf(ShellDestination.Timeline) }
            // 設定サブ画面（受信のセットアップ・動作チェック・接続設定と暗号キーの取り込み）へ入ったときの
            // 遷移元を1段だけ覚え、戻る操作をその画面へ戻す（§10.0）。destination と同じ場所に保持する。
            var subScreenOrigin: ShellDestination? by rememberSaveable { mutableStateOf(null) }
            // シェル外のモーダルなタスク。未ペアリングはウィザードから始め、共有はファイル送信へ入る。
            // ただし取り込み画面への遷移が再生成をまたいで復元されたときは、未ペアリングでも
            // ウィザードを被せず取り込み画面をそのまま出す（明示的な取り込み操作を優先する）。
            // 開いているタスクは destination と同じく再生成後も生存させる（ウィザードの途中で
            // QR スキャナを開くと構成変更が起きるため、失うと最初からやり直しになる）。
            val share = remember {
                Overlay.Share(sharedFiles, sharedText)
                    .takeIf { sharedFiles.isNotEmpty() || sharedText != null }
            }
            var overlay: Overlay? by rememberSaveable(stateSaver = overlaySaver(share)) {
                mutableStateOf(
                    when {
                        share != null && config.hasSharedKey -> share
                        config.hasSharedKey -> null
                        destination == ShellDestination.PairingImport -> null
                        else -> Overlay.Wizard
                    },
                )
            }

            // 動作チェックの UnifiedPush 系項目は受信のセットアップ画面へ誘導する。onOpen で診断からその画面へ移す。
            val healthChecker = remember {
                AndroidHealthChecker(
                    context = this@MainActivity,
                    onOpenReceiveSetup = { destination = ShellDestination.ReceiveSetup },
                    onRequestCompanionAssociation = ::requestCompanionAssociation,
                )
            }

            // タイムラインを表示するたびに動作チェックを実行し、対処の要る未達があればタイムライン上部の
            // 警告バナーの誘導先を確定する（§10.5）。取得が済むまで・未達が無いときは null でバナーを出さない。
            // 誘導先の画面で未達を直して戻ったときにバナーが実態へ追従するよう、表示のたびに再評価する。
            // 未セットアップは初回ウィザードが最優先のため、シェル表示に入るときだけ取得する。
            var bannerTarget: ShellDestination? by remember { mutableStateOf(null) }
            LaunchedEffect(destination, overlay) {
                if (config.hasSharedKey && overlay == null && destination == ShellDestination.Timeline) {
                    bannerTarget = setupBannerTarget(failingHealthCheckIds(healthChecker.check()))
                }
            }

            // シェル内の遷移を一元化する。設定を離れるときは遷移先に依らず設定反映（受信パイプラインの
            // 再構築）を通し（§10.2）、反映後も遷移先を保つため先に遷移先を確定してから再生成する。
            val onNavigate: (ShellDestination) -> Unit = { target ->
                val nav = shellNavigate(from = destination, to = target)
                destination = nav.destination
                subScreenOrigin = nav.subScreenOrigin
                if (nav.reflectSettings) resetReceiveAndRecreate()
            }

            // 一連の設定・ペアリング作業を終えてタイムラインへ戻る後処理。遷移先をタイムラインに確定してから
            // 再生成し、最新設定を反映する。
            val onReturnToTimeline: () -> Unit = {
                destination = ShellDestination.Timeline
                overlay = null
                resetReceiveAndRecreate()
            }
            // ペアリング済みならタイムラインへ、未ペアリングならウィザード再開導線つきの待機画面へ着地する。
            val onWizardClose: () -> Unit = {
                if (androidConfigRepository().load().hasSharedKey) {
                    onReturnToTimeline()
                } else {
                    overlay = Overlay.PairingLanding
                }
            }
            val onPairingLandingBack: (() -> Unit)? = if (config.hasSharedKey) {
                onReturnToTimeline
            } else {
                null
            }
            val onShareCancel: () -> Unit = { finish() }

            // バックキーはタイムラインへ戻す。設定サブ画面（受信のセットアップ・動作チェック・接続設定と
            // 暗号キーの取り込み）では開いた元の画面（設定 or タイムライン）へ戻す（§10.0）。シェル外の
            // タスクは各タスク固有の後処理を通す。設定から戻る時は onNavigate 経由で設定反映を通し、
            // 既存の後処理の共有を崩さない。
            BackHandler(enabled = overlay != null || destination != ShellDestination.Timeline) {
                when (overlay) {
                    Overlay.Wizard -> onWizardClose()
                    Overlay.PairingLanding -> (onPairingLandingBack ?: { overlay = null })()
                    is Overlay.Share -> onShareCancel()
                    null -> onNavigate(shellReturnDestination(destination, subScreenOrigin))
                }
            }

            // システムバー（ステータスバー・ナビゲーションバー）と重ならないよう、全画面共通で安全領域の
            // 余白を入れる（enableEdgeToEdge によりコンテンツがシステムバー裏まで描くため）。没入型にはせず、
            // 背景色をシステムバー裏まで塗った上でコンテンツだけを内側へ寄せる。
            PerantaTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding(),
                ) {
                    when (val current = overlay) {
                        Overlay.Wizard -> WizardScreen(
                            caps = platformCapabilities(),
                            controller = SettingsController(androidConfigRepository()),
                            provider = wizardSetupProvider,
                            healthChecker = healthChecker,
                            importController = importController,
                            qrContent = { uri ->
                                ZoomableQrCode(pairingQrMatrix(uri))
                            },
                            onCopyPairingUri = { text -> copyPairingUri(text) },
                            onCopyText = { text, sensitive -> copyText(text, sensitive) },
                            onRequestScan = { onResult -> requestScan(onResult) },
                            externalRefreshKey = resumeTick,
                            onClose = onWizardClose,
                            onSaved = { rebuildReceivePipeline() },
                        )

                        Overlay.PairingLanding -> PairingScanScreen(
                            controller = importController,
                            onRequestScan = { onResult -> requestScan(onResult) },
                            onOpenSettings = {
                                overlay = null
                                destination = ShellDestination.Settings
                            },
                            onOpenWizard = { overlay = Overlay.Wizard },
                            onImported = onReturnToTimeline,
                            onBack = onPairingLandingBack,
                        )

                        is Overlay.Share -> {
                            val files = current.files
                            var sending by remember { mutableStateOf(false) }
                            ShareScreen(
                                itemCount = files.size,
                                initialText = current.text,
                                sending = sending,
                                onSend = { caption ->
                                    if (files.isNotEmpty()) {
                                        AttachmentTransferService.enqueueUpload(this@MainActivity, files, caption)
                                        finish()
                                    } else {
                                        lifecycleScope.launch {
                                            sending = true
                                            val ok = PerantaSend.sendMessage(this@MainActivity, caption.orEmpty())
                                            sending = false
                                            if (ok) {
                                                finish()
                                            } else {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    MESSAGE_SEND_FAILED_MESSAGE,
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                onCancel = onShareCancel,
                            )
                        }

                        null -> PerantaShell(
                            destination = destination,
                            onNavigate = onNavigate,
                            backDestination = shellReturnDestination(destination, subScreenOrigin),
                            serverLabel = config.host.takeIf { it.isNotBlank() },
                            deviceLabel = config.deviceName?.takeIf { it.isNotBlank() },
                            serverTrailing = rosterUi?.let { { RosterDropdown(it) } },
                        ) { shellDestination ->
                            when (shellDestination) {
                                ShellDestination.Timeline -> Column(modifier = Modifier.fillMaxSize()) {
                                    bannerTarget?.let { target ->
                                        SetupWarningBanner(onConfirm = { onNavigate(target) })
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        App(
                                            items = PerantaReceive.items,
                                            timelineActions = if (canReceive) {
                                                PerantaReceive.timelineActions(this@MainActivity)
                                            } else {
                                                null
                                            },
                                            attachmentUi = attachmentUi,
                                            fullTextUi = if (canReceive) {
                                                AndroidAttachmentReceive.fullTextUi(this@MainActivity, config)
                                            } else {
                                                null
                                            },
                                            emptyStateMessage = if (canReceive) {
                                                DEFAULT_EMPTY_TIMELINE_MESSAGE
                                            } else {
                                                RECEIVE_NOT_READY_MESSAGE
                                            },
                                            scrollToItemId = pendingScrollItemId,
                                            onScrollToItemHandled = { pendingScrollItemId = null },
                                        )
                                    }
                                    composerUi?.let { MessageComposer(it) }
                                }

                                ShellDestination.Settings -> SettingsScreen(
                                    controller = SettingsController(androidConfigRepository()),
                                    qrContent = { uri ->
                                        ZoomableQrCode(pairingQrMatrix(uri))
                                    },
                                    onCopyPairingUri = { text -> copyPairingUri(text) },
                                    showSendRoleOptions = true,
                                    onOpenWizard = { overlay = Overlay.Wizard },
                                    loadHealthItems = { healthChecker.check() },
                                    onOpenHealthCheck = { onNavigate(ShellDestination.HealthCheck) },
                                    onOpenPairingImport = { onNavigate(ShellDestination.PairingImport) },
                                    hasReceiveSetup = receiveSetupAvailable,
                                    loadReceiveSetupItems = if (receiveSetupAvailable) {
                                        suspend { receiveSetupProvider.items() }
                                    } else {
                                        null
                                    },
                                    onOpenReceiveSetup = if (receiveSetupAvailable) {
                                        { onNavigate(ShellDestination.ReceiveSetup) }
                                    } else {
                                        null
                                    },
                                    update = UpdateUi(
                                        controller = updater.controller,
                                        canUpdate = !isDebugBuild(),
                                        currentVersionName = currentVersionName(),
                                        installState = updateInstallState,
                                        onInstall = { available -> updater.install(available) },
                                    ),
                                    showHeader = false,
                                    onSaved = { rebuildReceivePipeline() },
                                )

                                // 捕捉端末（送信）はインストール済みアプリ一覧から転送フィルタを編集し、
                                // 受信端末は受信履歴のミラーから送信元ごとに mute する（§10.4）。
                                ShellDestination.AppFilter -> if (config.sendEnabled) {
                                    AppFilterScreen(
                                        controller = AppFilterController(androidConfigRepository()),
                                        installedAppsProvider = AndroidInstalledAppsProvider(this@MainActivity),
                                        showHeader = false,
                                    )
                                } else {
                                    AppFilterScreen(
                                        controller = PerantaReceive.appFilterController(this@MainActivity),
                                        items = PerantaReceive.items,
                                        showHeader = false,
                                    )
                                }

                                ShellDestination.HealthCheck -> HealthCheckScreen(
                                    checker = healthChecker,
                                    externalRefreshKey = resumeTick,
                                    onCopyText = { text, sensitive -> copyText(text, sensitive) },
                                    showHeader = false,
                                )

                                ShellDestination.ReceiveSetup -> ReceiveSetupScreen(
                                    provider = receiveSetupProvider,
                                    externalRefreshKey = resumeTick,
                                    onCopyText = { text, sensitive -> copyText(text, sensitive) },
                                    receiveEndpoint = config.unifiedPushEndpoint,
                                    showHeader = false,
                                )

                                ShellDestination.PairingImport -> PairingScanScreen(
                                    controller = importController,
                                    onRequestScan = { onResult -> requestScan(onResult) },
                                    onImported = onReturnToTimeline,
                                    showHeader = false,
                                    showDescription = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * ミラー通知タップで既存インスタンスが再利用されたとき（launchMode="singleTop"）に届く Intent
     * を受け取る。以後の [intent] 参照が最新の値を返すよう保持し直したうえで、対象アイテム id が
     * 載っていればタイムラインへのスクロール要求として反映する（§3.2）。載っていなければ、
     * 消費済みでない既存のスクロール要求をそのまま保つ。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeScrollItemId(intent)?.let { pendingScrollItemId = it }
    }

    /**
     * Intent からミラー通知タップの対象アイテム id を取り出す。以後の Activity 再生成（[recreate]）で
     * 同じ Intent から再度消費してしまわないよう、読み取った extra は Intent から取り除く（§3.2）。
     */
    private fun consumeScrollItemId(source: Intent?): String? {
        val itemId = normalizeScrollItemId(source?.getStringExtra(EXTRA_SCROLL_ITEM_ID))
        source?.removeExtra(EXTRA_SCROLL_ITEM_ID)
        return itemId
    }

    /** 共有シート（ACTION_SEND / ACTION_SEND_MULTIPLE）で渡されたファイル Uri を取り出す。単数/複数の両方に対応する。 */
    private fun extractSharedFiles(intent: Intent?): List<Uri> {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val multiple = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return sharedStreamItems(single, multiple)
    }

    /** 共有シート（ACTION_SEND / ACTION_SEND_MULTIPLE）で渡されたテキスト（EXTRA_TEXT）を取り出す。 */
    private fun extractSharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }

    /**
     * 受信添付の操作束を組む（§4.3）。blobId からタイムライン履歴の [AttachmentRef] を引き、
     * 復号済みキャッシュを FileProvider の Uri として開く/共有し、保存は SAF ランチャーへ委ねる。
     */
    private fun buildAttachmentActions(config: PerantaConfig): AndroidAttachmentActions =
        AndroidAttachmentActions(
            context = this,
            refFor = { blobId -> findAttachmentRef(blobId) },
            cachedFileFor = { ref -> AndroidAttachmentReceive.cache(this, config).cachedFile(ref) },
            launchSaveDocument = { fileName, _ -> createDocumentLauncher.launch(fileName) },
            reportError = { message -> reportTimelineError(message) },
        )

    /** タイムライン履歴から blobId に一致する添付参照を引く。未取得・履歴消失時は null。 */
    private fun findAttachmentRef(blobId: String): AttachmentRef? =
        PerantaReceive.items.value
            .filterIsInstance<ReceivedFile>()
            .flatMap { it.payload.attachments }
            .firstOrNull { it.blobId == blobId }

    /**
     * 起動時に添付キャッシュを剪定し、以後タイムラインの更新ごとに取得済み添付を「完了」状態へ反映する（§4.3）。
     * 剪定・キャッシュ走査・サムネイルデコードはブロッキング I/O とビットマップ処理を伴うため IO ディスパッチャで動かす。
     */
    private fun primeAttachmentCache(config: PerantaConfig) {
        lifecycleScope.launch(ioDispatcher) {
            runCatching { AndroidAttachmentReceive.cache(this@MainActivity, config).prune() }
                .onFailure { if (it is CancellationException) throw it }
            PerantaReceive.items.collect { items ->
                AndroidAttachmentReceive.primeCached(this@MainActivity, config, items)
            }
        }
    }

    private fun reportTimelineError(message: String) {
        lifecycleScope.launch {
            try {
                PerantaReceive.reportError(this@MainActivity, message)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // タイムラインへのエラー反映失敗は本体の動作を妨げない。
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    /**
     * SAF の保存ピッカーを開いている間に Activity が再生成されても保存対象を見失わないよう、
     * 保存要求中の blobId を退避する（結果 Uri が返ったときに正しい添付へ書き出すため、§4.3）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        attachmentActions?.pendingSaveState()?.let { outState.putString(KEY_PENDING_SAVE_BLOB_ID, it) }
    }

    /**
     * 受信パイプラインを破棄してから Activity を再生成する。プロセス内シングルトンの受信状態は
     * recreate だけでは更新されないため、最新設定を確実に反映させる導線で使う。
     */
    private fun resetReceiveAndRecreate() {
        lifecycleScope.launch {
            PerantaReceive.reset()
            recreate()
        }
    }

    /**
     * 鍵の作成・作り直しを受信パイプラインへ即時反映する（§10.2 の例外）。
     * Activity は作り直さないため、設定画面に表示中の QR は消えない。
     */
    private fun rebuildReceivePipeline() {
        lifecycleScope.launch { PerantaReceive.rebuildIfPipelineConfigChanged(this@MainActivity) }
    }

    /**
     * 任意のテキストをシステムクリップボードへコピーする（動作チェックの案内ダイアログのコピー導線・
     * 設定画面のペアリング文字列コピーで共用、§10.3/§10.5）。[sensitive] が真のときだけ、
     * Android 13+ でクリップボード履歴・プレビューから伏せる。
     */
    private fun copyText(text: String, sensitive: Boolean, label: String = "Peranta") {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    /**
     * ペアリング文字列をシステムクリップボードへコピーする（設定画面のコピー導線、§10.3）。
     * 共有鍵・トークンを含む機密情報のため常に伏せ字対象にする。
     */
    private fun copyPairingUri(text: String) = copyText(text, sensitive = true, label = "Peranta pairing")

    /** カメラ権限を確かめてから QR スキャナを起動する（§10.5: 起動時ではなく必要時に要求）。 */
    private fun requestScan(onResult: (String?) -> Unit) {
        pendingScanResult = onResult
        val granted = checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * コンパニオン機器の登録ダイアログを出す。システムが周囲の機器を探して一覧を出し、
     * ユーザーが選ぶと [companionAssociationLauncher] へ結果が返る。
     */
    private fun requestCompanionAssociation() {
        val manager = CompanionAssociation.manager(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || manager == null) {
            Toast.makeText(this, COMPANION_UNAVAILABLE_MESSAGE, Toast.LENGTH_LONG).show()
            return
        }
        manager.associate(
            CompanionAssociation.request(),
            mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    companionAssociationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    CompanionAssociation.rebindNotificationListener(this@MainActivity)
                }

                override fun onFailure(error: CharSequence?) {
                    Toast.makeText(this@MainActivity, COMPANION_FAILED_MESSAGE, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    private fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("ペアリング QR を枠内に合わせてください")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    /** Android 13+ で通知権限が未許可なら実行時に要求する。許可済みなら要求しない（§10.5）。 */
    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun reportNotificationsDenied() {
        lifecycleScope.launch {
            try {
                PerantaReceive.reportError(this@MainActivity, NOTIFICATIONS_DENIED_MESSAGE)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // 権限拒否のタイムライン反映失敗は本体の動作を妨げない。
            }
        }
    }

    override fun onDestroy() {
        updater?.close()
        super.onDestroy()
    }

    /** インストール済みアプリ自身の versionCode を PackageManager から取得する。 */
    private fun currentVersionCode(): Int =
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()

    /** インストール済みアプリ自身の versionName を PackageManager から取得する。 */
    private fun currentVersionName(): String? =
        packageManager.getPackageInfo(packageName, 0).versionName

    /**
     * 開発ビルドか（§12）。配布版とは署名が異なり上書き更新できないため、更新の導線を閉じる判断に使う。
     * 判定は debuggable フラグで行う（debug ビルドだけが立てる）。
     */
    private fun isDebugBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
