package to.sava.peranta.update

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher

/**
 * Desktop の自己更新配線。HTTP クライアント・確認スコープ・MSI インストーラを内包し、
 * UI へは [controller] と [install] だけを公開する（§12）。ktor 型を app モジュールへ漏らさない。
 */
class DesktopUpdater(
    config: PerantaConfig,
    currentVersionCode: Int,
    private val log: Logger = Logger.withTag("Updater"),
) {
    private val httpClient = createNtfyHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val installer = DesktopUpdateInstaller()

    /** UI が購読する更新状態。 */
    val controller: UpdateController =
        UpdateController(UpdateChecker(httpClient, config, currentVersionCode, PLATFORM_DESKTOP), scope)

    /** MSI のダウンロードページを既定ブラウザで開く。失敗は UI をクラッシュさせずログに残す。 */
    fun install(url: String) {
        try {
            installer.openDownloadPage(url)
        } catch (error: Exception) {
            log.e(error) { "failed to open update download page" }
        }
    }

    /** 保持するスコープと HTTP クライアントを閉じる。 */
    fun close() {
        scope.cancel()
        httpClient.close()
    }
}
