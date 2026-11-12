package to.sava.peranta.receive

import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.notificationImage
import to.sava.peranta.ui.senderIcon

/** 改版を表示へ反映するための指示（§4.3.1）。 */
data class RevisionRefresh(
    /** 表示内容が変わったので、表示中のものを出し直す。 */
    val redisplay: Boolean,
    /** 取得して差し込む本文画像。無ければ null。 */
    val image: AttachmentRef?,
    /** 取得して差し込む送信者アイコン。無ければ null。 */
    val senderIcon: AttachmentRef?,
)

/**
 * 改版で [previous] から [updated] へ差し替わった受信通知を、表示へどう反映するかを決める（§4.3.1）。
 * 反映することが無ければ null。受信通知以外、元通知が既に消えているもの、
 * 表示内容も画像も変わらないものが該当する。
 *
 * 本文画像か送信者アイコンのどちらかが変わったときは、変わっていない方も含めて差し替え後に
 * 載っているものを返す。差し込みは表示を組み直して出すため、片方だけを渡すともう片方が落ちる。
 */
fun revisionRefreshFor(previous: TimelineItem, updated: TimelineItem): RevisionRefresh? {
    if (previous !is ReceivedNotification || updated !is ReceivedNotification) return null
    if (updated.sourceDismissed) return null
    val display = displayFor(updated) ?: return null
    val redisplay = displayFor(previous) != display
    val imagesChanged = updated.payload.notificationImage() != previous.payload.notificationImage() ||
        updated.payload.senderIcon() != previous.payload.senderIcon()
    val image = updated.payload.notificationImage().takeIf { imagesChanged }
    val senderIcon = updated.payload.senderIcon().takeIf { imagesChanged }
    if (!redisplay && image == null && senderIcon == null) return null
    return RevisionRefresh(redisplay = redisplay, image = image, senderIcon = senderIcon)
}

/**
 * 画像の取得を指示した [refresh] の本文画像・送信者アイコンが、[current] にいま載っているものと同じか（§4.3.1）。
 * 取得の間にさらに改版が届いて載せるべきものが変わっていたら false で、古い改版の結果で表示を上書きしない。
 * [current] が受信通知でなければ false。
 */
fun isRevisionImagesCurrent(refresh: RevisionRefresh, current: TimelineItem): Boolean {
    if (current !is ReceivedNotification) return false
    return refresh.image == current.payload.notificationImage() &&
        refresh.senderIcon == current.payload.senderIcon()
}
