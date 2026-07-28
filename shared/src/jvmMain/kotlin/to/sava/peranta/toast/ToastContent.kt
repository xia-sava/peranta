package to.sava.peranta.toast

import to.sava.peranta.receive.displayFor
import to.sava.peranta.receive.sourceLabelFor
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification

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
 * 文言の組み立ては OS 通知（§3.2）と共通のため [displayFor] に委ねる。
 */
fun toastContentFor(item: ReceivedNotification): ReceivedNotificationToast? =
    displayFor(item)?.let {
        ReceivedNotificationToast(
            id = it.id,
            title = it.title,
            body = it.body,
            source = it.source,
            openUrl = it.openUrl,
        )
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
    return ReceivedNotificationToast(
        id = item.id,
        title = FILE_RECEIVED_TITLE,
        body = body,
        source = sourceLabelFor(item.payload),
    )
}

/** 受信メッセージをトースト表示内容へ変換する。タイトルは送信元端末名。 */
fun toastContentFor(item: ReceivedMessage): ReceivedNotificationToast =
    displayFor(item).let {
        ReceivedNotificationToast(id = it.id, title = it.title, body = it.body)
    }
