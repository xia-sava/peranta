package to.sava.peranta.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** WebSocket 購読の接続状態。将来のトレイ接続状態表示にも用いる。 */
enum class NtfyConnectionState {
    /** 未接続・切断中。 */
    DISCONNECTED,

    /** 接続試行中（open 未受領）。 */
    CONNECTING,

    /** open イベントを受領し購読が確立した状態。 */
    SUBSCRIBED,
}

/**
 * ntfy への publish（HTTP POST）と subscribe（WebSocket 購読）を提供する。
 * テストではフェイク実装に差し替えられるよう interface とする。
 */
interface NtfyClient {

    /** 直近の購読接続状態。複数 topic を同時購読しない前提で単一状態を公開する。 */
    val connectionState: StateFlow<NtfyConnectionState>

    /**
     * [topic] へ [body] を POST する。
     * [cacheSeconds] を指定するとキャッシュ保持時間を `Cache: <n>s` ヘッダで縛る（null なら省略）。
     */
    suspend fun publish(topic: String, body: String, cacheSeconds: Int? = null)

    /**
     * [topic] を WebSocket 購読し、本文イベントのみを流す。
     * 切断時は指数バックオフで自動再接続し、Flow は明示キャンセルまで完了しない。
     */
    fun subscribe(topic: String): Flow<NtfyEvent>
}
