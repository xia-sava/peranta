package to.sava.peranta.send

import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.SentNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryDisplayMetaTest {

    private fun notification(
        id: String = "n1",
        title: String = "Verification",
        text: String = "your code is 123456",
    ) = NotificationPayload(
        id = id,
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 1000,
        packageName = "com.example.bank",
        appName = "Bank",
        title = title,
        text = text,
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = 900,
        expiresAtEpochMillis = 5000,
        priority = Priority.HIGH,
    )

    private fun sms(text: String = "確認コード 987654") = SmsPayload(
        id = "s1",
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 1000,
        senderNumber = "09011112222",
        senderName = "銀行",
        text = text,
        postedAtEpochMillis = 1000,
        expiresAtEpochMillis = 4000,
    )

    /** 通知メタは表示名・タイトル・失効を運び、id は元 payload と一致する。 */
    @Test
    fun notificationMetaCarriesDisplayFields() {
        val meta = retryDisplayMetaOf(notification())!!
        assertEquals(RetryDisplayKind.NOTIFICATION, meta.kind)
        assertEquals("n1", meta.id)
        assertEquals("Bank", meta.displayName)
        assertEquals("Verification", meta.title)
        assertEquals(5000, meta.expiresAtEpochMillis)
    }

    /** SMS メタは送信元表示名を運び、タイトルは持たない。送信済み項目も本文なしで組める。 */
    @Test
    fun smsMetaUsesSenderDisplayName() {
        val meta = retryDisplayMetaOf(sms())!!
        assertEquals(RetryDisplayKind.SMS, meta.kind)
        assertEquals("銀行", meta.displayName)
        assertEquals("", meta.title)

        val sent = meta.toSentNotification(from = "phone", timestamp = 8000)
        val built = sent.payload as SmsPayload
        assertEquals("", built.text)
        assertEquals("銀行", built.senderName)
        assertEquals(4000, sent.expiresAtEpochMillis)
    }

    /** 再送経路に乗らない種別は null（メタ化しない）。 */
    @Test
    fun unsupportedPayloadHasNoMeta() {
        val command = CommandPayload(
            id = "c1",
            from = "phone",
            to = "other",
            sentAtEpochMillis = 1000,
            command = CommandType.DISMISS,
        )
        assertNull(retryDisplayMetaOf(command))
    }

    /** メタ（本文を持たない）から送信済み項目を組み立てると本文は空で id が一致する。 */
    @Test
    fun buildsSentNotificationWithoutBody() {
        val payload = notification()
        val meta = retryDisplayMetaOf(payloadForPersistence(payload, keepSensitive = true))!!
        val sent: SentNotification = meta.toSentNotification(from = "phone", timestamp = 7000)
        assertEquals(payload.id, sent.id)
        assertEquals(7000, sent.timestampEpochMillis)
        assertEquals(5000, sent.expiresAtEpochMillis)
        val built = sent.payload as NotificationPayload
        assertEquals("", built.text)
        assertEquals("Bank", built.appName)
        assertEquals("Verification", built.title)
    }

    /** メタの符号化に本文は現れない（本文は載せない）。 */
    @Test
    fun encodedMetaExcludesBody() {
        val meta = retryDisplayMetaOf(notification(text = "極秘の本文 123456"))!!
        val json = encodeRetryDisplayMeta(meta)
        assertTrue(!json.contains("極秘の本文"))
        assertEquals(meta, decodeRetryDisplayMeta(json))
    }

    /** 再送記録の id は即時記録（payload.id）と一致する。 */
    @Test
    fun retryRecordIdMatchesImmediateRecord() {
        val payload = notification(id = "shared-id")
        val immediate = SentNotification(
            id = payload.id,
            timestampEpochMillis = 1000,
            payload = payload,
        )
        val retried = retryDisplayMetaOf(payload)!!.toSentNotification(from = "phone", timestamp = 2000)
        assertEquals(immediate.id, retried.id)
    }
}
