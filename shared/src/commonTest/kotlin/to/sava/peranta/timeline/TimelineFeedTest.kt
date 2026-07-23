package to.sava.peranta.timeline

import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.NotificationPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimelineFeedTest {

    private fun store(): TimelineStore = JsonlTimelineStore(FakeTimelineFile())

    private fun notification(id: String, timestamp: Long, expiresAt: Long? = null): ReceivedNotification =
        ReceivedNotification(
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
            ),
        )

    /** append 時に必ず失敗する永続化層。読み込みは常に空を返す。 */
    private class FailingStore : TimelineStore {
        override suspend fun append(item: TimelineItem): Unit = throw IllegalStateException("persist failed")
        override suspend fun loadAll(): List<TimelineItem> = emptyList()
        override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {}
    }

    /** append は永続化と同時に items へ即時反映する。 */
    @Test
    fun appendReflectsImmediatelyInItems() = runTest {
        val feed = TimelineFeed(store())
        val item = notification("n1", 100)

        feed.append(item)

        assertEquals(listOf(item), feed.items.value)
    }

    /** append は永続化した値をそのまま store にも残す。 */
    @Test
    fun appendPersistsToStore() = runTest {
        val store = store()
        val feed = TimelineFeed(store)
        val item = notification("n1", 100)

        feed.append(item)

        assertEquals(listOf(item), store.loadAll())
    }

    /** record は表示用と永続用を分けられ、items には displayItem・store には persistItem が残る。 */
    @Test
    fun recordSeparatesDisplayAndPersist() = runTest {
        val store = store()
        val feed = TimelineFeed(store)
        val display = notification("n1", 100)
        val persisted = display.copy(payload = (display.payload as NotificationPayload).copy(text = "伏せ字"))

        feed.record(displayItem = display, persistItem = persisted)

        val shown = (feed.items.value.single() as ReceivedNotification).payload as NotificationPayload
        val stored = (store.loadAll().single() as ReceivedNotification).payload as NotificationPayload
        assertEquals("body n1", shown.text)
        assertEquals("伏せ字", stored.text)
    }

    /** load は失効済みアイテムを items から除外するが、戻り値には全履歴を含める（dedupe 初期化用）。 */
    @Test
    fun loadExcludesExpiredFromItemsButReturnsAllHistory() = runTest {
        val store = store()
        store.append(notification("expired", 100, expiresAt = 500))
        store.append(notification("alive", 200, expiresAt = 5_000))
        val feed = TimelineFeed(store)

        val history = feed.load(now = 1_000)

        assertEquals(listOf("expired", "alive"), history.map { it.id })
        assertEquals(listOf("alive"), feed.items.value.map { it.id })
    }

    /** load は同一 id を後勝ちで畳み、items には最後に保存された値だけが残る。 */
    @Test
    fun loadFoldsDuplicateIdsKeepingLastValue() = runTest {
        val store = store()
        store.append(notification("n1", 100))
        store.append(notification("n1", 200))
        val feed = TimelineFeed(store)

        feed.load(now = 1_000)

        val items = feed.items.value
        assertEquals(1, items.size)
        assertEquals(200, items.single().timestampEpochMillis)
    }

    /** 同一 id での append は末尾追記ではなく置換になる（upsert）。 */
    @Test
    fun appendOfSameIdReplacesExistingItem() = runTest {
        val feed = TimelineFeed(store())
        feed.append(notification("n1", 100))

        feed.append(notification("n1", 200))

        val items = feed.items.value
        assertEquals(1, items.size)
        assertEquals(200, items.single().timestampEpochMillis)
    }

    /** load は同一 id の在メモリ版を履歴側より優先して残す（伏せ字前の表示を読込で巻き戻さない）。 */
    @Test
    fun loadKeepsInMemoryVersionOverHistoryForSameId() = runTest {
        val store = store()
        val feed = TimelineFeed(store)
        val display = notification("n1", 100)
        val persisted = display.copy(payload = (display.payload as NotificationPayload).copy(text = "伏せ字"))
        feed.record(displayItem = display, persistItem = persisted)

        feed.load(now = 1_000)

        val shown = (feed.items.value.single() as ReceivedNotification).payload as NotificationPayload
        assertEquals("body n1", shown.text)
    }

    /** load は履歴に未反映の在メモリのアイテムを消さない（読込と並行した追記を巻き戻さない）。 */
    @Test
    fun loadKeepsInMemoryItemsMissingFromHistory() = runTest {
        val old = notification("old", 100)
        // 永続には失敗するが履歴として old だけを返す store。表示にのみ載ったアイテムを作るために使う。
        val store = object : TimelineStore {
            override suspend fun append(item: TimelineItem): Unit = throw IllegalStateException("persist failed")
            override suspend fun loadAll(): List<TimelineItem> = listOf(old)
            override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {}
        }
        val feed = TimelineFeed(store)
        feed.record(notification("volatile", 200))

        feed.load(now = 1_000)

        assertEquals(listOf("old", "volatile"), feed.items.value.map { it.id })
    }

    /** record は永続失敗を握り、items への反映は継続する。 */
    @Test
    fun recordContinuesDisplayWhenPersistFails() = runTest {
        val feed = TimelineFeed(FailingStore())
        val item = notification("n1", 100)

        feed.record(item)

        assertEquals(listOf(item), feed.items.value)
    }

    /** append は永続失敗を呼び出し側へ伝播し、items へは反映しない。 */
    @Test
    fun appendPropagatesPersistFailureAndDoesNotReflect() = runTest {
        val feed = TimelineFeed(FailingStore())

        assertFailsWith<IllegalStateException> { feed.append(notification("n1", 100)) }

        assertTrue(feed.items.value.isEmpty())
    }
}
