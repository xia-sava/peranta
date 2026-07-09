package to.sava.peranta

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import to.sava.peranta.android.AndroidInstalledAppsProvider
import to.sava.peranta.android.PerantaReceive
import to.sava.peranta.android.PerantaUnifiedPush
import to.sava.peranta.android.androidConfigRepository
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.AppFilterScreen
import to.sava.peranta.ui.PairingScanScreen
import to.sava.peranta.ui.PerantaTheme
import to.sava.peranta.update.AndroidUpdater

/** 通知権限が拒否されたときにタイムラインへ出す文言（§10.5）。 */
private const val NOTIFICATIONS_DENIED_MESSAGE =
    "通知の権限が許可されていません。受信した通知は表示されません"

/** カメラ権限が拒否されたときに案内する文言（手動貼り付けへのフォールバックを明示する）。 */
private const val CAMERA_DENIED_MESSAGE =
    "カメラの権限が許可されていません。ペアリング文字列を貼り付けて取り込んでください"

/**
 * MainActivity が表示する画面（§10）。
 * [Main] はロール（受信/送信）に応じて本体を出し、[Pairing] は QR 取り込み画面、
 * [AppFilter] はアプリフィルタ画面（§10.4）を出す。健康診断（§10.5）の画面は後続フェーズでここへ加える。
 */
private sealed interface Screen {
    data object Main : Screen
    data object Pairing : Screen
    data object AppFilter : Screen
}

class MainActivity : ComponentActivity() {

    private var updater: AndroidUpdater? = null

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
        if (receiveRole) {
            requestNotificationsPermissionIfNeeded()
            lifecycleScope.launch { PerantaReceive.prime(this@MainActivity) }
        }

        setContent {
            var screen: Screen by remember {
                mutableStateOf(if (config.hasSharedKey) Screen.Main else Screen.Pairing)
            }
            when (screen) {
                Screen.Pairing -> PerantaTheme {
                    PairingScanScreen(
                        controller = importController,
                        onRequestScan = { onResult -> requestScan(onResult) },
                        onBack = if (config.hasSharedKey) {
                            { screen = Screen.Main }
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
                        onOpenPairing = { screen = Screen.Pairing },
                        onOpenAppFilter = { screen = Screen.AppFilter },
                        timelineActions = PerantaReceive.timelineActions(this@MainActivity),
                    )
                } else {
                    SendRoleApp(
                        sendEnabled = config.sendEnabled,
                        updateController = updater.controller,
                        onInstallUpdate = { url -> updater.install(url) },
                        onOpenPairing = { screen = Screen.Pairing },
                        onOpenAppFilter = { screen = Screen.AppFilter },
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
            }
        }
    }

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
