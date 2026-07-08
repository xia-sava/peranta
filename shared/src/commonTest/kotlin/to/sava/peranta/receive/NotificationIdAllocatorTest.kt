package to.sava.peranta.receive

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NotificationIdAllocatorTest {

    /** 未知の payload.id には 1 から順に連番の通知 ID を割り当てる。 */
    @Test
    fun assignsSequentialIdsToNewPayloads() {
        val allocator = NotificationIdAllocator(MapSettings())
        assertEquals(1, allocator.idFor("a"))
        assertEquals(2, allocator.idFor("b"))
        assertEquals(3, allocator.idFor("c"))
    }

    /** 同じ payload.id には常に同じ通知 ID を返す（既読同期の対応付け）。 */
    @Test
    fun samePayloadIdReturnsSameNotificationId() {
        val allocator = NotificationIdAllocator(MapSettings())
        val first = allocator.idFor("payload")
        assertEquals(first, allocator.idFor("payload"))
    }

    /** 異なる payload.id には異なる通知 ID を割り当てる。 */
    @Test
    fun differentPayloadsGetDifferentIds() {
        val allocator = NotificationIdAllocator(MapSettings())
        assertNotEquals(allocator.idFor("x"), allocator.idFor("y"))
    }

    /** 割り当てはストレージに永続化され、別インスタンスでも引き継がれる。 */
    @Test
    fun assignmentsPersistAcrossInstances() {
        val settings = MapSettings()
        val first = NotificationIdAllocator(settings).idFor("keep")
        val second = NotificationIdAllocator(settings).idFor("keep")
        assertEquals(first, second)
        assertEquals(2, NotificationIdAllocator(settings).idFor("fresh"))
    }

    /** 対応表が上限を超えると、対応表の件数は上限に収まる（古い割り当てが FIFO で剪定される）。 */
    @Test
    fun prunesOldestBeyondCapacity() {
        val settings = MapSettings()
        val allocator = NotificationIdAllocator(settings, capacity = 2)
        allocator.idFor("a")
        allocator.idFor("b")
        allocator.idFor("c")
        val mappingKeys = settings.keys.filter { it.startsWith(NotificationIdAllocator.KEY_PREFIX) }
        assertEquals(2, mappingKeys.size)
    }

    /** 残った id は同じ通知 ID を保ち、剪定された古い id は次の要求で新しい番号に採番される。 */
    @Test
    fun retainedIdKeepsNumberWhilePrunedIdIsReassigned() {
        val settings = MapSettings()
        val allocator = NotificationIdAllocator(settings, capacity = 2)
        allocator.idFor("a")
        allocator.idFor("b")
        allocator.idFor("c")
        assertEquals(2, allocator.idFor("b"))
        assertEquals(3, allocator.idFor("c"))
        assertNotEquals(1, allocator.idFor("a"))
    }
}
