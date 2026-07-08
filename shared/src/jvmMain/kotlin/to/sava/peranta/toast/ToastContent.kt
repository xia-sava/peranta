package to.sava.peranta.toast

import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedNotification

/** タイトルが空のときの代替（SnoreToast はタイトル・本文が空だと表示しない）。 */
private const val TITLE_FALLBACK = "Peranta"

/** 本文が空のときの代替。 */
private const val BODY_FALLBACK = "（本文なし）"

/** エラー通知トーストのタイトル。 */
private const val ERROR_TITLE = "Peranta 受信エラー"

/** 受信通知アイテムをトースト表示内容へ変換する。表示対象外の payload は null を返す。 */
fun toastContentFor(item: ReceivedNotification): ReceivedNotificationToast? =
    when (val payload = item.payload) {
        is NotificationPayload -> ReceivedNotificationToast(
            id = item.id,
            title = payload.title.ifBlank { payload.appName.ifBlank { TITLE_FALLBACK } },
            body = payload.text.ifBlank { BODY_FALLBACK },
        )

        is SmsPayload -> ReceivedNotificationToast(
            id = item.id,
            title = (payload.senderName ?: payload.senderNumber).ifBlank { TITLE_FALLBACK },
            body = payload.text.ifBlank { BODY_FALLBACK },
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
