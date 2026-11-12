package to.sava.peranta.roster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** NLS 接続/切断の再 announce を落ち着くまで待つデバウンス時間（ミリ秒）。 */
const val PRESENCE_REANNOUNCE_DEBOUNCE_MILLIS: Long = 5_000L

/**
 * announce 要求をデバウンスして 1 回にまとめる（§3.2）。要求が連続したら前の待機/実行をキャンセルし、
 * 最後の要求から [debounceMillis] 経過後に [announce] を 1 回だけ実行する。
 */
class PresenceAnnounceScheduler<T>(
    scope: CoroutineScope,
    private val debounceMillis: Long = PRESENCE_REANNOUNCE_DEBOUNCE_MILLIS,
    private val announce: suspend (T) -> Unit,
) {
    // 受け手が待ち始める前に届いた要求も保持する。起こされたばかりのプロセスでは、最初の要求が
    // 受け手の準備より先に届く。保持するのは最新の 1 件だけでよい（古い要求は新しい要求に置き換わる）。
    private val requests = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            requests.receiveAsFlow().collectLatest { value ->
                delay(debounceMillis)
                announce(value)
            }
        }
    }

    /** 再 announce を要求する。スレッドを選ばない（NLS のコールバックスレッドから呼べる）。 */
    fun request(value: T) {
        requests.trySend(value)
    }
}
