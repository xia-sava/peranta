package to.sava.peranta.receive

import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.NotificationVisibility
import to.sava.peranta.model.Payload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.ui.firstUrl

/** タイトルが空のときの代替表示。 */
private const val TITLE_FALLBACK = "Peranta"

/** 本文が空のときの代替表示。 */
private const val BODY_FALLBACK = "（本文なし）"

/** 発信元表示の区切り。 */
private const val SOURCE_SEPARATOR = " ・ "

/** 直接受信した SMS を発信元表示で名乗る名前（元のアプリが無いため）。 */
private const val SMS_SOURCE_NAME = "SMS"

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
    /** 発信元の表示名（§3.2）。タイトルだけでは何の通知か分からないため添える。 */
    val source: String? = null,
    /**
     * 元通知のキー。既読同期（§3.4）の対象を指す。これを持つ通知にだけ「送信元の通知を消す」
     * アクションを付ける。対応づいていない SMS・受信メッセージは null で、消す対象を指せない。
     */
    val notificationKey: String? = null,
    /**
     * ロック画面で見せてよい範囲（§3.2）。元通知が運んでこなかった（旧バージョン由来の）ときと、
     * 元通知を持たない受信メッセージは [NotificationVisibility.PRIVATE] とする。
     */
    val visibility: NotificationVisibility = NotificationVisibility.PRIVATE,
)

/**
 * 発信元の表示名（§3.2）。転送元の端末名と、その端末で通知を出したアプリ名をつなぐ。
 * タイトルはアプリごとに意味が異なる（送信者名だったりする）ため、これとは別に持つ。
 */
fun sourceLabelFor(payload: Payload): String? {
    val device = (payload.fromName ?: payload.from).ifBlank { null }
    val app = when (payload) {
        is NotificationPayload -> payload.appName.ifBlank { null }
        is SmsPayload -> SMS_SOURCE_NAME
        else -> null
    }
    return listOfNotNull(device, app)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(SOURCE_SEPARATOR)
}

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
            source = sourceLabelFor(payload),
            notificationKey = payload.notificationKeyOrNull(),
            visibility = payload.visibility ?: NotificationVisibility.PRIVATE,
        )

        is SmsPayload -> NotificationDisplay(
            id = payload.id,
            title = (payload.senderName ?: payload.senderNumber).ifBlank { TITLE_FALLBACK },
            body = payload.text.ifBlank { BODY_FALLBACK },
            priority = payload.priority,
            expiresAtEpochMillis = payload.expiresAtEpochMillis,
            openUrl = firstUrl(payload.text),
            source = sourceLabelFor(payload),
            notificationKey = payload.notificationKeyOrNull(),
        )

        else -> null
    }

/** 受信メッセージを OS 通知の表示内容へ変換する。タイトルが送信元端末名なので発信元は添えない。 */
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
