package to.sava.peranta.toast

import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.ui.firstUrl

/** タイトルが空のときの代替（SnoreToast はタイトル・本文が空だと表示しない）。 */
private const val TITLE_FALLBACK = "Peranta"

/** 本文が空のときの代替。 */
private const val BODY_FALLBACK = "（本文なし）"

/** エラー通知トーストのタイトル。 */
private const val ERROR_TITLE = "Peranta 受信エラー"

/** ファイル受信通知トーストのタイトル。 */
private const val FILE_RECEIVED_TITLE = "ファイルを受信しました"

/** ファイル名が空のときの代替。 */
private const val FILE_NAME_FALLBACK = "ファイル"

/**
 * 受信通知アイテムをトースト表示内容へ変換する。表示対象外の payload は null を返す。
 * 本文から URL が抽出できれば [ReceivedNotificationToast.openUrl] に詰め、「開く」ボタンの表示に使う（§3.3）。
 */
fun toastContentFor(item: ReceivedNotification): ReceivedNotificationToast? =
    when (val payload = item.payload) {
        is NotificationPayload -> ReceivedNotificationToast(
            id = item.id,
            title = payload.title.ifBlank { payload.appName.ifBlank { TITLE_FALLBACK } },
            body = payload.text.ifBlank { BODY_FALLBACK },
            openUrl = firstUrl("${payload.title} ${payload.text}"),
        )

        is SmsPayload -> ReceivedNotificationToast(
            id = item.id,
            title = (payload.senderName ?: payload.senderNumber).ifBlank { TITLE_FALLBACK },
            body = payload.text.ifBlank { BODY_FALLBACK },
            openUrl = firstUrl(payload.text),
        )

        else -> null
    }

/** エラーアイテムを軽い通知トーストへ変換する（§10.1 のローカル通知）。 */
fun toastContentFor(item: ErrorItem): ReceivedNotificationToast =
    ReceivedNotificationToast(
        id = item.id,
        title = ERROR_TITLE,
        body = item.message.ifBlank { BODY_FALLBACK },
    )

/**
 * 受信ファイルを「届いた」ことだけ知らせる軽いトーストへ変換する（§4.3、判断4）。
 * 自動ダウンロードはせず、先頭ファイル名（複数なら「ほか N 件」）を本文に載せる。
 */
fun toastContentFor(item: ReceivedFile): ReceivedNotificationToast {
    val attachments = item.payload.attachments
    val firstName = attachments.firstOrNull()?.fileName?.ifBlank { null } ?: FILE_NAME_FALLBACK
    val body = if (attachments.size > 1) "$firstName ほか ${attachments.size - 1} 件" else firstName
    return ReceivedNotificationToast(id = item.id, title = FILE_RECEIVED_TITLE, body = body)
}

/** 受信メッセージをトースト表示内容へ変換する。タイトルは送信元端末名。 */
fun toastContentFor(item: ReceivedMessage): ReceivedNotificationToast =
    ReceivedNotificationToast(
        id = item.id,
        title = (item.payload.fromName ?: item.payload.from).ifBlank { TITLE_FALLBACK },
        body = item.payload.text.ifBlank { BODY_FALLBACK },
    )
