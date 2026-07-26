package to.sava.peranta.send

import to.sava.peranta.model.SmsPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsDedupeTrackerTest {

    private fun smsPayload(id: String = "sms-1") = SmsPayload(
        id = id,
        from = "device-a",
        to = "*",
        sentAtEpochMillis = 0,
        senderNumber = "090-1111-2222",
        text = "確認コード 987654 です",
        postedAtEpochMillis = 0,
    )

    /** 直接受信した本文を含む通知は重複とみなす。 */
    @Test
    fun matchingNotificationIsDuplicate() {
        val tracker = SmsDedupeTracker()
        tracker.recordSms("確認コード 987654 です", at = 0)
        assertTrue(tracker.isDuplicateNotification("090-1111-2222", "確認コード 987654 です", at = 1_000))
    }

    /** 通知本文が SMS 本文を含めば、番号欠落や前後の装飾があっても重複とみなす。 */
    @Test
    fun notificationContainingSmsBodyIsDuplicate() {
        val tracker = SmsDedupeTracker()
        tracker.recordSms("hello world", at = 0)
        assertTrue(tracker.isDuplicateNotification("SMS", "hello world 追加テキスト", at = 500))
    }

    /** 別内容の通知は重複としない。 */
    @Test
    fun unrelatedNotificationIsNotDuplicate() {
        val tracker = SmsDedupeTracker()
        tracker.recordSms("確認コード 987654", at = 0)
        assertFalse(tracker.isDuplicateNotification("メール", "無関係な通知", at = 1_000))
    }

    /** 保持時間を過ぎた記憶は突き合わせ対象から外れる。 */
    @Test
    fun expiredMemoryIsPruned() {
        val tracker = SmsDedupeTracker(windowMillis = 60_000)
        tracker.recordSms("hello world", at = 0)
        assertFalse(tracker.isDuplicateNotification("x", "hello world", at = 60_001))
    }

    /** 空通知は重複としない。 */
    @Test
    fun blankNotificationIsNotDuplicate() {
        val tracker = SmsDedupeTracker()
        tracker.recordSms("hello", at = 0)
        assertFalse(tracker.isDuplicateNotification(null, null, at = 100))
    }

    /** 転送済みペイロードは、対応する通知の突き合わせで取り出せる。 */
    @Test
    fun forwardedPayloadIsRetrievedByMatchingNotification() {
        val tracker = SmsDedupeTracker()
        val payload = smsPayload()
        tracker.recordSms(payload.text, at = 0)
        tracker.recordForwarded(payload.text, payload)
        assertEquals(payload, tracker.consumeForwardedPayload("090-1111-2222", payload.text, at = 1_000))
    }

    /** 取り出しは 1 回だけで、同じ SMS の通知が再投稿されても二度目は返さない。 */
    @Test
    fun forwardedPayloadIsConsumedOnce() {
        val tracker = SmsDedupeTracker()
        val payload = smsPayload()
        tracker.recordSms(payload.text, at = 0)
        tracker.recordForwarded(payload.text, payload)
        tracker.consumeForwardedPayload("x", payload.text, at = 1_000)
        assertNull(tracker.consumeForwardedPayload("x", payload.text, at = 2_000))
    }

    /** 取り出したあとも本文の記憶は残り、重複抑止は効き続ける。 */
    @Test
    fun consumingPayloadKeepsDuplicateSuppression() {
        val tracker = SmsDedupeTracker()
        val payload = smsPayload()
        tracker.recordSms(payload.text, at = 0)
        tracker.recordForwarded(payload.text, payload)
        tracker.consumeForwardedPayload("x", payload.text, at = 1_000)
        assertTrue(tracker.isDuplicateNotification("x", payload.text, at = 2_000))
    }

    /** 転送内容が未確定の SMS は、通知が一致しても取り出せない（重複判定だけ効く）。 */
    @Test
    fun unlinkedSmsHasNoForwardedPayload() {
        val tracker = SmsDedupeTracker()
        tracker.recordSms("確認コード 987654 です", at = 0)
        assertNull(tracker.consumeForwardedPayload("x", "確認コード 987654 です", at = 1_000))
    }

    /** 保持時間切れで本文の記憶ごと消えた SMS には紐づけられない。 */
    @Test
    fun forwardedPayloadIsNotRecordedForExpiredSms() {
        val tracker = SmsDedupeTracker(windowMillis = 60_000)
        val payload = smsPayload()
        tracker.recordSms(payload.text, at = 0)
        tracker.isDuplicateNotification("x", "無関係", at = 60_001)
        tracker.recordForwarded(payload.text, payload)
        assertNull(tracker.consumeForwardedPayload("x", payload.text, at = 60_002))
    }
}
