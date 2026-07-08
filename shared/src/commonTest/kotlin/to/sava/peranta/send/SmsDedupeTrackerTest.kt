package to.sava.peranta.send

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsDedupeTrackerTest {

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
}
