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
import java.io.File

/**
 * Desktop の自己更新配線。HTTP クライアント・確認スコープ・MSI インストーラを内包し、
 * UI へは [controller]・[installState]・[install] だけを公開する（§12）。
 * ktor 型を app モジュールへ漏らさない。
 *
 * [downloadRelease]（配布物の取得）と [launchInstaller]（照合済み配布物の引き渡し）は
 * 内包する [DesktopUpdateInstaller] の対応する操作を既定とし、差し替えを受け付ける。
 */
class DesktopUpdater(
    currentVersionCode: Int,
    private val log: Logger = Logger.withTag("Updater"),
    downloadRelease: (suspend (url: String, onProgress: (received: Long, total: Long) -> Unit) -> File)? = null,
    launchInstaller: ((msi: File) -> Unit)? = null,
) {
    private val httpClient = createNtfyHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val installer = DesktopUpdateInstaller(httpClient)
    private val downloadRelease = downloadRelease ?: installer::download
    private val launchInstaller = launchInstaller ?: installer::launchInstaller

    /** UI が購読する更新状態。 */
    val controller: UpdateController =
        UpdateController(UpdateChecker(httpClient, currentVersionCode, PLATFORM_DESKTOP), scope)

    private val _installState = MutableStateFlow<UpdateInstallState?>(null)

    /** 適用の進み具合。未着手は null。 */
    val installState: StateFlow<UpdateInstallState?> = _installState.asStateFlow()

    /** 配布物として適用できる実行形態か。開発実行では false。 */
    val canInstall: Boolean
        get() = installer.isSupported

    /** 照合まで済んだ配布物。適用の確認を待つあいだ保持する。 */
    private var verified: File? = null

    /**
     * 配布物を落として照合する。照合まで済むと [UpdateInstallState.ReadyToApply] で止まり、
     * 実際の適用は [applyNow] を待つ。適用はアプリの終了を伴うため、確認を挟んでから進める。
     * 実行中は多重に走らせない。
     */
    fun install(available: UpdateStatus.Available) {
        if (isRunning()) {
            return
        }
        _installState.value = UpdateInstallState.Downloading(0, 0)
        scope.launch {
            try {
                val msi = downloadRelease(available.url) { received, total ->
                    _installState.value = UpdateInstallState.Downloading(received, total)
                }
                _installState.value = UpdateInstallState.Verifying
                if (!matchesSha256(msi, available.sha256)) {
                    msi.delete()
                    log.w { "downloaded update rejected: sha256 mismatch" }
                    _installState.value = UpdateInstallState.Failed("ダウンロードした更新の照合に失敗しました")
                    return@launch
                }
                verified = msi
                _installState.value = UpdateInstallState.ReadyToApply
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.e(error) { "update download failed" }
                _installState.value = UpdateInstallState.Failed("更新のダウンロードに失敗しました")
            }
        }
    }

    /**
     * 照合済みの配布物を適用スクリプトへ引き渡し、[onReadyToExit] を呼ぶ。スクリプトは自プロセスの
     * 終了を待ってからインストールを始めるため、呼び出し側はここでアプリを終了させる。
     */
    fun applyNow(onReadyToExit: () -> Unit) {
        val msi = verified ?: return
        try {
            launchInstaller(msi)
            _installState.value = UpdateInstallState.Launching
            onReadyToExit()
        } catch (error: Exception) {
            log.e(error) { "update apply failed" }
            _installState.value = UpdateInstallState.Failed("更新の適用に失敗しました")
        }
    }

    /** 適用を取りやめ、ダウンロード済みの配布物を捨てる。 */
    fun cancelApply() {
        verified?.delete()
        verified = null
        _installState.value = null
    }

    private fun isRunning(): Boolean = when (_installState.value) {
        is UpdateInstallState.Downloading -> true
        UpdateInstallState.Verifying, UpdateInstallState.ReadyToApply, UpdateInstallState.Launching -> true
        else -> false
    }

    /** 保持するスコープと HTTP クライアントを閉じる。 */
    fun close() {
        scope.cancel()
        httpClient.close()
    }
}
