package to.sava.peranta.send

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PerantaJson
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.SentNotification

/** 再送メタが表す payload の種別。 */
@Serializable
enum class RetryDisplayKind {
    @SerialName("notification")
    NOTIFICATION,

    @SerialName("sms")
    SMS,
}

/**
 * 再送成功時にタイムラインへ送信済みとして記録するための最小表示メタ（§3.1）。
 * 本文（text）は含めず、表示に要る情報だけを運ぶ。この値は publish せず、
 * 端末内の WorkManager DB に閉じる（ワイヤには暗号文 Envelope のみを出す）。
 */
@Serializable
data class RetryDisplayMeta(
    val kind: RetryDisplayKind,
    val id: String,
    val sentAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val displayName: String,
    val title: String,
    val priority: Priority,
)

/**
 * 永続化調整済みの [payload] から表示メタを取り出す。本文は取り込まない。
 * 再送経路に乗らない種別（command/presence）は null を返す。
 */
fun retryDisplayMetaOf(payload: Payload): RetryDisplayMeta? = when (payload) {
    is NotificationPayload -> RetryDisplayMeta(
        kind = RetryDisplayKind.NOTIFICATION,
        id = payload.id,
        sentAtEpochMillis = payload.sentAtEpochMillis,
        expiresAtEpochMillis = payload.expiresAtEpochMillis,
        displayName = payload.appName,
        title = payload.title,
        priority = payload.priority,
    )

    is SmsPayload -> RetryDisplayMeta(
        kind = RetryDisplayKind.SMS,
        id = payload.id,
        sentAtEpochMillis = payload.sentAtEpochMillis,
        expiresAtEpochMillis = payload.expiresAtEpochMillis,
        displayName = payload.senderName ?: payload.senderNumber,
        title = "",
        priority = payload.priority,
    )

    else -> null
}

/**
 * 表示メタから送信済みタイムライン項目を組み立てる。本文は持たないため空にする。
 * [from] は送信端末名、[timestamp] は記録時刻とする。
 */
fun RetryDisplayMeta.toSentNotification(from: String, timestamp: Long): SentNotification {
    val payload: Payload = when (kind) {
        RetryDisplayKind.NOTIFICATION -> NotificationPayload(
            id = id,
            from = from,
            to = BROADCAST_TARGET,
            sentAtEpochMillis = sentAtEpochMillis,
            packageName = "",
            appName = displayName,
            title = title,
            text = "",
            notificationKey = "",
            postedAtEpochMillis = sentAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            priority = priority,
        )

        RetryDisplayKind.SMS -> SmsPayload(
            id = id,
            from = from,
            to = BROADCAST_TARGET,
            sentAtEpochMillis = sentAtEpochMillis,
            senderNumber = "",
            senderName = displayName,
            text = "",
            postedAtEpochMillis = sentAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            priority = priority,
        )
    }
    return SentNotification(
        id = id,
        timestampEpochMillis = timestamp,
        payload = payload,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )
}

/** 表示メタを WorkManager 入力に載せる JSON へ符号化する。 */
fun encodeRetryDisplayMeta(meta: RetryDisplayMeta): String = PerantaJson.encodeToString(meta)

/** WorkManager 入力の JSON から表示メタを復号する。 */
fun decodeRetryDisplayMeta(json: String): RetryDisplayMeta = PerantaJson.decodeFromString(json)
