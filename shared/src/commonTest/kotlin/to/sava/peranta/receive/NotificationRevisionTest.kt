package to.sava.peranta.receive

import to.sava.peranta.filter.payloadForPersistence
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRevisionTest {

    private fun received(payload: Payload) = ReceivedNotification(
        id = payload.id,
        timestampEpochMillis = 1_000L,
        payload = payload,
        expiresAtEpochMillis = null,
    )

    private fun attachment(blobId: String, kind: AttachmentKind = AttachmentKind.IMAGE) = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "$blobId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = kind,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 1),
    )

    private fun notification(
        attachments: List<AttachmentRef> = emptyList(),
        senderIcon: AttachmentRef? = null,
        revision: Int = 0,
        text: String = "写真を送りました",
    ) = NotificationPayload(
        id = "n1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 900L,
        packageName = "com.example.chat",
        appName = "Chat",
        title = "相手",
        text = text,
        notificationKey = "0|com.example.chat|1|null|10",
        postedAtEpochMillis = 900L,
        attachments = attachments,
        senderIcon = senderIcon,
        revision = revision,
    )

    private fun sms(notificationKey: String? = null, revision: Int = 0) = SmsPayload(
        id = "s1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 900L,
        senderNumber = "+81900000000",
        text = "コードは 123456 です",
        postedAtEpochMillis = 900L,
        notificationKey = notificationKey,
        revision = revision,
    )

    /** 元通知の key が付いた SMS の改版は表示内容が変わるため、出し直しを指示する（§3.1）。 */
    @Test
    fun smsRevisionWithNotificationKeyRedisplays() {
        val refresh = revisionRefreshFor(
            received(sms()),
            received(sms(notificationKey = "0|com.android.messaging|7|null|10", revision = 1)),
        )
        assertEquals(RevisionRefresh(redisplay = true, image = null, senderIcon = null), refresh)
    }

    /** 本文画像だけを足した改版は、表示内容は変わらず、取得して差し込む画像を返す。 */
    @Test
    fun imageOnlyRevisionReturnsImageWithoutRedisplay() {
        val image = attachment("blob-image")
        val refresh = revisionRefreshFor(
            received(notification()),
            received(notification(attachments = listOf(image), revision = 1)),
        )
        assertEquals(RevisionRefresh(redisplay = false, image = image, senderIcon = null), refresh)
    }

    /** 送信者アイコンだけを足した改版は、取得して差し込むアイコンを返す。 */
    @Test
    fun senderIconOnlyRevisionReturnsSenderIcon() {
        val icon = attachment("blob-icon")
        val refresh = revisionRefreshFor(
            received(notification()),
            received(notification(senderIcon = icon, revision = 1)),
        )
        assertEquals(RevisionRefresh(redisplay = false, image = null, senderIcon = icon), refresh)
    }

    /** 本文画像を足した改版では、既に載っていた送信者アイコンも返す（組み直しで落とさないため）。 */
    @Test
    fun imageRevisionAlsoReturnsUnchangedSenderIcon() {
        val icon = attachment("blob-icon")
        val image = attachment("blob-image")
        val refresh = revisionRefreshFor(
            received(notification(senderIcon = icon)),
            received(notification(attachments = listOf(image), senderIcon = icon, revision = 1)),
        )
        assertEquals(RevisionRefresh(redisplay = false, image = image, senderIcon = icon), refresh)
    }

    /** 送信者アイコンを足した改版では、既に載っていた本文画像も返す（組み直しで落とさないため）。 */
    @Test
    fun senderIconRevisionAlsoReturnsUnchangedImage() {
        val icon = attachment("blob-icon")
        val image = attachment("blob-image")
        val refresh = revisionRefreshFor(
            received(notification(attachments = listOf(image))),
            received(notification(attachments = listOf(image), senderIcon = icon, revision = 1)),
        )
        assertEquals(RevisionRefresh(redisplay = false, image = image, senderIcon = icon), refresh)
    }

    /** 表示内容と本文画像が同時に変わる改版は、出し直しと画像の取得の両方を指示する。 */
    @Test
    fun displayAndImageChangedTogether() {
        val image = attachment("blob-image")
        val refresh = revisionRefreshFor(
            received(notification()),
            received(notification(attachments = listOf(image), revision = 1, text = "写真を 2 枚送りました")),
        )
        assertEquals(RevisionRefresh(redisplay = true, image = image, senderIcon = null), refresh)
    }

    /** 差し替え前が伏せ字済み（履歴から読み直した本文）なら、本文が違うので出し直しを指示する。 */
    @Test
    fun redactedPreviousRedisplays() {
        val refresh = revisionRefreshFor(
            received(payloadForPersistence(sms(), keepSensitive = false)),
            received(sms(revision = 1)),
        )
        assertEquals(true, refresh?.redisplay)
    }

    /** 表示対象外の payload（command など）の受信通知は反映の対象にならない。 */
    @Test
    fun nonDisplayablePayloadIsNotReflected() {
        val command = received(
            CommandPayload(
                id = "c1",
                from = "desk",
                to = "phone",
                sentAtEpochMillis = 900L,
                command = CommandType.DISMISS,
                targetNotificationKey = "0|com.example|1|null|10",
            ),
        )
        assertNull(revisionRefreshFor(command, command))
    }

    /** 取得を指示した画像・アイコンが、その時点のアイテムに載っているものと同じなら差し込んでよい。 */
    @Test
    fun imagesAreCurrentWhenUnchangedSinceFetch() {
        val image = attachment("blob-image")
        val updated = received(notification(attachments = listOf(image), revision = 1))
        val refresh = revisionRefreshFor(received(notification()), updated)!!
        assertTrue(isRevisionImagesCurrent(refresh, updated))
    }

    /** 取得の間にさらに改版が届いて本文画像が変わっていたら、古い改版の結果で差し込まない。 */
    @Test
    fun imagesAreNotCurrentAfterNewerRevision() {
        val updated = received(notification(attachments = listOf(attachment("blob-old")), revision = 1))
        val refresh = revisionRefreshFor(received(notification()), updated)!!
        val newer = received(notification(attachments = listOf(attachment("blob-new")), revision = 2))
        assertFalse(isRevisionImagesCurrent(refresh, newer))
    }

    /** その時点のアイテムが受信通知でなければ差し込まない。 */
    @Test
    fun imagesAreNotCurrentForNonNotificationItem() {
        val refresh = RevisionRefresh(redisplay = false, image = attachment("blob-image"), senderIcon = null)
        val file = ReceivedFile(
            id = "n1",
            timestampEpochMillis = 1_000L,
            payload = FilePayload(
                id = "n1",
                from = "desk",
                to = "*",
                sentAtEpochMillis = 900L,
                attachments = listOf(attachment("blob-image")),
                postedAtEpochMillis = 900L,
            ),
        )
        assertFalse(isRevisionImagesCurrent(refresh, file))
    }

    /** 本文画像に選ばれるのは kind が IMAGE の添付で、ファイル添付だけの改版は反映しない。 */
    @Test
    fun fileAttachmentRevisionIsNotReflected() {
        val refresh = revisionRefreshFor(
            received(notification()),
            received(notification(attachments = listOf(attachment("blob-file", AttachmentKind.FILE)), revision = 1)),
        )
        assertNull(refresh)
    }

    /** 表示内容も画像も変わらない改版は、反映することが無い。 */
    @Test
    fun unchangedRevisionIsNotReflected() {
        val image = attachment("blob-image")
        val refresh = revisionRefreshFor(
            received(notification(attachments = listOf(image), revision = 1)),
            received(notification(attachments = listOf(image), revision = 2)),
        )
        assertNull(refresh)
    }

    /** 元通知が既に消えている受信通知の改版は、出し直しも差し込みもしない。 */
    @Test
    fun sourceDismissedRevisionIsNotReflected() {
        val refresh = revisionRefreshFor(
            received(sms()).copy(sourceDismissed = true),
            received(sms(notificationKey = "0|com.android.messaging|7|null|10", revision = 1))
                .copy(sourceDismissed = true),
        )
        assertNull(refresh)
    }

    /** 受信通知以外のアイテムは反映の対象にならない。 */
    @Test
    fun nonNotificationItemIsNotReflected() {
        val file = ReceivedFile(
            id = "f1",
            timestampEpochMillis = 1_000L,
            payload = FilePayload(
                id = "f1",
                from = "desk",
                to = "*",
                sentAtEpochMillis = 900L,
                attachments = listOf(attachment("blob-a")),
                postedAtEpochMillis = 900L,
            ),
        )
        assertNull(revisionRefreshFor(file, file))
    }
}
