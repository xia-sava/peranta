package to.sava.peranta.model

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PayloadTest {

    private fun notification(priority: Priority) = NotificationPayload(
        id = "n",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1,
        packageName = "p",
        appName = "a",
        title = "t",
        text = "b",
        notificationKey = "k",
        postedAtEpochMillis = 1,
        priority = priority,
    )

    private fun command(type: CommandType) = CommandPayload(
        id = "c",
        from = "desktop",
        to = "phone",
        sentAtEpochMillis = 1,
        command = type,
    )

    /** 全 Priority 値が JSON を往復して保存される。 */
    @Test
    fun allPriorityValuesRoundTrip() {
        Priority.entries.forEach { priority ->
            val decoded = decodePayload(encodePayload(notification(priority))) as NotificationPayload
            assertEquals(priority, decoded.priority)
        }
    }

    /** 全 CommandType 値が JSON を往復して保存される。 */
    @Test
    fun allCommandTypeValuesRoundTrip() {
        CommandType.entries.forEach { type ->
            val decoded = decodePayload(encodePayload(command(type))) as CommandPayload
            assertEquals(type, decoded.command)
        }
    }

    /** explicitNulls=false のため null 項目は JSON に出力されない。既定値は encodeDefaults=true で出力される。 */
    @Test
    fun nullFieldsAreOmittedButDefaultsAreEmitted() {
        val json = encodePayload(
            SmsPayload(
                id = "s",
                from = "phone",
                to = "desktop",
                sentAtEpochMillis = 1,
                senderNumber = "+81",
                senderName = null,
                text = "hi",
                postedAtEpochMillis = 1,
                expiresAtEpochMillis = null,
            ),
        )
        assertTrue(!json.contains("senderName"), json)
        assertTrue(!json.contains("expiresAtEpochMillis"), json)
        assertContains(json, "\"priority\":\"high\"")
    }

    /** BROADCAST_TARGET は全端末宛を表す "*"。 */
    @Test
    fun broadcastTargetIsAsterisk() {
        assertEquals("*", BROADCAST_TARGET)
    }

    /** newPayloadId は空でない値を生成し、呼び出しごとに異なる。 */
    @Test
    fun newPayloadIdIsNonBlankAndDistinct() {
        val a = newPayloadId()
        val b = newPayloadId()
        assertTrue(a.isNotBlank())
        assertNotEquals(a, b)
    }

    /** nowEpochMillis は正のエポックミリ秒を返す。 */
    @Test
    fun nowEpochMillisIsPositive() {
        assertTrue(nowEpochMillis() > 0)
    }

    /** JSON として解釈できない本文の復号は SerializationException になる。 */
    @Test
    fun decodeMalformedJsonThrows() {
        assertFailsWith<SerializationException> { decodePayload("not json at all") }
    }

    /** 未知の type 判別子を持つ JSON の復号は SerializationException になる。 */
    @Test
    fun decodeUnknownTypeDiscriminatorThrows() {
        val json = """{"type":"bogus","id":"x","from":"a","to":"b","sentAtEpochMillis":1}"""
        assertFailsWith<SerializationException> { decodePayload(json) }
    }

    /** fromName（§4.1）を持たない旧バージョン由来の JSON も復号でき、fromName は null になる。 */
    @Test
    fun decodeWithoutFromNameFallsBackToNull() {
        val json = """
            {"type":"notification","id":"n","from":"phone","to":"*","sentAtEpochMillis":1,
            "packageName":"p","appName":"a","title":"t","text":"b","notificationKey":"k",
            "postedAtEpochMillis":1}
        """.trimIndent()
        val decoded = decodePayload(json) as NotificationPayload
        assertEquals(null, decoded.fromName)
        assertEquals("phone", decoded.from)
    }

    /** fromName ありのペイロードは JSON を往復しても保持される。 */
    @Test
    fun fromNameRoundTrips() {
        val decoded = decodePayload(encodePayload(notification(Priority.NORMAL).copy(fromName = "xia-phone"))) as NotificationPayload
        assertEquals("xia-phone", decoded.fromName)
    }
}
