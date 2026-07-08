package to.sava.peranta.send

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ForwardedKeyTracker] の記録・消費・上限淘汰を検証する（§3.4）。 */
class ForwardedKeyTrackerTest {

    /** 記録した key は一度だけ「転送済み」として消費でき、二度目は false になる。 */
    @Test
    fun remembersAndConsumesOnce() {
        val tracker = ForwardedKeyTracker()
        tracker.remember("0|k1")
        assertTrue(tracker.consume("0|k1"))
        assertFalse(tracker.consume("0|k1"))
    }

    /** 記録していない key は転送済みではない（他アプリの削除を拾わない）。 */
    @Test
    fun unknownKeyIsNotForwarded() {
        assertFalse(ForwardedKeyTracker().consume("0|unknown"))
    }

    /** 上限を超えると最古の key から淘汰される。 */
    @Test
    fun evictsOldestBeyondCapacity() {
        val tracker = ForwardedKeyTracker(capacity = 2)
        tracker.remember("a")
        tracker.remember("b")
        tracker.remember("c")
        assertFalse(tracker.consume("a"))
        assertTrue(tracker.consume("b"))
        assertTrue(tracker.consume("c"))
    }

    /** 同じ key を重ねて記録しても 1 エントリのままで、余計な淘汰を起こさない。 */
    @Test
    fun rememberingSameKeyTwiceKeepsSingleEntry() {
        val tracker = ForwardedKeyTracker(capacity = 2)
        tracker.remember("a")
        tracker.remember("a")
        tracker.remember("b")
        assertTrue(tracker.consume("a"))
        assertTrue(tracker.consume("b"))
    }
}
