package to.sava.peranta.send

import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.newPayloadId

/**
 * command に付ける失効までの猶予（§4）。
 * command は即時操作（dismiss / invokeAction / reply 等）が前提のため、OTP 本文の猶予
 * （[OTP_TTL_MILLIS]）より短くする。大幅に遅延して届いた操作が誤って実行されるのを防ぐ。
 */
const val COMMAND_TTL_MILLIS: Long = 2 * 60 * 1000L

/**
 * 送信元端末への操作コマンドを組み立てる（§3.4）。
 * 失効時刻は送信時刻 [now] + [COMMAND_TTL_MILLIS] とし、受信側で期限切れを無視できるようにする。
 */
fun buildCommandPayload(
    command: CommandType,
    from: String,
    to: String,
    now: Long,
    targetNotificationKey: String? = null,
    actionIndex: Int? = null,
    replyText: String? = null,
    packageName: String? = null,
    idGen: () -> String = ::newPayloadId,
): CommandPayload = CommandPayload(
    id = idGen(),
    from = from,
    to = to,
    sentAtEpochMillis = now,
    command = command,
    targetNotificationKey = targetNotificationKey,
    actionIndex = actionIndex,
    replyText = replyText,
    packageName = packageName,
    expiresAtEpochMillis = now + COMMAND_TTL_MILLIS,
)
