package to.sava.peranta.send

import to.sava.peranta.filter.SENSITIVE_HISTORY_PLACEHOLDER
import to.sava.peranta.filter.payloadForPersistence
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.net.NtfyPublishException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendPersistenceTest {

    private fun notification(title: String, text: String) = NotificationPayload(
        id = "n1",
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 1000,
        packageName = "com.example.bank",
        appName = "Bank",
        title = title,
        text = text,
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = 1000,
    )

    private fun sms(text: String) = SmsPayload(
        id = "s1",
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 1000,
        senderNumber = "09011112222",
        text = text,
        postedAtEpochMillis = 1000,
    )

    /** keepSensitive=true なら OTP 通知も SMS もそのまま保存する。 */
    @Test
    fun keepingSensitiveLeavesBodyIntact() {
        val otp = notification("code", "your code is 123456")
        assertEquals(otp, payloadForPersistence(otp, keepSensitive = true))
        val message = sms("確認コード 987654")
        assertEquals(message, payloadForPersistence(message, keepSensitive = true))
    }

    /** keepSensitive=false のとき OTP 通知の本文は伏せ、コードを含まないタイトルは残す。 */
    @Test
    fun otpNotificationBodyIsMasked() {
        val otp = notification("code", "your code is 123456")
        val stored = payloadForPersistence(otp, keepSensitive = false) as NotificationPayload
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, stored.text)
        assertEquals("code", stored.title)
    }

    /** OTP コードがタイトルにある場合、タイトルも本文も伏せる。 */
    @Test
    fun otpCodeInTitleMasksTitle() {
        val otp = notification(title = "123456", text = "認証コードを入力してください")
        val stored = payloadForPersistence(otp, keepSensitive = false) as NotificationPayload
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, stored.title)
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, stored.text)
    }

    /** keepSensitive=false でも通常通知（非 OTP）の本文は伏せない。 */
    @Test
    fun ordinaryNotificationBodyIsKept() {
        val ordinary = notification("お知らせ", "こんにちは")
        assertEquals(ordinary, payloadForPersistence(ordinary, keepSensitive = false))
    }

    /** keepSensitive=false のとき SMS の本文は常に伏せる。 */
    @Test
    fun smsBodyIsAlwaysMasked() {
        val message = sms("ふつうの本文")
        val stored = payloadForPersistence(message, keepSensitive = false) as SmsPayload
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, stored.text)
        assertEquals("09011112222", stored.senderNumber)
    }

    /** 4xx はリトライ不能、5xx とその他の例外はリトライ可能とみなす。 */
    @Test
    fun retriableClassification() {
        assertFalse(isRetriablePublishError(NtfyPublishException(401, "unauthorized")))
        assertFalse(isRetriablePublishError(NtfyPublishException(413, "too large")))
        assertTrue(isRetriablePublishError(NtfyPublishException(503, "unavailable")))
        assertTrue(isRetriablePublishError(RuntimeException("io")))
    }

    /** 予算以下はそのまま、超過分は省略記号付きで UTF-8 バイト予算内に収める。 */
    @Test
    fun truncationRespectsByteBudget() {
        assertEquals("short", truncateForForwarding("short", MAX_FORWARDED_TEXT_BYTES))
        val long = "あ".repeat(MAX_FORWARDED_TEXT_BYTES)
        val truncated = truncateForForwarding(long, MAX_FORWARDED_TEXT_BYTES)
        assertTrue(truncated.encodeToByteArray().size <= MAX_FORWARDED_TEXT_BYTES)
        assertTrue(truncated.endsWith("…"))
    }

    /** 2 バイト文字（ラテン拡張）もバイト予算で正しく切り詰める。 */
    @Test
    fun truncationCountsTwoByteChars() {
        val text = "é".repeat(20)
        val truncated = truncateForForwarding(text, maxBytes = 15)
        assertTrue(truncated.encodeToByteArray().size <= 15)
        assertTrue(truncated.endsWith("…"))
    }

    /** マルチバイト文字はバイト境界ではなくコードポイント境界で切り、絵文字を壊さない。 */
    @Test
    fun truncationKeepsSurrogatePairsIntact() {
        val emoji = "😀"
        val text = emoji.repeat(100)
        val truncated = truncateForForwarding(text, maxBytes = 25)
        assertTrue(truncated.encodeToByteArray().size <= 25)
        assertTrue(truncated.endsWith("…"))
        val withoutEllipsis = truncated.removeSuffix("…")
        assertEquals(0, withoutEllipsis.length % emoji.length)
        assertTrue(withoutEllipsis.chunked(emoji.length).all { it == emoji })
    }
}
