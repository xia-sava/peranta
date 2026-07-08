package to.sava.peranta.timeline

import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonlTimelineStoreTest {

    private fun received(
        id: String,
        timestamp: Long,
        expiresAt: Long? = null,
    ): ReceivedNotification = ReceivedNotification(
        id = id,
        timestampEpochMillis = timestamp,
        expiresAtEpochMillis = expiresAt,
        payload = NotificationPayload(
            id = id,
            from = "phone",
            to = "*",
            sentAtEpochMillis = timestamp,
            packageName = "com.example",
            appName = "Example",
            title = "T $id",
            text = "body $id",
            notificationKey = "0|com.example|$id|null|10",
            postedAtEpochMillis = timestamp,
            expiresAtEpochMillis = expiresAt,
            priority = Priority.HIGH,
        ),
    )

    /** append した順に loadAll で同じ値が読み戻せる。 */
    @Test
    fun appendThenLoadAllRoundTrips() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        val a = received("a", 100)
        val b = ErrorItem("e", 200, "失敗", ErrorKind.DECRYPTION)
        val c = received("c", 300)
        store.append(a)
        store.append(b)
        store.append(c)
        assertEquals(listOf<TimelineItem>(a, b, c), store.loadAll())
    }

    /** prune は now より前に失効したアイテムを落とし、失効しない/未失効を残す。 */
    @Test
    fun pruneDropsExpiredItems() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        store.append(received("expired", 100, expiresAt = 500))
        store.append(received("alive", 200, expiresAt = 5_000))
        store.append(received("permanent", 300, expiresAt = null))
        store.prune(now = 1_000)
        assertEquals(listOf("alive", "permanent"), store.loadAll().map { it.id })
    }

    /** prune は上限を超えた分を落とし、新しい maxItems 件を残す。 */
    @Test
    fun pruneKeepsMostRecentUpToLimit() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        (1..5).forEach { store.append(received("n$it", it.toLong())) }
        store.prune(maxItems = 3, now = 0)
        assertEquals(listOf("n3", "n4", "n5"), store.loadAll().map { it.id })
    }

    /** 壊れた行があっても他の正しい行は読める。 */
    @Test
    fun corruptLineIsSkipped() = runTest {
        val good = received("good", 100)
        val file = FakeTimelineFile(
            listOf(
                """{"type":"received"...broken""",
                to.sava.peranta.model.PerantaJson.encodeToString<TimelineItem>(good),
                "",
            ),
        )
        val store = JsonlTimelineStore(file)
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("good", loaded.first().id)
    }

    /** append は既存内容を保ったまま追記する（上書きしない）。 */
    @Test
    fun appendPreservesExisting() = runTest {
        val file = FakeTimelineFile()
        val store = JsonlTimelineStore(file)
        store.append(received("first", 1))
        store.append(received("second", 2))
        assertEquals(2, file.readLines().size)
        assertTrue(file.readLines().all { it.isNotBlank() })
    }
}
