package to.sava.peranta.send

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationRepostTrackerTest {

    /** 転送済みと同一内容の再投稿は、時間が経っても抑止対象になる。 */
    @Test
    fun unchangedRepostIsSuppressed() {
        val tracker = NotificationRepostTracker()
        tracker.recordForwarded("key-1", "差出人", "本文")
        assertTrue(tracker.isUnchangedRepost("key-1", "差出人", "本文"))
    }

    /** 同一 key でも内容（タイトルか本文）が変われば抑止しない。 */
    @Test
    fun changedContentIsNotSuppressed() {
        val tracker = NotificationRepostTracker()
        tracker.recordForwarded("key-1", "差出人", "本文A")
        assertFalse(tracker.isUnchangedRepost("key-1", "差出人", "本文B"))
        assertFalse(tracker.isUnchangedRepost("key-1", "別の差出人", "本文A"))
    }

    /** 未転送の key は抑止しない。 */
    @Test
    fun unknownKeyIsNotSuppressed() {
        val tracker = NotificationRepostTracker()
        assertFalse(tracker.isUnchangedRepost("key-1", "差出人", "本文"))
    }

    /** 元通知の削除で記録を忘れ、同一内容でも再び転送対象になる。 */
    @Test
    fun forgetAllowsSameContentAgain() {
        val tracker = NotificationRepostTracker()
        tracker.recordForwarded("key-1", "差出人", "本文")
        tracker.forget("key-1")
        assertFalse(tracker.isUnchangedRepost("key-1", "差出人", "本文"))
    }

    /** 上限を超えると最古の記録から淘汰される。 */
    @Test
    fun oldestEntryIsEvictedOverCapacity() {
        val tracker = NotificationRepostTracker(capacity = 2)
        tracker.recordForwarded("key-1", "t", "a")
        tracker.recordForwarded("key-2", "t", "b")
        tracker.recordForwarded("key-3", "t", "c")
        assertFalse(tracker.isUnchangedRepost("key-1", "t", "a"))
        assertTrue(tracker.isUnchangedRepost("key-2", "t", "b"))
        assertTrue(tracker.isUnchangedRepost("key-3", "t", "c"))
    }

    /** 別 key は互いに影響しない。 */
    @Test
    fun differentKeysAreIndependent() {
        val tracker = NotificationRepostTracker()
        tracker.recordForwarded("key-1", "差出人", "本文")
        assertFalse(tracker.isUnchangedRepost("key-2", "差出人", "本文"))
    }
}
