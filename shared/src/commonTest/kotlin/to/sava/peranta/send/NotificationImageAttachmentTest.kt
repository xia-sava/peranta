package to.sava.peranta.send

import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 通知への画像自動添付（§4.3.1）の判定と改版の組み立て。 */
class NotificationImageAttachmentTest {

    private fun notification(
        packageName: String = "com.example.chat",
        title: String = "田中さん",
        text: String = "写真を送りました",
        attachments: List<AttachmentRef> = emptyList(),
        revision: Int = 0,
    ) = NotificationPayload(
        id = "id-1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 2000,
        packageName = packageName,
        appName = "Chat",
        title = title,
        text = text,
        notificationKey = "0|$packageName|1|null|10",
        postedAtEpochMillis = 1000,
        priority = Priority.NORMAL,
        attachments = attachments,
        revision = revision,
    )

    private fun imageRef(blobId: String = "blob-1") = AttachmentRef(
        blobId = blobId,
        url = "https://host/file/$blobId",
        fileName = "notification-1000.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 1),
    )

    /** トグルが ON でセンシティブでない通知には画像を添付する。 */
    @Test
    fun ordinaryNotificationGetsImage() {
        assertTrue(
            shouldAttachNotificationImage(
                payload = notification(),
                attachNotificationImages = true,
                persistSensitiveHistory = false,
            ),
        )
    }

    /** トグルが OFF なら添付しない。 */
    @Test
    fun toggleOffSkipsImage() {
        assertFalse(
            shouldAttachNotificationImage(
                payload = notification(),
                attachNotificationImages = false,
                persistSensitiveHistory = false,
            ),
        )
    }

    /** 履歴で本文を伏せる対象（SMS）には、全文添付と同じく画像も添付しない。 */
    @Test
    fun sensitivePayloadSkipsImage() {
        val sms = SmsPayload(
            id = "id-2",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 2000,
            senderNumber = "09000000000",
            text = "認証コードは 123456 です",
            postedAtEpochMillis = 1000,
        )
        assertFalse(
            shouldAttachNotificationImage(
                payload = sms,
                attachNotificationImages = true,
                persistSensitiveHistory = false,
            ),
        )
    }

    /** 伏せ字を無効にしている端末では、センシティブな payload でも添付する。 */
    @Test
    fun sensitivePayloadGetsImageWhenHistoryKeepsIt() {
        val sms = SmsPayload(
            id = "id-2",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 2000,
            senderNumber = "09000000000",
            text = "認証コードは 123456 です",
            postedAtEpochMillis = 1000,
        )
        assertTrue(
            shouldAttachNotificationImage(
                payload = sms,
                attachNotificationImages = true,
                persistSensitiveHistory = true,
            ),
        )
    }

    /** 改版は画像を末尾に足し、改版番号を 1 つ上げる。id と本文は据え置く。 */
    @Test
    fun revisionAddsImageAndBumpsRevision() {
        val revised = withImageAttachment(notification(), imageRef())

        assertEquals("id-1", revised.id)
        assertEquals("写真を送りました", revised.text)
        assertEquals(listOf("blob-1"), revised.attachments.map { it.blobId })
        assertEquals(1, revised.revision)
    }

    /** 既に全文添付を持つ通知でも、既存の添付を落とさず画像を足す。 */
    @Test
    fun revisionKeepsExistingAttachments()  {
        val fullText = imageRef("blob-text").copy(kind = AttachmentKind.TEXT, mimeType = "text/plain")
        val revised = withImageAttachment(notification(attachments = listOf(fullText)), imageRef())

        assertEquals(listOf("blob-text", "blob-1"), revised.attachments.map { it.blobId })
    }
}
