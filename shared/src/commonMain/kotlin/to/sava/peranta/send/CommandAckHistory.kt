package to.sava.peranta.send

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.CommandAckPayload
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.receive.normalizeReceivedPayload

/**
 * 応答の送り先（自端末の受信 topic）に残っている応答を読み直す口（§3.4）。
 * [sinceEpochMillis] 以降に届いた応答を返し、読み直せなかったときは null を返す。
 */
typealias CommandAckHistory = suspend (topic: String, sinceEpochMillis: Long) -> List<CommandAckPayload>?

/**
 * ntfy のメッセージ履歴から応答を読み直す [CommandAckHistory]。自端末の購読が切れていて届かなかった
 * 応答も、サーバの保持（60 秒）のうちなら HTTP で拾える。復号できない・応答でないメッセージは読み飛ばし、
 * 拾った応答には受信時と同じ関門（[normalizeReceivedPayload]）を当てる。
 * 例外は種別名だけを残す（ktor の例外メッセージには接続先 URL（＝topic）が載る、§16）。
 */
fun ntfyCommandAckHistory(
    ntfy: NtfyClient,
    cipher: MessageCipher,
    log: Logger = Logger.withTag("CommandAck"),
): CommandAckHistory = { topic, sinceEpochMillis ->
    try {
        ntfy.fetchHistory(topic, since = (sinceEpochMillis / MILLIS_PER_SECOND).toString())
            .mapNotNull { event -> openAckOrNull(cipher, event.message) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        log.w { "failed to re-read command acks (${error::class.simpleName})" }
        null
    }
}

private const val MILLIS_PER_SECOND = 1000L

private suspend fun openAckOrNull(cipher: MessageCipher, message: String): CommandAckPayload? =
    try {
        normalizeReceivedPayload(cipher.open(decodeEnvelope(message))) as? CommandAckPayload
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }
