package to.sava.peranta.update

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.sava.peranta.net.createUpdateHttpClient
import to.sava.peranta.platform.ioDispatcher

/**
 * Android の自己更新配線。HTTP クライアント・確認スコープ・APK インストーラを内包し、
 * UI へは [controller]・[installState]・[install] だけを公開する（§12）。
 * ktor 型を app モジュールへ漏らさない。
 */
class AndroidUpdater(
    context: Context,
    currentVersionCode: Int,
    private val log: Logger = Logger.withTag("Updater"),
) {
    private val httpClient = createUpdateHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val installer = AndroidUpdateInstaller(context.applicationContext, httpClient)

    /** UI が購読する更新状態。 */
    val controller: UpdateController =
        UpdateController(UpdateChecker(httpClient, currentVersionCode, PLATFORM_ANDROID), scope)

    private val _installState = MutableStateFlow<UpdateInstallState?>(null)

    /** 適用の進み具合。未着手は null。 */
    val installState: StateFlow<UpdateInstallState?> = _installState.asStateFlow()

    /**
     * APK をダウンロードして照合し、インストール確認 Intent を発行する。実行中は多重に走らせない。
     * インストールの可否はユーザーが確認画面で決めるため、ここでは発行までを担う。
     */
    fun install(available: UpdateStatus.Available) {
        if (isRunning()) {
            return
        }
        _installState.value = UpdateInstallState.Downloading(0, 0)
        scope.launch {
            try {
                val apk = installer.download(available.url) { received, total ->
                    _installState.value = UpdateInstallState.Downloading(received, total)
                }
                _installState.value = UpdateInstallState.Verifying
                installer.verify(apk, available.sha256)
                installer.launch(apk)
                _installState.value = UpdateInstallState.Launching
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.e(error) { "update download/install failed" }
                _installState.value = UpdateInstallState.Failed("更新の適用に失敗しました")
            }
        }
    }

    private fun isRunning(): Boolean = when (_installState.value) {
        is UpdateInstallState.Downloading -> true
        UpdateInstallState.Verifying, UpdateInstallState.Launching -> true
        else -> false
    }

    /** 保持するスコープと HTTP クライアントを閉じる。 */
    fun close() {
        scope.cancel()
        httpClient.close()
    }
}
