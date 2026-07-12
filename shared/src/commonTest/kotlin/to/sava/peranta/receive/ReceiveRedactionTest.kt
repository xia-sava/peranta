package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.SENSITIVE_HISTORY_PLACEHOLDER
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiveRedactionTest {

    private val now = 10_000L
    private val deviceId = "desk"
    private val cipher = MessageCipher(generateKey(), "k1")

    private fun store(): TimelineStore = JsonlTimelineStore(FakeTimelineFile())

    private fun pipeline(store: TimelineStore, persistSensitive: Boolean) = ReceivePipeline(
        ntfy = FakeNtfyClient(),
        cipher = cipher,
        store = store,
        deviceId = deviceId,
        persistSensitiveHistory = persistSensitive,
        now = { now },
    )

    private fun otp(id: String = "n1", expiresAt: Long? = null): NotificationPayload = NotificationPayload(
        id = id,
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        packageName = "com.example.bank",
        appName = "Bank",
        title = "認証コード",
        text = "コードは 123456 です",
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = now - 100,
        expiresAtEpochMillis = expiresAt,
    )

    private fun filePayload(caption: String?, id: String = "f1"): FilePayload = FilePayload(
        id = id,
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        caption = caption,
        attachments = listOf(
            AttachmentRef(
                blobId = "blob-1",
                url = "https://ntfy.example/file/blob-1",
                fileName = "receipt.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 2048,
                kind = AttachmentKind.IMAGE,
                enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
            ),
        ),
        postedAtEpochMillis = now - 100,
    )

    private fun textAttachment(blobId: String = "blob-text"): AttachmentRef = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "message.txt",
        mimeType = "text/plain",
        sizeBytes = 4096,
        kind = AttachmentKind.TEXT,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 4),
    )

    private fun imageAttachment(blobId: String = "blob-image"): AttachmentRef = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
    )

    /** 送信元が持たせた全文添付（TEXT）付きの OTP 通知。IMAGE 添付も併せ持たせ、伏せ字対象外の種別が残ることを検証する。 */
    private fun otpWithAttachments(id: String = "n1"): NotificationPayload = otp(id = id).copy(
        attachments = listOf(imageAttachment(), textAttachment()),
    )

    /** 送信元が持たせた全文添付（TEXT）付きの SMS。 */
    private fun smsWithTextAttachment(id: String = "s1"): SmsPayload = SmsPayload(
        id = id,
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        senderNumber = "09011112222",
        text = "ふつうの本文",
        postedAtEpochMillis = now - 100,
        attachments = listOf(textAttachment()),
    )

    private suspend fun eventFor(payload: Payload): NtfyEvent =
        NtfyEvent(id = "e", time = now, topic = "t", message = encodeEnvelope(cipher.seal(payload)))

    private fun textOf(item: Any?): String =
        ((item as ReceivedNotification).payload as NotificationPayload).text

    /** 既定（非永続）では、永続履歴の OTP 本文は伏せるが、表示用の StateFlow には本文を残す。 */
    @Test
    fun otpBodyIsRedactedInStoreButKeptInDisplay() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = false)

        pipeline.handleEvent(eventFor(otp()))

        assertEquals("コードは 123456 です", textOf(pipeline.items.value.single()))
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, textOf(store.loadAll().single()))
    }

    /** persistSensitiveHistory を有効にすると、永続履歴にも本文がそのまま残る。 */
    @Test
    fun sensitiveHistoryOptInKeepsBodyInStore() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = true)

        pipeline.handleEvent(eventFor(otp()))

        assertEquals("コードは 123456 です", textOf(store.loadAll().single()))
    }

    /** 表示フック（OS 通知・トーストへ回す一時表示）には伏せ字前の本文が渡る。 */
    @Test
    fun onItemAppendedReceivesUnredactedBody() = runTest {
        var seenText: String? = null
        val pipeline = ReceivePipeline(
            ntfy = FakeNtfyClient(),
            cipher = cipher,
            store = store(),
            deviceId = deviceId,
            persistSensitiveHistory = false,
            now = { now },
            onItemAppended = { item ->
                seenText = ((item as? ReceivedNotification)?.payload as? NotificationPayload)?.text
            },
        )

        pipeline.handleEvent(eventFor(otp()))

        assertEquals("コードは 123456 です", seenText)
    }

    /** 既定（非永続）では、受信ファイルのキャプションは永続だけ伏せ、表示・添付メタは保つ。 */
    @Test
    fun fileCaptionRedactedInStoreButKeptInDisplay() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = false)

        pipeline.handleEvent(eventFor(filePayload("領収書 12,800 円")))

        val displayed = pipeline.items.value.single() as ReceivedFile
        assertEquals("領収書 12,800 円", displayed.payload.caption)
        val stored = store.loadAll().single() as ReceivedFile
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, stored.payload.caption)
        assertEquals("receipt.jpg", stored.payload.attachments.single().fileName)
    }

    /** persistSensitiveHistory を有効にすると、受信ファイルのキャプションも永続に残る。 */
    @Test
    fun fileCaptionKeptWhenSensitiveOptIn() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = true)

        pipeline.handleEvent(eventFor(filePayload("領収書 12,800 円")))

        val stored = store.loadAll().single() as ReceivedFile
        assertEquals("領収書 12,800 円", stored.payload.caption)
    }

    /**
     * 送信元が persistSensitiveHistory=true で送ってきた OTP 通知に TEXT 添付（全文 blob 参照）が
     * 付いていても、受信側の persistSensitiveHistory=false では本文とあわせて TEXT 添付も永続から除く。
     * IMAGE 添付は伏せ字と無関係なため永続にも表示にも残る。
     */
    @Test
    fun textAttachmentStrippedFromStoreWhenRedacted() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = false)

        pipeline.handleEvent(eventFor(otpWithAttachments()))

        val displayed = (pipeline.items.value.single() as ReceivedNotification).payload as NotificationPayload
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.TEXT), displayed.attachments.map { it.kind })

        val stored = (store.loadAll().single() as ReceivedNotification).payload as NotificationPayload
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, stored.text)
        assertEquals(listOf(AttachmentKind.IMAGE), stored.attachments.map { it.kind })
    }

    /** persistSensitiveHistory=true では本文が伏せられないため、TEXT 添付も永続にそのまま残る。 */
    @Test
    fun textAttachmentKeptInStoreWhenSensitiveOptIn() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = true)

        pipeline.handleEvent(eventFor(otpWithAttachments()))

        val stored = (store.loadAll().single() as ReceivedNotification).payload as NotificationPayload
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.TEXT), stored.attachments.map { it.kind })
    }

    /** SMS も同様に、伏せ字適用時は TEXT 添付を永続から除く。表示用には残す。 */
    @Test
    fun smsTextAttachmentStrippedFromStoreWhenRedacted() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = false)

        pipeline.handleEvent(eventFor(smsWithTextAttachment()))

        val displayed = (pipeline.items.value.single() as ReceivedNotification).payload as SmsPayload
        assertEquals(listOf(AttachmentKind.TEXT), displayed.attachments.map { it.kind })

        val stored = (store.loadAll().single() as ReceivedNotification).payload as SmsPayload
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, stored.text)
        assertTrue(stored.attachments.isEmpty())
    }

    /** loadHistory は失効済みエントリを表示から除外するが、剪定するまでストアには残す。 */
    @Test
    fun loadHistoryHidesExpiredButKeepsStored() = runTest {
        val store = store()
        store.append(
            ReceivedNotification("expired", now - 200, otp(id = "expired"), expiresAtEpochMillis = now - 1),
        )
        store.append(
            ReceivedNotification("live", now - 100, otp(id = "live"), expiresAtEpochMillis = now + 10_000),
        )
        val pipeline = pipeline(store, persistSensitive = true)

        pipeline.loadHistory()

        assertEquals(listOf("live"), pipeline.items.value.map { it.id })
        assertEquals(listOf("expired", "live"), store.loadAll().map { it.id })
    }
}
