package to.sava.peranta.toast

import to.sava.peranta.model.ActionExecutionKind
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.actionKindAt
import to.sava.peranta.receive.notificationDisplayFor
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem

/**
 * タイムラインのアイテムをトースト表示内容へ変換する。トーストで知らせない種別と、元通知が
 * 既に消えている受信通知は null を返す。
 * 種別ごとの文言と知らせるかどうかの規則は OS 通知（§3.2）と共通のため [notificationDisplayFor] に委ねる。
 * 受信通知にだけ、元通知のアクションを載せる。
 */
fun toastContentFor(item: TimelineItem): ReceivedNotificationToast? =
    notificationDisplayFor(item)?.let {
        ReceivedNotificationToast(
            id = it.id,
            title = it.title,
            body = it.body,
            source = it.source,
            openUrl = it.openUrl,
            actions = if (item is ReceivedNotification) toastActionsFor(item.payload) else emptyList(),
        )
    }

/**
 * トーストに載せる元通知のアクションを選ぶ（§3.3）。押した結果が発出元で完結するもの
 * （[ActionExecutionKind.SENDER_EFFECT]）と、この端末で本文を入力して送るもの
 * （[ActionExecutionKind.REPLY]）を採る。押しても発出元の画面が開くだけのものと、分類する材料が
 * 無いものは落とす。手元で結果を確かめられない操作をトーストへ並べても空振りになるため。
 * 落としたアクションもタイムラインのバブル（§10.1）には並ぶので操作手段は残る。
 */
private fun toastActionsFor(payload: Payload): List<ToastAction> {
    val notification = payload as? NotificationPayload ?: return emptyList()
    return notification.actions.mapIndexedNotNull { index, label ->
        if (label.isBlank()) return@mapIndexedNotNull null
        when (notification.actionKindAt(index)) {
            ActionExecutionKind.SENDER_EFFECT -> ToastAction(index = index, label = label)
            ActionExecutionKind.REPLY -> ToastAction(index = index, label = label, needsInput = true)
            else -> null
        }
    }
}

/**
 * 表示していた [action] が、押した時点の [payload] でも同じ位置に同じ名前で残っているか（§3.3）。
 * トーストは操作するまで残るため、その間に元通知が差し替わってアクションの並びが変わることがある。
 * 位置だけで発火すると別の操作を起こしてしまうので、送る前にこれで確かめる。
 */
fun isActionStillOffered(payload: NotificationPayload, action: ToastAction): Boolean =
    payload.actions.getOrNull(action.index) == action.label
