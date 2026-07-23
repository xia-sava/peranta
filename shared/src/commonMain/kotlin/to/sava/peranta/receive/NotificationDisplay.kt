package to.sava.peranta.receive

import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.ui.firstUrl

/** タイトルが空のときの代替表示。 */
private const val TITLE_FALLBACK = "Peranta"

/** 本文が空のときの代替表示。 */
private const val BODY_FALLBACK = "（本文なし）"

/**
 * 受信通知を OS 通知として表示するための、プラットフォーム非依存の表示内容。
 * 本文の伏せ字（§11）は送信側で適用済みのため、ここでは payload の値をそのまま用いる。
 */
data class NotificationDisplay(
    /** payload.id。ローカル通知 ID との対応付け（§3.4 の既読同期）に使う。 */
    val id: String,
    val title: String,
    val body: String,
    val priority: Priority,
    /** 失効時刻。設定時は表示後にこの時刻で自動的に消せる（OTP の短寿命）。 */
    val expiresAtEpochMillis: Long?,
    /** 本文から抽出した先頭 URL。あれば「開く」アクションを追加する（§3.2）。 */
    val openUrl: String? = null,
)

/**
 * 受信通知アイテムを [NotificationDisplay] へ変換する。
 * 通知・SMS 以外の payload（command / presence 等）は表示対象外として null を返す。
 */
fun displayFor(item: ReceivedNotification): NotificationDisplay? =
    when (val payload = item.payload) {
        is NotificationPayload -> NotificationDisplay(
            id = payload.id,
            title = payload.title.ifBlank { payload.appName.ifBlank { TITLE_FALLBACK } },
            body = payload.text.ifBlank { BODY_FALLBACK },
            priority = payload.priority,
            expiresAtEpochMillis = payload.expiresAtEpochMillis,
            openUrl = firstUrl("${payload.title} ${payload.text}"),
        )

        is SmsPayload -> NotificationDisplay(
            id = payload.id,
            title = (payload.senderName ?: payload.senderNumber).ifBlank { TITLE_FALLBACK },
            body = payload.text.ifBlank { BODY_FALLBACK },
            priority = payload.priority,
            expiresAtEpochMillis = payload.expiresAtEpochMillis,
            openUrl = firstUrl(payload.text),
        )

        else -> null
    }

/** 受信メッセージを OS 通知の表示内容へ変換する。タイトルは送信元端末名。 */
fun displayFor(item: ReceivedMessage): NotificationDisplay = NotificationDisplay(
    id = item.payload.id,
    title = (item.payload.fromName ?: item.payload.from).ifBlank { TITLE_FALLBACK },
    body = item.payload.text.ifBlank { BODY_FALLBACK },
    priority = Priority.NORMAL,
    expiresAtEpochMillis = null,
)

/** 通知チャネルの区分。優先度から一意に定まり、OS の重要度へ対応させる（§4）。 */
enum class NotificationChannelKind {
    HIGH,
    NORMAL,
    LOW,
}

/** 優先度に対応する通知チャネル区分を返す。 */
fun channelKindFor(priority: Priority): NotificationChannelKind = when (priority) {
    Priority.HIGH -> NotificationChannelKind.HIGH
    Priority.NORMAL -> NotificationChannelKind.NORMAL
    Priority.LOW -> NotificationChannelKind.LOW
}
