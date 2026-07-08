package to.sava.peranta

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import to.sava.peranta.android.PerantaReceive
import to.sava.peranta.android.PerantaUnifiedPush
import to.sava.peranta.android.androidConfigRepository
import to.sava.peranta.update.AndroidUpdater

/** 通知権限が拒否されたときにタイムラインへ出す文言（§10.5）。 */
private const val NOTIFICATIONS_DENIED_MESSAGE =
    "通知の権限が許可されていません。受信した通知は表示されません"

class MainActivity : ComponentActivity() {

    private var updater: AndroidUpdater? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) reportNotificationsDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val config = androidConfigRepository().load()
        val updater = AndroidUpdater(this, config, currentVersionCode()).also { this.updater = it }
        updater.checkAtStartup()

        val receiveRole = !config.sendEnabled && config.isReadyForUnifiedPushReceive
        if (receiveRole) {
            requestNotificationsPermissionIfNeeded()
            PerantaUnifiedPush.register(this)
            lifecycleScope.launch { PerantaReceive.prime(this@MainActivity) }
        }

        setContent {
            if (receiveRole) {
                App(
                    items = PerantaReceive.items,
                    updateController = updater.controller,
                    onInstallUpdate = { url -> updater.install(url) },
                    receiveEndpoint = config.unifiedPushEndpoint,
                )
            } else {
                SendRoleApp(
                    sendEnabled = config.sendEnabled,
                    updateController = updater.controller,
                    onInstallUpdate = { url -> updater.install(url) },
                )
            }
        }
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
