package to.sava.peranta.net

import kotlinx.serialization.Serializable

/**
 * ntfy の WebSocket / JSON ストリームの 1 メッセージ。
 * event フィールドで "message" / "open" / "keepalive" 等を判別する。
 */
@Serializable
data class NtfyWsMessage(
    val id: String? = null,
    val time: Long? = null,
    val event: String? = null,
    val topic: String? = null,
    val message: String? = null,
)

/** event == "message" の ntfy メッセージを表す、パイプライン向けの正規化イベント。 */
data class NtfyEvent(
    val id: String,
    val time: Long,
    val topic: String,
    val message: String,
)

/** ntfy が配送する本文イベントの event 値。 */
private const val EVENT_MESSAGE = "message"

/** 購読確立を示す ntfy の event 値。 */
private const val EVENT_OPEN = "open"

/** 購読確立（open）イベントかどうか。 */
val NtfyWsMessage.isOpen: Boolean get() = event == EVENT_OPEN

/**
 * ntfy の 1 行 JSON を [NtfyEvent] へ変換する。
 * event が "message" 以外（"open" / "keepalive" / "poll_request" 等）や
 * message 欠落の場合は null を返す。
 */
fun NtfyWsMessage.toEventOrNull(): NtfyEvent? {
    if (event != EVENT_MESSAGE) return null
    val body = message ?: return null
    return NtfyEvent(
        id = id ?: "",
        time = time ?: 0L,
        topic = topic ?: "",
        message = body,
    )
}
