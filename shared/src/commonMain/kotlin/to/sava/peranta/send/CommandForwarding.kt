package to.sava.peranta.send

import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.newPayloadId

/**
 * command の配送特性（§3.4）。失効までの猶予とサーバのキャッシュ保持を、遅れて届いたときに
 * 何が起きるかで分ける。
 *
 * - [IMMEDIATE]: 遅れて実行されると誤爆する操作（アクション発火・返信）。OTP 本文の猶予
 *   （[OTP_TTL_MILLIS]）より短く切り、返信本文がサーバに残る時間も同じだけに抑える。
 * - [STATE_SYNC]: 冪等な取り下げと、恒久的な設定変更（既読同期・denylist の更新）。受信端末が
 *   半日単位で離れていても届く必要があり、遅れて届いても結果は変わらない。保持はサーバの
 *   `cache-duration`（§9）へ委ね、失効はその既定値に合わせる。
 */
enum class CommandDelivery(val ttlMillis: Long, val cacheSeconds: Int?) {
    IMMEDIATE(ttlMillis = 2 * 60 * 1000L, cacheSeconds = HIGH_PRIORITY_CACHE_SECONDS),
    STATE_SYNC(ttlMillis = 24 * 60 * 60 * 1000L, cacheSeconds = null),
}

/** [command] の配送特性（§3.4）。 */
fun deliveryOf(command: CommandType): CommandDelivery = when (command) {
    CommandType.DISMISS,
    CommandType.MUTE_APP,
    CommandType.UNMUTE_APP,
    -> CommandDelivery.STATE_SYNC

    CommandType.INVOKE_ACTION,
    CommandType.REPLY,
    -> CommandDelivery.IMMEDIATE
}

/**
 * 送信元端末への操作コマンドを組み立てる（§3.4）。
 * 失効時刻は送信時刻 [now] + 配送特性（[deliveryOf]）の猶予とし、受信側で期限切れを無視できるようにする。
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
    expiresAtEpochMillis = now + deliveryOf(command).ttlMillis,
)
