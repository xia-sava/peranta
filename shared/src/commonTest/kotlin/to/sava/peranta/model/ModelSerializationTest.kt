package to.sava.peranta.model

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelSerializationTest {

    /** NotificationPayload が全フィールドを保持したままラウンドトリップすることを検証する。 */
    @Test
    fun notificationRoundTrip() {
        val payload = NotificationPayload(
            id = "id-1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 1_000,
            packageName = "com.example.app",
            appName = "Example",
            title = "Title",
            text = "Body text",
            notificationKey = "0|com.example.app|1|tag|10",
            actions = listOf("Reply", "Mark as read"),
            postedAtEpochMillis = 900,
            expiresAtEpochMillis = 2_000,
            priority = Priority.HIGH,
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** SmsPayload が全フィールドを保持したままラウンドトリップすることを検証する。 */
    @Test
    fun smsRoundTrip() {
        val payload = SmsPayload(
            id = "id-2",
            from = "phone",
            to = "desktop",
            sentAtEpochMillis = 1_500,
            senderNumber = "+81901234567",
            senderName = "Alice",
            text = "See you soon",
            postedAtEpochMillis = 1_400,
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** CommandPayload が全フィールドを保持したままラウンドトリップすることを検証する。 */
    @Test
    fun commandRoundTrip() {
        val payload = CommandPayload(
            id = "id-3",
            from = "desktop",
            to = "phone",
            sentAtEpochMillis = 2_000,
            command = CommandType.REPLY,
            targetNotificationKey = "0|com.example.app|1|tag|10",
            actionIndex = 0,
            replyText = "On my way",
            packageName = "com.example.app",
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** PresencePayload が全フィールドを保持したままラウンドトリップすることを検証する。 */
    @Test
    fun presenceRoundTrip() {
        val payload = PresencePayload(
            id = "id-4",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 2_500,
            deviceName = "Pixel",
            endpoint = "https://example.invalid/relay",
            capabilities = listOf("notification", "sms"),
            sender = true,
        )
        assertEquals(payload, decodePayload(encodePayload(payload)))
    }

    /** JSON の判別フィールドが "type" であることを検証する。 */
    @Test
    fun discriminatorFieldIsType() {
        val json = encodePayload(
            PresencePayload(
                id = "id-5",
                from = "phone",
                to = BROADCAST_TARGET,
                sentAtEpochMillis = 1,
                deviceName = "Pixel",
                endpoint = "e",
            ),
        )
        assertTrue(json.contains("\"type\":\"presence\""), json)
    }

    /** 未知フィールドを含む JSON を無視して既知フィールドのみ復元することを検証する。 */
    @Test
    fun decodeIgnoresUnknownFields() {
        val json = """
            {
              "type": "notification",
              "id": "id-6",
              "from": "phone",
              "to": "*",
              "sentAtEpochMillis": 10,
              "packageName": "com.example.app",
              "appName": "Example",
              "title": "T",
              "text": "B",
              "notificationKey": "k",
              "postedAtEpochMillis": 9,
              "futureField": "ignored",
              "nested": { "a": 1 }
            }
        """.trimIndent()
        val decoded = decodePayload(json) as NotificationPayload
        assertEquals("id-6", decoded.id)
        assertEquals(Priority.NORMAL, decoded.priority)
    }

    /** Envelope が全フィールドを保持したままラウンドトリップすることを検証する。 */
    @Test
    fun envelopeRoundTrip() {
        val envelope = Envelope(v = 1, keyId = "k1", nonce = "bm9uY2U=", ciphertext = "Y2lwaGVy")
        assertEquals(envelope, decodeEnvelope(encodeEnvelope(envelope)))
    }

    /** keyId 欠損の Envelope JSON をデコードすると SerializationException になることを検証する。 */
    @Test
    fun decodeEnvelopeMissingRequiredFieldThrowsSerializationException() {
        val json = """
            {
              "v": 1,
              "nonce": "bm9uY2U=",
              "ciphertext": "Y2lwaGVy"
            }
        """.trimIndent()
        assertFailsWith<SerializationException> { decodeEnvelope(json) }
    }
}
