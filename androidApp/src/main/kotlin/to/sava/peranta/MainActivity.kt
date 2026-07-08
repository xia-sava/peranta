package to.sava.peranta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import to.sava.peranta.android.androidConfigRepository
import to.sava.peranta.update.AndroidUpdater

class MainActivity : ComponentActivity() {

    private var updater: AndroidUpdater? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val config = androidConfigRepository().load()
        val updater = AndroidUpdater(this, config, currentVersionCode()).also { this.updater = it }
        updater.checkAtStartup()

        setContent {
            SendRoleApp(
                sendEnabled = config.sendEnabled,
                updateController = updater.controller,
                onInstallUpdate = { url -> updater.install(url) },
            )
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
