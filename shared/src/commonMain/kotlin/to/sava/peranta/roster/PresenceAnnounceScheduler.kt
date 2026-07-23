package to.sava.peranta.roster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
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
    private val requests = MutableSharedFlow<T>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            requests.collectLatest { value ->
                delay(debounceMillis)
                announce(value)
            }
        }
    }

    /** 再 announce を要求する。スレッドを選ばない（NLS のコールバックスレッドから呼べる）。 */
    fun request(value: T) {
        requests.tryEmit(value)
    }
}
