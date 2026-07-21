package to.sava.peranta

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import to.sava.peranta.android.PerantaReceive
import to.sava.peranta.android.PerantaUnifiedPush
import to.sava.peranta.android.androidConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.pairing.pairingQrMatrix
import to.sava.peranta.send.sharedStreamItems
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.AppFilterScreen
import to.sava.peranta.ui.AttachmentUi
import to.sava.peranta.ui.HealthCheckScreen
import to.sava.peranta.ui.healthCheckNeedsAttention
import to.sava.peranta.ui.PairingScanScreen
import to.sava.peranta.ui.PerantaTheme
import to.sava.peranta.ui.QrCodeCanvas
import to.sava.peranta.ui.SettingsScreen
import to.sava.peranta.ui.ShareScreen
import to.sava.peranta.ui.setup.ReceiveSetupScreen
import to.sava.peranta.ui.setup.WizardRole
import to.sava.peranta.ui.setup.WizardScreen
import to.sava.peranta.update.AndroidUpdater

/** 通知権限が拒否されたときにタイムラインへ出す文言（§10.5）。 */
private const val NOTIFICATIONS_DENIED_MESSAGE =
    "通知の権限が許可されていません。受信した通知は表示されません"

/** カメラ権限が拒否されたときに案内する文言（手動貼り付けへのフォールバックを明示する）。 */
private const val CAMERA_DENIED_MESSAGE =
    "カメラの権限が許可されていません。ペアリング文字列を貼り付けて取り込んでください"

/** SAF 保存要求中の blobId を Activity 再生成越しに引き継ぐための保存キー。 */
private const val KEY_PENDING_SAVE_BLOB_ID = "pendingSaveBlobId"

/**
 * MainActivity が表示する画面（§10）。
 * [Main] はロール（受信/送信）に応じて本体を出し、[Pairing] は QR 取り込み画面、
 * [Settings] は設定画面（§10.2）、[AppFilter] はアプリフィルタ画面（§10.4）、
 * [HealthCheck] は健康診断画面（§10.5）、[ReceiveSetup] は受信のセットアップ常設画面、
 * [Wizard] は設定・診断をページ列で案内するウィザードを出す。
 */
private sealed interface Screen {
    data object Main : Screen
    data object Pairing : Screen
    data object Settings : Screen
    data object AppFilter : Screen
    data object HealthCheck : Screen
    data object ReceiveSetup : Screen
    data object Wizard : Screen
    data class Share(val files: List<Uri>) : Screen
}

class MainActivity : ComponentActivity() {

    private var updater: AndroidUpdater? = null

    /**
     * 画面復帰（ON_RESUME）ごとに進めるカウンタ。健康診断画面はこれを再チェックの契機にし、
     * システム設定から戻った直後の権限・設定状態を反映する（§10.5）。
     */
    private var resumeTick by mutableStateOf(0)

    /** スキャン結果の受け取り先。スキャン開始のたびに差し替える（キャンセル時は null が渡る）。 */
    private var pendingScanResult: ((String?) -> Unit)? = null

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

    /** 受信添付の「開く」「保存」「共有」を担う。受信ロールで設定が揃ったときだけ生成する（§4.3）。 */
    private var attachmentActions: AndroidAttachmentActions? = null

