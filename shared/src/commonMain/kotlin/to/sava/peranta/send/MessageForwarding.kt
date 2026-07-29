package to.sava.peranta.send

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.MAX_MESSAGE_TEXT_BYTES
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.model.truncateToUtf8Bytes
import to.sava.peranta.net.NtfyClient

/** メッセージ送信に失敗したときにタイムラインへ出す文言。 */
const val MESSAGE_SEND_FAILED_MESSAGE: String = "メッセージの送信に失敗しました"

/** 入力テキストから全端末宛のメッセージを組む。本文は上限で切り詰める。 */
fun buildMessagePayload(
    deviceId: String,
    text: String,
    now: Long,
    deviceName: String? = null,
    idGen: () -> String = ::newPayloadId,
): MessagePayload = MessagePayload(
    id = idGen(),
    from = deviceId,
    to = BROADCAST_TARGET,
    sentAtEpochMillis = now,
    text = truncateToUtf8Bytes(text, MAX_MESSAGE_TEXT_BYTES),
    fromName = deviceName,
)

/**
 * メッセージを封緘して全端末へ送る。成功時は SentNotification が feed へ即時記録され（右バブル）、
 * 宛先未解決・publish 失敗はエラーとしてタイムラインへ記録して false を返す（自動再送はしない）。
 * 例外は外へ漏らさない（CancellationException を除く）。
 */
suspend fun sendMessage(
    config: PerantaConfig,
    cipher: MessageCipher,
    ntfy: NtfyClient,
    pipeline: SendPipeline,
    text: String,
    now: Long = nowEpochMillis(),
    log: Logger = Logger.withTag("MessageSend"),
): Boolean {
    val payload = buildMessagePayload(config.deviceId!!, text, now, config.deviceName)
    return try {
        val topics = resolveSendTopics(config, cipher, ntfy)
        if (topics.isEmpty()) {
            log.w { "no delivery topics resolved for message id=${payload.id}" }
            pipeline.recordError(MESSAGE_SEND_FAILED_MESSAGE)
            return false
        }
        pipeline.send(payload, topics)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        log.w(error) { "message send failed id=${payload.id}" }
        runCatching { pipeline.recordError(MESSAGE_SEND_FAILED_MESSAGE) }
        false
    }
}
