package to.sava.peranta.receive

import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.CommandAckPayload
import to.sava.peranta.model.CommandOutcome
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.send.CommandDelivery

/** コマンドへの応答（§3.4）を [topic] へ送る口。実行端末の受信中核（[ReceivePipeline]）が使う。 */
fun interface CommandAcknowledger {
    suspend fun acknowledge(topic: String, ack: CommandAckPayload)
}

/**
 * 応答を封緘して ntfy へ publish する [CommandAcknowledger]。
 * 応答は遅れて届いても送信端末はもう待っていないため、即時操作（[CommandDelivery.IMMEDIATE]）と同じく
 * サーバの保持を短くする。
 */
fun publishingCommandAcknowledger(cipher: MessageCipher, ntfy: NtfyClient): CommandAcknowledger =
    CommandAcknowledger { topic, ack ->
        ntfy.publish(topic, encodeEnvelope(cipher.seal(ack)), CommandDelivery.IMMEDIATE.cacheSeconds)
    }

/** [command] への応答を組む。宛先はコマンドの送信端末で、失効は即時操作と揃える。 */
fun buildCommandAckPayload(
    command: CommandPayload,
    from: String,
    outcome: CommandOutcome,
    message: String?,
    now: Long,
    idGen: () -> String = ::newPayloadId,
): CommandAckPayload = CommandAckPayload(
    id = idGen(),
    from = from,
    to = command.from,
    sentAtEpochMillis = now,
    commandId = command.id,
    command = command.command,
    outcome = outcome,
    message = message,
    expiresAtEpochMillis = now + CommandDelivery.IMMEDIATE.ttlMillis,
)