    /** 添付保存（SAF）のドキュメント作成ランチャー。返った Uri へキャッシュからコピーする。 */
    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val actions = attachmentActions ?: return@registerForActivityResult
            lifecycleScope.launch { actions.copyToDocument(uri) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val config = androidConfigRepository().load()
        val updater = AndroidUpdater(this, config, currentVersionCode()).also { this.updater = it }
        updater.checkAtStartup()

        val importController = PairingImportController(androidConfigRepository())
        // コマンド受信のための UnifiedPush 登録は役割を問わない（送信ロールのスマホも受け口を持つ、§3.4）。
        if (config.isReadyForUnifiedPushReceive) {
            PerantaUnifiedPush.register(this)
        }
        val receiveRole = !config.sendEnabled && config.isReadyForUnifiedPushReceive
        val attachmentUi = if (receiveRole) {
            attachmentActions = buildAttachmentActions(config).also {
                it.restorePendingSaveState(savedInstanceState?.getString(KEY_PENDING_SAVE_BLOB_ID))
            }
            AndroidAttachmentReceive.attachmentUi(this, attachmentActions!!)
        } else {
            null
        }
        if (receiveRole) {
            requestNotificationsPermissionIfNeeded()
            lifecycleScope.launch { PerantaReceive.prime(this@MainActivity) }
            primeAttachmentCache(config)
        }

        val receiveSetupProvider = AndroidReceiveSetupProvider(this)
        val wizardSetupProvider = AndroidWizardSetupProvider(this)
        // 受信のセットアップは受信ロールの設定が揃うか、UnifiedPush 登録済みのとき入れる。
        // 登録済みなら config が欠けても修復の作業台へ戻れるようにする。
        val showReceiveSetup = config.isReadyForUnifiedPushReceive || AndroidSetupProbe(this).unifiedPushRegistered()
        val sharedFiles = extractSharedFiles(intent)

        setContent {
            var screen: Screen by remember {
                mutableStateOf(
                    when {
                        sharedFiles.isNotEmpty() && config.hasSharedKey -> Screen.Share(sharedFiles)
                        config.hasSharedKey -> Screen.Main
                        else -> Screen.Wizard
                    },
                )
            }

            // 健康診断の UnifiedPush 系項目は受信のセットアップ画面へ誘導する。onOpen で診断からその画面へ移す。
            val healthChecker = remember { AndroidHealthChecker(this@MainActivity) { screen = Screen.ReceiveSetup } }

            // 起動時にペアリング済みなら健康診断を実行し、対処の要る未達があれば診断画面へ誘導する（§10.5）。
            // 未セットアップは初回ウィザードが最優先のため、Main のときだけ遷移する。強制ブロックはしない。
            LaunchedEffect(Unit) {
                if (config.hasSharedKey && screen == Screen.Main &&
                    healthCheckNeedsAttention(healthChecker.check())
                ) {
                    screen = Screen.HealthCheck
                }
            }

            when (screen) {
                Screen.Pairing -> PerantaTheme {
                    PairingScanScreen(
                        controller = importController,
                        onRequestScan = { onResult -> requestScan(onResult) },
                        onOpenSettings = if (config.hasSharedKey) {
                            null
                        } else {
                            { screen = Screen.Settings }
                        },
                        onOpenWizard = if (config.hasSharedKey) {
                            null
                        } else {
                            { screen = Screen.Wizard }
                        },
                        onImported = { resetReceiveAndRecreate() },
                        onBack = if (config.hasSharedKey) {
                            { resetReceiveAndRecreate() }
                        } else {
                            null
                        },
                    )
                }

                Screen.Main -> if (receiveRole) {
                    App(
                        items = PerantaReceive.items,
                        updateController = updater.controller,
                        onInstallUpdate = { url -> updater.install(url) },
                        receiveEndpoint = config.unifiedPushEndpoint,
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenPairing = { screen = Screen.Pairing },
                        onOpenAppFilter = { screen = Screen.AppFilter },
                        onOpenReceiveSetup = if (showReceiveSetup) {
                            { screen = Screen.ReceiveSetup }
                        } else {
                            null
                        },
                        onOpenHealthCheck = { screen = Screen.HealthCheck },
                        timelineActions = PerantaReceive.timelineActions(this@MainActivity),
                        attachmentUi = attachmentUi,
                        fullTextUi = AndroidAttachmentReceive.fullTextUi(this@MainActivity, config),
                    )
                } else {
                    SendRoleApp(
                        sendEnabled = config.sendEnabled,
                        updateController = updater.controller,
                        onInstallUpdate = { url -> updater.install(url) },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenPairing = { screen = Screen.Pairing },
                        onOpenAppFilter = { screen = Screen.AppFilter },
                        onOpenReceiveSetup = if (showReceiveSetup) {
                            { screen = Screen.ReceiveSetup }
                        } else {
                            null
                        },
                        onOpenHealthCheck = { screen = Screen.HealthCheck },
                    )
                }

                Screen.Settings -> PerantaTheme {
                    SettingsScreen(
                        controller = SettingsController(androidConfigRepository()),
                        qrContent = { uri ->
                            QrCodeCanvas(pairingQrMatrix(uri), modifier = Modifier.size(240.dp))
                        },
                        onCopyPairingUri = { text -> copyPairingUri(text) },
                        showSendRoleOptions = true,
                        onOpenTimeline = { resetReceiveAndRecreate() },
                        onOpenWizard = { screen = Screen.Wizard },
                    )
                }

                Screen.Wizard -> PerantaTheme {
                    WizardScreen(
                        role = WizardRole.ANDROID,
                        controller = SettingsController(androidConfigRepository()),
                        provider = wizardSetupProvider,
                        healthChecker = healthChecker,
                        importController = importController,
                        qrContent = { uri ->
                            QrCodeCanvas(pairingQrMatrix(uri), modifier = Modifier.size(240.dp))
                        },
                        onCopyPairingUri = { text -> copyPairingUri(text) },
                        onCopyText = { text, sensitive -> copyText(text, sensitive) },
                        onRequestScan = { onResult -> requestScan(onResult) },
                        externalRefreshKey = resumeTick,
                        // ペアリング済みならタイムラインへ（再生成で最新設定を反映）、未ペアリングなら
                        // ウィザード再開導線つきの待機画面（Pairing）へ着地する。
                        onClose = {
                            if (androidConfigRepository().load().hasSharedKey) {
                                resetReceiveAndRecreate()
                            } else {
                                screen = Screen.Pairing
                            }
                        },
                    )
                }

                Screen.AppFilter -> PerantaTheme {
                    if (receiveRole) {
                        AppFilterScreen(
                            controller = PerantaReceive.appFilterController(this@MainActivity),
                            items = PerantaReceive.items,
                            onBack = { screen = Screen.Main },
                        )
                    } else {
                        AppFilterScreen(
                            controller = AppFilterController(androidConfigRepository()),
                            installedAppsProvider = AndroidInstalledAppsProvider(this@MainActivity),
                            onBack = { screen = Screen.Main },
                        )
                    }
                }

                Screen.HealthCheck -> PerantaTheme {
                    HealthCheckScreen(
                        checker = healthChecker,
                        onBack = { screen = Screen.Main },
                        externalRefreshKey = resumeTick,
                        onCopyText = { text, sensitive -> copyText(text, sensitive) },
                    )
                }

                Screen.ReceiveSetup -> PerantaTheme {
                    ReceiveSetupScreen(
                        provider = receiveSetupProvider,
                        onBack = { screen = Screen.Main },
                        externalRefreshKey = resumeTick,
                        onCopyText = { text, sensitive -> copyText(text, sensitive) },
                    )
                }

                is Screen.Share -> PerantaTheme {
                    val files = (screen as Screen.Share).files
                    ShareScreen(
                        itemCount = files.size,
                        onSend = { caption ->
                            AttachmentTransferService.enqueueUpload(this@MainActivity, files, caption)
                            finish()
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
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
     * 任意のテキストをシステムクリップボードへコピーする（健康診断の案内ダイアログのコピー導線・
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
}
