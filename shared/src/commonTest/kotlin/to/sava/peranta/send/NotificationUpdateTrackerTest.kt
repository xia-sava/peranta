package to.sava.peranta.send

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationUpdateTrackerTest {

    /** 同一 key・同一本文を時間枠内に再掲すると抑止対象になる。 */
    @Test
    fun repeatedSameUpdateIsSuppressed() {
        val tracker = NotificationUpdateTracker(windowMillis = 10_000)
        assertFalse(tracker.isRepeatUpdate("key-1", "本文", at = 0))
        assertTrue(tracker.isRepeatUpdate("key-1", "本文", at = 5_000))
    }

    /** 同一 key でも本文が変われば抑止しない。 */
    @Test
    fun changedBodyIsNotSuppressed() {
        val tracker = NotificationUpdateTracker()
        tracker.isRepeatUpdate("key-1", "本文A", at = 0)
        assertFalse(tracker.isRepeatUpdate("key-1", "本文B", at = 1_000))
    }

    /** 時間枠を過ぎた再掲は抑止しない。 */
    @Test
    fun updateAfterWindowIsNotSuppressed() {
        val tracker = NotificationUpdateTracker(windowMillis = 10_000)
        tracker.isRepeatUpdate("key-1", "本文", at = 0)
        assertFalse(tracker.isRepeatUpdate("key-1", "本文", at = 10_001))
    }

    /** 別 key は互いに影響しない。 */
    @Test
    fun differentKeysAreIndependent() {
        val tracker = NotificationUpdateTracker()
        tracker.isRepeatUpdate("key-1", "本文", at = 0)
        assertFalse(tracker.isRepeatUpdate("key-2", "本文", at = 1_000))
    }
}
