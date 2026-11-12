package to.sava.peranta.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.model.AttachmentRef

/**
 * blobId ごとのダウンロード状態（§4.3）。書き手は各プラットフォームの転送、読み手は添付カード（[AttachmentUi]）。
 * 各 `mark*` は blobId の状態をまるごと置き換える。書き込みは [MutableStateFlow.update] の CAS で行うため、
 * 書き手のスレッドと読み手のスレッドが分かれていてもよい。
 */
class AttachmentDownloadStates {

    private val _states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())

    /** blobId ごとのダウンロード状態。UI がこれを購読する。 */
    val states: StateFlow<Map<String, AttachmentDownloadState>> = _states.asStateFlow()

    /** [blobId] の現在の状態。一度も書き込んでいなければ null。 */
    fun stateOf(blobId: String): AttachmentDownloadState? = _states.value[blobId]

    /** ダウンロードの開始を反映する（カードは進捗バーとキャンセルを出す）。 */
    fun markRunning(blobId: String, totalBytes: Long) {
        put(blobId, AttachmentDownloadState(progress = TransferProgress.running(totalBytes)))
    }

    /** 転送済みバイト数を進捗バーへ反映する。 */
    fun markProgress(blobId: String, transferredBytes: Long, totalBytes: Long) {
        put(
            blobId,
            AttachmentDownloadState(progress = TransferProgress(transferredBytes, totalBytes, TransferState.RUNNING)),
        )
    }

    /** 復号済みファイルがキャッシュに在ることを反映する。[thumbnail] は画像をデコードできたときのサムネイル。 */
    fun markCached(ref: AttachmentRef, thumbnail: ImageBitmap?) {
        put(
            ref.blobId,
            AttachmentDownloadState(
                progress = TransferProgress(ref.sizeBytes, ref.sizeBytes, TransferState.COMPLETED),
                cached = true,
                thumbnail = thumbnail,
            ),
        )
    }

    /** ダウンロードの失敗を反映する（カードは再試行ボタンを出す）。 */
    fun markFailed(blobId: String, totalBytes: Long) {
        put(blobId, AttachmentDownloadState(progress = TransferProgress(0, totalBytes, TransferState.FAILED)))
    }

    /** ユーザーによるキャンセルを反映する（カードは未取得の表示へ戻る）。 */
    fun markCancelled(blobId: String) {
        put(blobId, AttachmentDownloadState(progress = TransferProgress(0, 0, TransferState.CANCELLED)))
    }

    private fun put(blobId: String, state: AttachmentDownloadState) {
        _states.update { it + (blobId to state) }
    }
}
