package to.sava.peranta.ui

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayloadAttachmentsTest {

    private fun attachment(blobId: String, kind: AttachmentKind = AttachmentKind.IMAGE) = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "$blobId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = kind,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
    )

    private fun receivedFile(vararg blobIds: String) = ReceivedFile(
        id = "f1",
        timestampEpochMillis = 1_000L,
        payload = FilePayload(
            id = "f1",
            from = "desk",
            to = "*",
            sentAtEpochMillis = 900L,
            attachments = blobIds.map { attachment(it) },
            postedAtEpochMillis = 900L,
        ),
    )

    private fun receivedNotification(vararg attachments: AttachmentRef) = ReceivedNotification(
        id = "n1",
        timestampEpochMillis = 1_000L,
        payload = NotificationPayload(
            id = "n1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            packageName = "com.example.news",
            appName = "News",
            title = "見出し",
            text = "本文",
            notificationKey = "0|com.example.news|1|null|10",
            postedAtEpochMillis = 900L,
            attachments = attachments.toList(),
        ),
    )

    /** 受信ファイルの添付を blobId で引ける。 */
    @Test
    fun findsAttachmentOfReceivedFile() {
        val items: List<TimelineItem> = listOf(receivedFile("blob-a", "blob-b"))
        assertEquals("blob-b", findAttachmentRef(items, "blob-b")?.blobId)
    }

    /** 通知に付いた画像の添付も blobId で引ける。 */
    @Test
    fun findsImageAttachedToNotification() {
        val items: List<TimelineItem> = listOf(receivedFile("blob-a"), receivedNotification(attachment("blob-n")))
        assertEquals("blob-n", findAttachmentRef(items, "blob-n")?.blobId)
    }

    /** 全文添付は本文の展開が扱うため、添付の操作の対象として引かない。 */
    @Test
    fun doesNotFindFullTextAttachment() {
        val items: List<TimelineItem> = listOf(receivedNotification(attachment("blob-t", AttachmentKind.TEXT)))
        assertNull(findAttachmentRef(items, "blob-t"))
    }

    /** どのアイテムにも無い blobId は null になる。 */
    @Test
    fun returnsNullForUnknownBlob() {
        assertNull(findAttachmentRef(listOf(receivedFile("blob-a")), "blob-x"))
    }

    /** 通知に載せる本文画像は、kind が IMAGE の先頭の添付になる。 */
    @Test
    fun notificationImageIsFirstImageKindAttachment() {
        val payload = receivedNotification(
            attachment("blob-file", AttachmentKind.FILE),
            attachment("blob-image"),
        ).payload
        assertEquals("blob-image", payload.notificationImage()?.blobId)
    }

    /** MIME が画像でも kind が FILE の添付は、通知に載せる本文画像に選ばない。 */
    @Test
    fun notificationImageIgnoresFileKindEvenWithImageMime() {
        val payload = receivedNotification(attachment("blob-file", AttachmentKind.FILE)).payload
        assertNull(payload.notificationImage())
    }
}
