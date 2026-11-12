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
import java.io.File

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
        if (_installState.value.isInProgress()) {
            return
        }
        _installState.value = UpdateInstallState.Downloading(0, 0)
        scope.launch {
            downloadAndHandOffApk(
                available = available,
                download = installer::download,
                verifyPackage = installer::hasOwnPackageName,
                launch = installer::launch,
                discard = ::discardApk,
                onState = { _installState.value = it },
                log = log,
            )
        }
    }

    /** 保持するスコープと HTTP クライアントを閉じる。 */
    fun close() {
        scope.cancel()
        httpClient.close()
    }
}

/**
 * APK を取得して sha256 を照合し（[downloadVerifiedRelease]）、照合を通った APK だけを
 * [handOffVerifiedApk] で packageName の照合とインストーラへの引き渡しへ進める（§12）。
 * 進み具合と失敗は [onState] へ出す。sha256 の照合に落ちた APK は、[verifyPackage] にも
 * [launch] にも渡さずに破棄する。キャンセルは握らずに伝える。
 */
internal suspend fun downloadAndHandOffApk(
    available: UpdateStatus.Available,
    download: suspend (url: String, onProgress: (received: Long, total: Long) -> Unit) -> File,
    verifyPackage: (File) -> Boolean,
    launch: (File) -> Unit,
    discard: (File) -> Unit,
    onState: (UpdateInstallState) -> Unit,
    log: Logger,
) {
    val apk = downloadVerifiedRelease(available, download, discard, onState, log) ?: return
    handOffVerifiedApk(apk, verifyPackage, launch, discard, onState, log)
}

/**
 * sha256 の照合を通った [apk] が自アプリの packageName を持つかを [verifyPackage] で確かめてから、
 * [launch] でインストーラへ引き渡す（§12）。
 *
 * [verifyPackage] が偽を返すか例外を投げた APK は [discard] で破棄し、[UPDATE_VERIFY_FAILED_MESSAGE] で
 * 止まる（引き渡しへ進まない）。引き渡しに失敗したら [UPDATE_APPLY_FAILED_MESSAGE] で止まる。
 * 引き渡せたら [UpdateInstallState.Launching] にする。キャンセルは握らずに伝える。
 */
internal fun handOffVerifiedApk(
    apk: File,
    verifyPackage: (File) -> Boolean,
    launch: (File) -> Unit,
    discard: (File) -> Unit,
    onState: (UpdateInstallState) -> Unit,
    log: Logger,
) {
    val ownPackage = try {
        verifyPackage(apk)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        log.w(error) { "downloaded update package could not be checked" }
        false
    }
    if (!ownPackage) {
        discard(apk)
        log.w { "downloaded update rejected: package mismatch" }
        onState(UpdateInstallState.Failed(UPDATE_VERIFY_FAILED_MESSAGE))
        return
    }
    try {
        launch(apk)
        onState(UpdateInstallState.Launching)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        log.e(error) { "update install failed" }
        onState(UpdateInstallState.Failed(UPDATE_APPLY_FAILED_MESSAGE))
    }
}

/** ダウンロードした APK を捨てる。 */
private fun discardApk(apk: File) {
    apk.delete()
}
