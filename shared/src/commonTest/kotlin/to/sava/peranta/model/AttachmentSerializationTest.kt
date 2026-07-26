package to.sava.peranta.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentSerializationTest {

    private fun attachment(blobId: String = "blob-1") = AttachmentRef(
        blobId = blobId,
        url = "https://peranta.sava.to/file/abc123",
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 3_145_728,
        kind = AttachmentKind.IMAGE,
        blobExpiresAtEpochMillis = 5_000,
        enc = BlobEnc(
            v = 1,
            keyId = "k1",
            saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
            chunkSize = 1_048_576,
            totalChunks = 3,
        ),
    )

    /** FilePayload が添付を含めて全フィールドを保持したままラウンドトリップする。 */
    @Test
    fun filePayloadRoundTrip() {
        val payload = FilePayload(
            id = "id-1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 1_000,
            caption = "見て",
            attachments = listOf(attachment("a"), attachment("b")),
            postedAtEpochMillis = 900,
            expiresAtEpochMillis = 2_000,
            priority = Priority.HIGH,
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** FilePayload の JSON 判別子は "file"。 */
    @Test
    fun filePayloadDiscriminatorIsFile() {
        val json = encodePayload(
            FilePayload(
                id = "id-2",
                from = "phone",
                to = BROADCAST_TARGET,
                sentAtEpochMillis = 1,
                attachments = listOf(attachment()),
                postedAtEpochMillis = 1,
            ),
        )
        assertTrue(json.contains("\"type\":\"file\""), json)
    }

    /** NotificationPayload は attachments を含めてもラウンドトリップする。 */
    @Test
    fun notificationWithAttachmentsRoundTrip() {
        val payload = NotificationPayload(
            id = "id-3",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 1_000,
            packageName = "com.example.app",
            appName = "Example",
            title = "Title",
            text = "Body",
            notificationKey = "0|com.example.app|1|tag|10",
            postedAtEpochMillis = 900,
            attachments = listOf(attachment()),
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** attachments フィールドを持たない旧 JSON も、空リスト既定で復元される（後方互換）。 */
    @Test
    fun notificationWithoutAttachmentsDefaultsToEmpty() {
        val json = """
            {
              "type": "notification",
              "id": "id-4",
              "from": "phone",
              "to": "*",
              "sentAtEpochMillis": 10,
              "packageName": "com.example.app",
              "appName": "Example",
              "title": "T",
              "text": "B",
              "notificationKey": "k",
              "postedAtEpochMillis": 9
            }
        """.trimIndent()
        val decoded = decodePayload(json) as NotificationPayload
        assertEquals(emptyList(), decoded.attachments)
    }

    /** AttachmentRef 単体も kotlinx.serialization でラウンドトリップする。 */
    @Test
    fun attachmentRefRoundTrip() {
        val ref = attachment()
        assertEquals(ref, PerantaJson.decodeFromString<AttachmentRef>(PerantaJson.encodeToString(ref)))
    }

    /** kind=TEXT（全文添付）の AttachmentRef は JSON で "text" として往復する。 */
    @Test
    fun textAttachmentKindRoundTrips() {
        val ref = attachment().copy(kind = AttachmentKind.TEXT, mimeType = "text/plain", fileName = "message.txt")
        val json = PerantaJson.encodeToString(ref)
        assertTrue(json.contains("\"kind\":\"text\""), json)
        assertEquals(ref, PerantaJson.decodeFromString<AttachmentRef>(json))
    }

    /** SmsPayload は attachments を含めてもラウンドトリップする。 */
    @Test
    fun smsWithAttachmentsRoundTrip() {
        val payload = SmsPayload(
            id = "sms-1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 1_000,
            senderNumber = "09000000000",
            senderName = "銀行",
            text = "本文プレビュー…",
            postedAtEpochMillis = 900,
            attachments = listOf(attachment().copy(kind = AttachmentKind.TEXT)),
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** 通知は送信者アイコンを含めてラウンドトリップする（§4.3.1）。 */
    @Test
    fun notificationWithSenderIconRoundTrip() {
        val payload = NotificationPayload(
            id = "n-1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 1_000,
            packageName = "com.example.chat",
            appName = "Chat",
            title = "田中さん",
            text = "写真を送りました",
            notificationKey = "0|com.example.chat|1|null|10",
            postedAtEpochMillis = 900,
            attachments = listOf(attachment("blob-photo")),
            senderIcon = attachment("blob-icon").copy(fileName = "sender-icon-900.png", mimeType = "image/png"),
            revision = 1,
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** senderIcon を持たない旧 JSON も null 既定で復元される（後方互換）。 */
    @Test
    fun notificationWithoutSenderIconDefaultsToNull() {
        val json = """
            {
              "type": "notification",
              "id": "n-2",
              "from": "phone",
              "to": "*",
              "sentAtEpochMillis": 10,
              "packageName": "com.example.chat",
              "appName": "Chat",
              "title": "田中さん",
              "text": "写真を送りました",
              "notificationKey": "0|com.example.chat|1|null|10",
              "postedAtEpochMillis": 9
            }
        """.trimIndent()
        val decoded = decodePayload(json) as NotificationPayload
        assertNull(decoded.senderIcon)
    }

    /** attachments を持たない旧 SMS JSON も、空リスト既定で復元される（後方互換）。 */
    @Test
    fun smsWithoutAttachmentsDefaultsToEmpty() {
        val json = """
            {
              "type": "sms",
              "id": "sms-2",
              "from": "phone",
              "to": "*",
              "sentAtEpochMillis": 10,
              "senderNumber": "09000000000",
              "text": "確認コード",
              "postedAtEpochMillis": 9
            }
        """.trimIndent()
        val decoded = decodePayload(json) as SmsPayload
        assertEquals(emptyList(), decoded.attachments)
    }

    /** SMS は対応づいた元通知の key と改版番号を含めてラウンドトリップする（§3.1）。 */
    @Test
    fun smsWithNotificationKeyRoundTrip() {
        val payload = SmsPayload(
            id = "sms-3",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 1_000,
            senderNumber = "09000000000",
            text = "確認コード 987654",
            postedAtEpochMillis = 900,
            notificationKey = "0|com.android.messaging|7|null|10",
            revision = 1,
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** notificationKey を持たない旧 SMS JSON も、未対応づけとして復元される（後方互換）。 */
    @Test
    fun smsWithoutNotificationKeyDefaultsToUnlinked() {
        val json = """
            {
              "type": "sms",
              "id": "sms-4",
              "from": "phone",
              "to": "*",
              "sentAtEpochMillis": 10,
              "senderNumber": "09000000000",
              "text": "確認コード",
              "postedAtEpochMillis": 9
            }
        """.trimIndent()
        val decoded = decodePayload(json) as SmsPayload
        assertNull(decoded.notificationKey)
        assertEquals(0, decoded.revision)
    }
}
