package to.sava.peranta.update

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher

/**
 * Android の自己更新配線。HTTP クライアント・確認スコープ・APK インストーラを内包し、
 * UI へは [controller] と [install] だけを公開する（§12）。ktor 型を app モジュールへ漏らさない。
 */
class AndroidUpdater(
    context: Context,
    config: PerantaConfig,
    currentVersionCode: Int,
    private val log: Logger = Logger.withTag("Updater"),
) {
    private val httpClient = createNtfyHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val installer = AndroidUpdateInstaller(context.applicationContext, httpClient)

    /** UI が購読する更新状態。 */
    val controller: UpdateController =
        UpdateController(UpdateChecker(httpClient, config, currentVersionCode, PLATFORM_ANDROID), scope)

    /** 起動時の更新確認を実行する。 */
    fun checkAtStartup() {
        controller.checkNow()
    }

    /** APK をダウンロードしてインストール確認 Intent を発行する。失敗はログに残す。 */
    fun install(url: String) {
        scope.launch {
            try {
                installer.downloadAndLaunch(url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.e(error) { "update download/install failed" }
            }
        }
    }

    /** 保持するスコープと HTTP クライアントを閉じる。 */
    fun close() {
        scope.cancel()
        httpClient.close()
    }
}
