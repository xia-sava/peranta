package to.sava.peranta.ui

import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineScrollTest {

    private fun item(id: String): TimelineItem =
        ErrorItem(id = id, timestampEpochMillis = 1000L, message = id, kind = ErrorKind.OTHER)

    /** 対象 id が表示リストにあれば、その index を返す。 */
    @Test
    fun returnsIndexOfMatchingItem() {
        val visible = listOf(item("a"), item("b"), item("c"))
        assertEquals(1, timelineScrollTargetIndex(visible, "b"))
    }

    /** 対象 id が表示リストに無ければ null（剪定済み等、§10.1）。 */
    @Test
    fun returnsNullWhenItemNotVisible() {
        val visible = listOf(item("a"), item("b"))
        assertNull(timelineScrollTargetIndex(visible, "missing"))
    }

    /** ローカル非表示（スワイプで消した等）で除かれたアイテムを渡した表示リストでは見つからない。 */
    @Test
    fun returnsNullWhenItemWasLocallyDismissedFromVisibleList() {
        val all = listOf(item("a"), item("b"))
        val visibleAfterLocalDismiss = all.filterNot { it.id == "b" }
        assertNull(timelineScrollTargetIndex(visibleAfterLocalDismiss, "b"))
    }

    /** 空の表示リストでは常に null。 */
    @Test
    fun returnsNullForEmptyVisibleList() {
        assertNull(timelineScrollTargetIndex(emptyList(), "a"))
    }
}
