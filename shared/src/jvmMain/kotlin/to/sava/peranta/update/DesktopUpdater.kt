package to.sava.peranta.update

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher

/**
 * Desktop の自己更新配線。HTTP クライアント・確認スコープ・MSI インストーラを内包し、
 * UI へは [controller]・[installState]・[install] だけを公開する（§12）。
 * ktor 型を app モジュールへ漏らさない。
 */
class DesktopUpdater(
    currentVersionCode: Int,
    private val log: Logger = Logger.withTag("Updater"),
) {
    private val httpClient = createNtfyHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val installer = DesktopUpdateInstaller(httpClient)

    /** UI が購読する更新状態。 */
    val controller: UpdateController =
        UpdateController(UpdateChecker(httpClient, currentVersionCode, PLATFORM_DESKTOP), scope)

    private val _installState = MutableStateFlow<UpdateInstallState?>(null)

    /** 適用の進み具合。未着手は null。 */
    val installState: StateFlow<UpdateInstallState?> = _installState.asStateFlow()

    /** 配布物として適用できる実行形態か。開発実行では false。 */
    val canInstall: Boolean
        get() = installer.isSupported

    /**
     * 配布物を落として照合し、適用スクリプトへ引き渡す。引き渡しに成功したら [onReadyToExit] を呼ぶ。
     * スクリプトは自プロセスの終了を待ってからインストールを始めるため、呼び出し側はここで
     * アプリを終了させる。実行中は多重に走らせない。
     */
    fun install(available: UpdateStatus.Available, onReadyToExit: () -> Unit) {
        if (isRunning()) {
            return
        }
        _installState.value = UpdateInstallState.Downloading
        scope.launch {
            try {
                val msi = installer.download(available.url)
                _installState.value = UpdateInstallState.Verifying
                if (!matchesSha256(msi, available.sha256)) {
                    msi.delete()
                    log.w { "downloaded update rejected: sha256 mismatch" }
                    _installState.value = UpdateInstallState.Failed("ダウンロードした更新の照合に失敗しました")
                    return@launch
                }
                installer.launchInstaller(msi)
                _installState.value = UpdateInstallState.Launching
                onReadyToExit()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.e(error) { "update install failed" }
                _installState.value = UpdateInstallState.Failed("更新の適用に失敗しました")
            }
        }
    }

    private fun isRunning(): Boolean = when (_installState.value) {
        UpdateInstallState.Downloading, UpdateInstallState.Verifying, UpdateInstallState.Launching -> true
        else -> false
    }

    /** 保持するスコープと HTTP クライアントを閉じる。 */
    fun close() {
        scope.cancel()
        httpClient.close()
    }
}
