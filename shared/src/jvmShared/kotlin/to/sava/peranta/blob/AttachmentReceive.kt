package to.sava.peranta.blob

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.AttachmentDownloadStates
import to.sava.peranta.ui.referencedAttachments
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * タイムラインの [items] が参照する添付（表示用の添付と送信者アイコン）のうち、[states] に状態を持たず
 * 復号済みファイルが在るものを取得済みにする（§4.3）。履歴読み込み・新規受信の双方で、カードを開く・保存できる
 * 状態にする。進行中・失敗・キャンセル済みなど状態を持つ blob は、ファイルが在っても書き換えない。
 * [cachedFile] は復号済みファイルを引く口（無ければ null）、[decodeThumbnail] はサムネイルのデコード（できなければ null）。
 */
fun primeCachedAttachments(
    states: AttachmentDownloadStates,
    items: List<TimelineItem>,
    cachedFile: (AttachmentRef) -> File?,
    decodeThumbnail: (AttachmentRef, File) -> ImageBitmap?,
) {
    items.asSequence()
        .flatMap { it.referencedAttachments() }
        .filter { states.stateOf(it.blobId) == null }
        .forEach { ref -> cachedFile(ref)?.let { file -> states.markCached(ref, decodeThumbnail(ref, file)) } }
}

/**
 * 復号済みの全文添付（kind=TEXT、§4.3）の本文を blobId ごとに保持し、再表示（スクロール復帰）での再取得を避ける。
 * [log] には取得を見送った・失敗した旨を残す。
 */
class FullTextCache(private val log: Logger = Logger.withTag("FullTextCache")) {

    private val texts = ConcurrentHashMap<String, String>()

    /**
     * [ref] の全文添付の本文を返す（§4.3）。保持していればそれを、無ければ [download] で復号済みファイルを得て
     * UTF-8 で読み、保持してから返す。宣言サイズが自動取得の上限（[exceedsFullTextAutoFetchLimit]）を超えるもの、
     * 取得に失敗したもの（オフライン・期限切れ等）は null を返す。失敗は保持しないため、次の表示で取り直す。
     * キャンセルは握らずに伝える。ブロッキング I/O を含むため IO ディスパッチャで動かす。
     */
    suspend fun fetch(ref: AttachmentRef, download: suspend (AttachmentRef) -> File): String? {
        texts[ref.blobId]?.let { return it }
        if (exceedsFullTextAutoFetchLimit(ref.sizeBytes)) {
            log.w { "full text attachment exceeds auto fetch limit; skipping blobId=${ref.blobId} sizeBytes=${ref.sizeBytes}" }
            return null
        }
        return try {
            withContext(ioDispatcher) { download(ref).readText(Charsets.UTF_8) }
                .also { texts[ref.blobId] = it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // 例外そのものは流さない。ktor の例外メッセージには blob の取得先 URL（＝ホスト）が載る（§11）。
            log.w { "full text fetch failed blobId=${ref.blobId} (${error::class.simpleName})" }
            null
        }
    }
}
