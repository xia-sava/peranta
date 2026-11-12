package to.sava.peranta.update

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 配布物を取得して sha256 を照合する（§12）。進み具合と失敗を [onState] へ出す。
 * 照合を通った配布物を返す。失敗したときは破棄して null。成功時の状態は Verifying のまま返す。
 *
 * [download] は [UpdateStatus.Available.url] から配布物を取得して置いたファイルを返す。
 * https でない取得先を拒むことと、失敗・キャンセルのときに書きかけを残さないことは [download] が受け持つ
 * （[downloadToFile] がそうする）。取得に失敗したら [UPDATE_DOWNLOAD_FAILED_MESSAGE] で止まる。
 *
 * 照合に落ちた配布物は [discard] で破棄して [UPDATE_VERIFY_FAILED_MESSAGE] で止まる。
 * 照合の期待値が空のときも照合に落ちる（[matchesSha256]）。取得の済んだ配布物を照合のために
 * 読めなかったときも破棄し、取得の失敗として止まる。キャンセルは握らずに伝える。
 */
suspend fun downloadVerifiedRelease(
    available: UpdateStatus.Available,
    download: suspend (url: String, onProgress: (received: Long, total: Long) -> Unit) -> File,
    discard: (File) -> Unit,
    onState: (UpdateInstallState) -> Unit,
    log: Logger,
): File? {
    val release = try {
        download(available.url) { received, total ->
            onState(UpdateInstallState.Downloading(received, total))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        reportDownloadFailure(error, onState, log)
        return null
    }
    onState(UpdateInstallState.Verifying)
    val matches = try {
        matchesSha256(release, available.sha256)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        discard(release)
        reportDownloadFailure(error, onState, log)
        return null
    }
    if (!matches) {
        discard(release)
        log.w { "downloaded update rejected: sha256 mismatch" }
        onState(UpdateInstallState.Failed(UPDATE_VERIFY_FAILED_MESSAGE))
        return null
    }
    return release
}

private fun reportDownloadFailure(error: Exception, onState: (UpdateInstallState) -> Unit, log: Logger) {
    log.e(error) { "update download failed" }
    onState(UpdateInstallState.Failed(UPDATE_DOWNLOAD_FAILED_MESSAGE))
}
