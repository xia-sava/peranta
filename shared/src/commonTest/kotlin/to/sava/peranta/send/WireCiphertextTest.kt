package to.sava.peranta.send

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.ENVELOPE_VERSION
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 各 payload に仕込む平文の目印。ワイヤ上の本文に現れてはならない。 */
private const val TITLE_MARKER = "title-never-on-the-wire"
private const val TEXT_MARKER = "text-never-on-the-wire"
private const val SMS_TEXT_MARKER = "sms-never-on-the-wire"
private const val MESSAGE_TEXT_MARKER = "message-never-on-the-wire"
private const val CAPTION_MARKER = "caption-never-on-the-wire"
private const val REPLY_TEXT_MARKER = "reply-never-on-the-wire"
private const val HOST_MARKER = "blob-host-never-on-the-wire.example.com"

/**
 * ntfy へ載るのは暗号文だけ、を送信側で固定する。publish された本文はどの payload 種別でも
 * Envelope であり、平文フィールドを生のまま含まない。
 */
class WireCiphertextTest {

    private val now = 5_000L
    private val cipher = MessageCipher(generateKey(), "k1")
    private val ntfy = FakeNtfyClient()

    /** ワイヤ上の本文に現れてはならない文字列。 */
    private val markers = listOf(
        TITLE_MARKER,
        TEXT_MARKER,
        SMS_TEXT_MARKER,
        MESSAGE_TEXT_MARKER,
        CAPTION_MARKER,
        REPLY_TEXT_MARKER,
        HOST_MARKER,
    )

    private val payloads: List<Payload> = listOf(
        NotificationPayload(
            id = "n1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = now,
            packageName = "com.example.bank",
            appName = "Bank",
            title = TITLE_MARKER,
            text = TEXT_MARKER,
            notificationKey = "0|com.example.bank|1|null|10",
            postedAtEpochMillis = now,
        ),
        SmsPayload(
            id = "s1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = now,
            senderNumber = "090-1111-2222",
            text = SMS_TEXT_MARKER,
            postedAtEpochMillis = now,
        ),
        MessagePayload(
            id = "msg1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = now,
            text = MESSAGE_TEXT_MARKER,
        ),
        CommandPayload(
            id = "cmd1",
            from = "desk",
            to = "phone",
            sentAtEpochMillis = now,
            command = CommandType.REPLY,
            targetNotificationKey = "0|com.example.bank|1|null|10",
            actionIndex = 0,
            replyText = REPLY_TEXT_MARKER,
        ),
        FilePayload(
            id = "f1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = now,
            caption = CAPTION_MARKER,
            attachments = listOf(
                AttachmentRef(
                    blobId = "blob-1",
                    url = "https://$HOST_MARKER/file/abc",
                    fileName = "photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 2048,
                    kind = AttachmentKind.IMAGE,
                    enc = BlobEnc(
                        keyId = "k1",
                        saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
                        chunkSize = 1_048_576,
                        totalChunks = 1,
                    ),
                ),
            ),
            postedAtEpochMillis = now,
        ),
    )

    private fun pipeline() = SendPipeline(cipher, ntfy, FakeTimelineStore(), now = { now })

    /** publish された本文はすべて Envelope で、平文の目印を含まない。 */
    @Test
    fun everyPublishedBodyIsAnEnvelopeWithoutPlaintext() = runTest {
        val pipeline = pipeline()
        payloads.forEach { pipeline.send(it, listOf("topic"), persistSensitive = true) }

        assertEquals(payloads.size, ntfy.published.size)
        ntfy.published.forEach { published ->
            val envelope = decodeEnvelope(published.body)
            assertEquals(ENVELOPE_VERSION, envelope.v)
            assertEquals(cipher.keyId, envelope.keyId)
            assertTrue(envelope.nonce.isNotBlank())
            assertTrue(envelope.ciphertext.isNotBlank())
            markers.forEach { marker ->
                assertFalse(published.body.contains(marker), "plaintext '$marker' appeared on the wire")
            }
        }
    }

    /** publish された本文は送った payload そのものの暗号文で、別物にすり替わっていない。 */
    @Test
    fun everyPublishedBodyOpensBackToItsPayload() = runTest {
        val pipeline = pipeline()
        payloads.forEach { pipeline.send(it, listOf("topic"), persistSensitive = true) }

        val opened = ntfy.published.map { cipher.open(decodeEnvelope(it.body)) }
        assertEquals(payloads, opened)
    }
}
