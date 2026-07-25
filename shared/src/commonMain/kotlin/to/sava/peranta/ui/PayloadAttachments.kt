package to.sava.peranta.ui

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem

/** payload に含まれる全文添付（kind=TEXT、§4.3）を返す。無ければ null。 */
internal fun Payload.fullTextAttachment(): AttachmentRef? =
    attachmentsOf(this).firstOrNull { it.kind == AttachmentKind.TEXT }

/** payload に含まれる画像・ファイル添付を返す。全文添付は本文の展開が扱うため除く。 */
internal fun Payload.displayAttachments(): List<AttachmentRef> =
    attachmentsOf(this).filterNot { it.kind == AttachmentKind.TEXT }

/**
 * タイムラインアイテムが持つ画像・ファイル添付を返す。
 * ダウンロード状態のプライムなど、アイテム種別を問わず添付を辿りたい箇所で使う。
 */
internal fun TimelineItem.displayAttachments(): List<AttachmentRef> = when (this) {
    is ReceivedFile -> payload.displayAttachments()
    is ReceivedNotification -> payload.displayAttachments()
    else -> emptyList()
}

private fun attachmentsOf(payload: Payload): List<AttachmentRef> = when (payload) {
    is NotificationPayload -> payload.attachments
    is SmsPayload -> payload.attachments
    is FilePayload -> payload.attachments
    else -> emptyList()
}
