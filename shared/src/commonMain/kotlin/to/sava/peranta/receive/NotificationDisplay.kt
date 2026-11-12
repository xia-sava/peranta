package to.sava.peranta.receive

import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.NotificationVisibility
import to.sava.peranta.model.Payload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.SwipeBehavior
import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.model.swipeBehaviorOrDefault
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.errorTitleOf
import to.sava.peranta.ui.firstUrl

/** 「開く」アクションのラベル（§3.2 / §3.3）。 */
const val OPEN_URL_ACTION_LABEL: String = "開く"

/** 「送信元の通知を消す」のラベル（§3.2 / §3.3 / §10.1）。 */
const val DISMISS_SOURCE_ACTION_LABEL: String = "送信元の通知を消す"

/** タイトルが空のときの代替表示。 */
private const val TITLE_FALLBACK = "Peranta"

/** 本文が空のときの代替表示。 */
private const val BODY_FALLBACK = "（本文なし）"

/** ファイル受信を知らせる表示のタイトル。 */
private const val FILE_RECEIVED_TITLE = "ファイルを受信しました"

/** ファイル名が空のときの代替表示。 */
private const val FILE_NAME_FALLBACK = "ファイル"

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
    /**
     * タイムラインのアイテムの時刻（受信したアイテムは受信時刻、エラーは記録した時刻）。
     * OS 通知の時刻に当てる。改版で差し替わっても変わらないため、出し直しても同じ値になる（§4.3.1）。
     */
    val timestampEpochMillis: Long,
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
     * 払いのけたときの扱い（§3.3）。送信端末がアプリごとの設定から決めて運んできた指示で、
     * 既定は表示を引っ込めるだけの [SwipeBehavior.LOCAL_ONLY]。
     */
    val swipeBehavior: SwipeBehavior = SwipeBehavior.LOCAL_ONLY,
    /**
     * ロック画面で見せてよい範囲（§3.2）。元通知が運んでこなかった（旧バージョン由来の）ときと、
     * 元通知を持たない受信メッセージ・受信ファイルは [NotificationVisibility.PRIVATE] とする。
     */
    val visibility: NotificationVisibility = NotificationVisibility.PRIVATE,
    /** 受信アイテムではなくエラーの知らせか（§10.1）。 */
    val isError: Boolean = false,
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
            timestampEpochMillis = item.timestampEpochMillis,
            openUrl = firstUrl("${payload.title} ${payload.text}"),
            source = sourceLabelFor(payload),
            notificationKey = payload.notificationKeyOrNull(),
            swipeBehavior = payload.swipeBehaviorOrDefault(),
            visibility = payload.visibility ?: NotificationVisibility.PRIVATE,
        )

        is SmsPayload -> NotificationDisplay(
            id = payload.id,
            title = (payload.senderName ?: payload.senderNumber).ifBlank { TITLE_FALLBACK },
            body = payload.text.ifBlank { BODY_FALLBACK },
            priority = payload.priority,
            expiresAtEpochMillis = payload.expiresAtEpochMillis,
            timestampEpochMillis = item.timestampEpochMillis,
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
    timestampEpochMillis = item.timestampEpochMillis,
)

/**
 * 受信ファイルを「届いた」ことだけ知らせる表示内容へ変換する（§4.3）。
 * 本文は先頭ファイル名（複数なら「ほか N 件」）で、キャプションがあれば次の行に続ける。
 * 送信元の端末名を発信元として添える。
 */
fun displayFor(item: ReceivedFile): NotificationDisplay {
    val attachments = item.payload.attachments
    val firstName = attachments.firstOrNull()?.fileName?.ifBlank { null } ?: FILE_NAME_FALLBACK
    val files = if (attachments.size > 1) "$firstName ほか ${attachments.size - 1} 件" else firstName
    return NotificationDisplay(
        id = item.payload.id,
        title = FILE_RECEIVED_TITLE,
        body = listOfNotNull(files, item.payload.caption?.ifBlank { null }).joinToString("\n"),
        priority = item.payload.priority,
        expiresAtEpochMillis = item.payload.expiresAtEpochMillis,
        timestampEpochMillis = item.timestampEpochMillis,
        source = sourceLabelFor(item.payload),
    )
}

/**
 * エラーを通知で知らせるときの表示内容（§10.1）。優先度は NORMAL で、失効せず、
 * 発信元・元通知のキー・「開く」の URL は持たない。
 */
fun displayFor(item: ErrorItem): NotificationDisplay = NotificationDisplay(
    id = item.id,
    title = errorTitleOf(item.kind),
    body = item.message.ifBlank { BODY_FALLBACK },
    priority = Priority.NORMAL,
    expiresAtEpochMillis = null,
    timestampEpochMillis = item.timestampEpochMillis,
    isError = true,
)

/**
 * タイムラインに載った [item] を通知で知らせるときの表示内容。知らせない種別は null。
 * 元通知が既に消えている受信通知も null（取り下げる相手がいない、§3.3）。
 */
fun notificationDisplayFor(item: TimelineItem): NotificationDisplay? = when (item) {
    is ReceivedNotification -> if (item.sourceDismissed) null else displayFor(item)
    is ReceivedMessage -> displayFor(item)
    is ReceivedFile -> displayFor(item)
    is ErrorItem -> displayFor(item)
    else -> null
}

/** 通知チャネルの区分。受信は優先度から一意に定まり、OS の重要度へ対応させる（§4）。エラーは専用の区分。 */
enum class NotificationChannelKind {
    HIGH,
    NORMAL,
    LOW,
    ERROR,
}

/** 優先度に対応する通知チャネル区分を返す。 */
fun channelKindFor(priority: Priority): NotificationChannelKind = when (priority) {
    Priority.HIGH -> NotificationChannelKind.HIGH
    Priority.NORMAL -> NotificationChannelKind.NORMAL
    Priority.LOW -> NotificationChannelKind.LOW
}

/** 表示内容に対応する通知チャネル区分。エラーは優先度に依らず [NotificationChannelKind.ERROR]。 */
fun channelKindFor(display: NotificationDisplay): NotificationChannelKind =
    if (display.isError) NotificationChannelKind.ERROR else channelKindFor(display.priority)
