package to.sava.peranta.android

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.timeline.deleteTimelineFiles
import java.io.File

/** 送信するファイルを転送のあいだ置くキャッシュ領域のディレクトリ名。 */
const val OUTGOING_CACHE_DIR: String = "outgoing"

/**
 * 「すべての情報の消去」（§11）でキャッシュ領域から消すもの。復号済み添付と、送信待ちの
 * スプールコピーが対象で、設定と秘密は [to.sava.peranta.config.ConfigRepository] 側が消す。
 *
 * 消すのは Peranta が作るディレクトリだけに限り、[cacheDir] 配下でもそれ以外には触れない。
 * 更新の配布物を置く領域は通知・設定・鍵に由来しないため対象に含めず、取得の経路が自ら片づける。
 */
fun eraseCachedAppData(cacheDir: File) {
    listOf(ATTACHMENTS_CACHE_DIR, OUTGOING_CACHE_DIR)
        .map { File(cacheDir, it) }
        .filterNot { it.deleteRecursively() }
        .forEach { Logger.withTag("Reset").w { "failed to erase ${it.name}" } }
}

/**
 * 「すべての情報の消去」（§11）で [filesDir] のタイムライン履歴を消す。履歴の本体と、書き直しの途中で
 * 止められて残った一時ファイルが対象で、一時ファイルを経ずに直接消す。
 * 一時ファイルには乱数名のものに加え、一時ファイルを固定名 `timeline.jsonl.tmp` で作っていた版が
 * 残したものも含む。履歴の内容がそのまま入っているため消す。
 *
 * 消せなかったものはログへ残して続け、例外は投げない。後続の消去を止めないため。
 * [filesDir] 内のそれ以外のファイルには触れない。
 */
fun eraseTimelineFiles(filesDir: File) {
    deleteTimelineFiles(filesDir)
        .forEach { Logger.withTag("Reset").w { "failed to erase ${it.name}" } }
}

/**
 * 送信の再送キューを取り消し、終わった記録を WorkManager から消す（§11）。
 * 入力 Data には表示メタ（アプリ名・通知タイトル）が平文で載るため、消去の対象に含める。
 * 取り消すのは Peranta が積んだ再送ジョブだけで、[SendRetryWorker] のクラス名が付く暗黙のタグで絞る。
 *
 * 失敗しても他の消去を止めないよう、理由をログへ残して続ける。
 */
suspend fun eraseSendRetryQueue(context: Context) {
    val workManager = WorkManager.getInstance(context.applicationContext)
    try {
        workManager.cancelAllWorkByTag(SendRetryWorker::class.java.name).await()
        workManager.pruneWork().await()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Logger.withTag("Reset").w(error) { "failed to erase send retry queue" }
    }
}
