package to.sava.peranta.send

import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.newPayloadId

/** 共有された画像・ファイルのキャプションに載せる UTF-8 バイト予算（本文と同じ配分に倣う）。 */
const val MAX_CAPTION_BYTES: Int = MAX_FORWARDED_TEXT_BYTES

/**
 * アップロード済みの [attachments] を [FilePayload] に組み立てる（§4.3）。
 * [caption] は切り詰めたうえで載せ、空なら null にする。宛先は全端末（`to: "*"`）とする。
 */
fun buildFilePayload(
    deviceId: String,
    attachments: List<AttachmentRef>,
    now: Long,
    caption: String? = null,
    priority: Priority = Priority.NORMAL,
    idGen: () -> String = ::newPayloadId,
): FilePayload {
    require(attachments.isNotEmpty()) { "FilePayload requires at least one attachment" }
    val trimmedCaption = caption
        ?.let { truncateForForwarding(it, MAX_CAPTION_BYTES) }
        ?.takeIf { it.isNotBlank() }
    return FilePayload(
        id = idGen(),
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = now,
        caption = trimmedCaption,
        attachments = attachments,
        postedAtEpochMillis = now,
        priority = priority,
    )
}
