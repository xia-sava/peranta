package to.sava.peranta.send

import kotlinx.coroutines.test.runTest
import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.blob.FakeBlobTransport
import to.sava.peranta.blob.MAX_FULL_TEXT_ATTACHMENT_BYTES
import to.sava.peranta.blob.drainToBytes
import to.sava.peranta.blob.uploadFullTextAttachment
import to.sava.peranta.filter.FilterMode
import io.ktor.utils.io.ByteReadChannel
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.FULL_TEXT_PREVIEW_BYTES
import to.sava.peranta.model.MAX_FORWARDED_TEXT_BYTES
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FullTextAttachmentTest {

    private val sharedKey: ByteArray = ByteArray(32) { (it * 3 + 7).toByte() }

    /** UTF-8 バイト長。 */
    private fun byteLen(value: String): Int = value.encodeToByteArray().size

    /** テスト用に「全文を blob 化して AttachmentRef を返す」アップローダ。1 回の呼び出しで 1 blob。 */
    private fun uploader(transport: FakeBlobTransport, cipher: BlobCipher): suspend (String) -> AttachmentRef =
        { text -> uploadFullTextAttachment(transport, cipher, "peranta-blob-x", text, newBlobId = { "blob-1" }) }

    private fun notification(text: String, title: String = "お知らせ"): NotificationPayload =
        prepareForwardedNotification(
            NotificationInput(
                packageName = "com.example.app",
                appName = "App",
                title = title,
                text = text,
                notificationKey = "0|com.example.app|1|null|10",
                postedAtEpochMillis = 1000,
            ),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
        )!!.payload

    /** prepareForwardedNotification は切り詰め前・伏せ字適用後の本文全文を返す（非伏せ字時は入力そのまま）。 */
    @Test
    fun prepareExposesUntruncatedFullText() {
        val long = "あ".repeat(1500)
        val prepared = prepareForwardedNotification(
            NotificationInput(
                packageName = "com.example.app",
                appName = "App",
                title = "件名",
                text = long,
                notificationKey = "0|com.example.app|1|null|10",
                postedAtEpochMillis = 1000,
            ),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
        )!!
        assertEquals(long, prepared.fullText)
        // インライン本文は従来どおり 2000 バイト予算で切り詰まる。
        assertTrue(byteLen(prepared.payload.text) <= MAX_FORWARDED_TEXT_BYTES)
    }

    /** トグル OFF なら本文がいくら長くても添付せず、payload をそのまま返す。 */
    @Test
    fun toggleOffNeverAttaches() = runTest {
        val transport = FakeBlobTransport()
        val payload = notification(text = "a".repeat(1000))
        val result = attachFullTextIfNeeded(
            payload = payload,
            fullText = "a".repeat(1000),
            attachFullTextWhenTruncated = false,
            persistSensitiveHistory = false,
            uploadFullText = uploader(transport, BlobCipher(sharedKey, "k1")),
        )
        assertSame(payload, result)
        assertTrue(transport.uploads.isEmpty())
    }

    /** プレビュー予算内（512 バイト以下）の本文は添付しない。 */
    @Test
    fun withinPreviewBudgetNotAttached() = runTest {
        val transport = FakeBlobTransport()
        val short = "a".repeat(FULL_TEXT_PREVIEW_BYTES)
        val payload = notification(text = short)
        val result = attachFullTextIfNeeded(
            payload = payload,
            fullText = short,
            attachFullTextWhenTruncated = true,
            persistSensitiveHistory = false,
            uploadFullText = uploader(transport, BlobCipher(sharedKey, "k1")),
        )
        assertSame(payload, result)
        assertTrue(transport.uploads.isEmpty())
    }

    /** 512 バイト超・非センシティブなら、インラインをプレビューに切り詰め、全文 blob を 1 件添付する。 */
    @Test
    fun overBudgetAttachesFullTextBlob() = runTest {
        val transport = FakeBlobTransport()
        val cipher = BlobCipher(sharedKey, "k1")
        val full = "本文" + "z".repeat(1000)
        val payload = notification(text = full)

        val result = attachFullTextIfNeeded(
            payload = payload,
            fullText = full,
            attachFullTextWhenTruncated = true,
            persistSensitiveHistory = false,
            uploadFullText = uploader(transport, cipher),
        )

        assertNotSame(payload, result)
        assertTrue(byteLen(result.text) <= FULL_TEXT_PREVIEW_BYTES, "preview was ${byteLen(result.text)} bytes")
        assertEquals(1, result.attachments.size)
        val ref = result.attachments.single()
        assertEquals(AttachmentKind.TEXT, ref.kind)
        assertEquals("text/plain", ref.mimeType)

        // アップロードされた blob を復号すると全文に戻る。
        val stored = transport.uploads.single()
        val decrypted = drainToBytes { output ->
            BlobCipher(sharedKey, "k1").decrypt(ref.blobId, ref.enc, ref.sizeBytes, ByteReadChannel(stored.body), output)
        }
        assertEquals(full, decrypted.decodeToString())
    }

    /** 上限（[MAX_FULL_TEXT_ATTACHMENT_BYTES]）を超える本文は、非センシティブでも全文 blob を諦め、プレビューのみで送る。 */
    @Test
    fun overSizeLimitSkipsBlobAndKeepsPreviewOnly() = runTest {
        val transport = FakeBlobTransport()
        val huge = "a".repeat((MAX_FULL_TEXT_ATTACHMENT_BYTES + 1).toInt())
        val payload = notification(text = huge)

        val result = attachFullTextIfNeeded(
            payload = payload,
            fullText = huge,
            attachFullTextWhenTruncated = true,
            persistSensitiveHistory = false,
            uploadFullText = uploader(transport, BlobCipher(sharedKey, "k1")),
        )

        assertSame(payload, result)
        assertTrue(transport.uploads.isEmpty())
    }

    /** ちょうど上限までの本文は従来どおり全文 blob を添付する（境界値）。 */
    @Test
    fun atSizeLimitStillAttachesBlob() = runTest {
        val transport = FakeBlobTransport()
        val atLimit = "a".repeat(MAX_FULL_TEXT_ATTACHMENT_BYTES.toInt())
        val payload = notification(text = atLimit)

        val result = attachFullTextIfNeeded(
            payload = payload,
            fullText = atLimit,
            attachFullTextWhenTruncated = true,
            persistSensitiveHistory = false,
            uploadFullText = uploader(transport, BlobCipher(sharedKey, "k1")),
        )

        assertNotSame(payload, result)
        assertEquals(1, transport.uploads.size)
    }

    /** persistSensitiveHistory=false の OTP 通知はセンシティブ扱いで、長文でも全文 blob を作らない。 */
    @Test
    fun sensitiveOtpNotificationSkipsBlobUnlessPersisted() = runTest {
        val transport = FakeBlobTransport()
        val cipher = BlobCipher(sharedKey, "k1")
        val otpText = "your verification code is 123456 " + "x".repeat(1000)
        val payload = notification(text = otpText, title = "code")
        assertEquals(Priority.HIGH, payload.priority, "sanity: OTP is detected as high priority")

        val skipped = attachFullTextIfNeeded(
            payload = payload,
            fullText = otpText,
            attachFullTextWhenTruncated = true,
            persistSensitiveHistory = false,
            uploadFullText = uploader(transport, cipher),
        )
        assertSame(payload, skipped)
        assertTrue(transport.uploads.isEmpty())

        // persistSensitiveHistory=true なら伏せ字対象でなくなり、全文添付する。
        val attached = attachFullTextIfNeeded(
            payload = payload,
            fullText = otpText,
            attachFullTextWhenTruncated = true,
            persistSensitiveHistory = true,
            uploadFullText = uploader(transport, cipher),
        )
        assertEquals(1, attached.attachments.size)
        assertEquals(1, transport.uploads.size)
    }

    /** SMS は persistSensitiveHistory=false で常にセンシティブ扱い（添付なし）、true なら長文で添付する。 */
    @Test
    fun smsAttachesOnlyWhenPersistingSensitive() = runTest {
        val transport = FakeBlobTransport()
        val cipher = BlobCipher(sharedKey, "k1")
        val body = "s".repeat(1000)
        val payload = buildSmsPayload(senderNumber = "09000000000", text = body, deviceId = "phone", now = 2000)

        val skipped = attachFullTextIfNeeded(payload, body, attachFullTextWhenTruncated = true, persistSensitiveHistory = false, uploadFullText = uploader(transport, cipher))
        assertSame(payload, skipped)
        assertTrue(transport.uploads.isEmpty())

        val attached = attachFullTextIfNeeded(payload, body, attachFullTextWhenTruncated = true, persistSensitiveHistory = true, uploadFullText = uploader(transport, cipher))
        assertEquals(1, attached.attachments.size)
        assertEquals(AttachmentKind.TEXT, attached.attachments.single().kind)
    }

    /** プレビュー切り詰めはサロゲートペア（絵文字）を分断しない。 */
    @Test
    fun previewDoesNotSplitSurrogatePairs() = runTest {
        val transport = FakeBlobTransport()
        val full = "🙂".repeat(400)
        val payload = notification(text = full)
        val result = attachFullTextIfNeeded(payload, full, attachFullTextWhenTruncated = true, persistSensitiveHistory = false, uploadFullText = uploader(transport, BlobCipher(sharedKey, "k1")))
        val core = result.text.removeSuffix("…")
        assertTrue(core.isNotEmpty())
        assertEquals(0, core.length % "🙂".length)
        assertTrue(core.chunked("🙂".length).all { it == "🙂" })
    }
}
