package to.sava.peranta.timeline

import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.NotificationPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /** record は新規追記なら true を返す。 */
    @Test
    fun recordReturnsTrueForNewItem() = runTest {
        val feed = TimelineFeed(store())

        val appended = feed.record(notification("n1", 100))

        assertTrue(appended)
    }

    /** record は同一 id の置換なら false を返す。 */
    @Test
    fun recordReturnsFalseForReplacedItem() = runTest {
        val feed = TimelineFeed(store())
        feed.record(notification("n1", 100))

        val appended = feed.record(notification("n1", 200))

        assertFalse(appended)
        assertEquals(200, feed.items.value.single().timestampEpochMillis)
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

    /** 在メモリのタイムラインは上限で頭打ちになり、超えた分は古い順に落ちる。 */
    @Test
    fun itemsAreCappedAtMaxItems() = runTest {
        val feed = TimelineFeed(store(), maxItems = 3)

        repeat(10) { feed.record(notification("n$it", 100L + it)) }

        assertEquals(listOf("n7", "n8", "n9"), feed.items.value.map { it.id })
    }

    /** load も同じ上限を適用し、履歴が上限を超えていても在メモリは膨らまない。 */
    @Test
    fun loadIsCappedAtMaxItems() = runTest {
        val store = store()
        repeat(10) { store.append(notification("n$it", 100L + it)) }
        val feed = TimelineFeed(store, maxItems = 3)

        feed.load(now = 1_000)

        assertEquals(listOf("n7", "n8", "n9"), feed.items.value.map { it.id })
    }

    /** 起動時の prune を通っていれば、稼働中も一定件数ごとに同じ条件で永続側が剪定される。 */
    @Test
    fun storeIsPrunedWhileRunningWithTheStartupPolicy() = runTest {
        val calls = mutableListOf<Pair<Int, Long?>>()
        val store = object : TimelineStore {
            override suspend fun append(item: TimelineItem) {}
            override suspend fun loadAll(): List<TimelineItem> = emptyList()
            override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {
                calls += maxItems to maxAgeMillis
            }
        }
        val feed = TimelineFeed(store)
        feed.prune(maxItems = 500, now = 0, maxAgeMillis = 42)

        repeat(400) { feed.record(notification("n$it", 100L + it)) }

        assertEquals(3, calls.size)
        assertTrue(calls.all { it == 500 to 42L })
    }

    /** 起動時の prune を通っていなければ、稼働中に永続側の剪定は走らない。 */
    @Test
    fun storeIsNotPrunedWithoutStartupPrune() = runTest {
        var pruneCalls = 0
        val store = object : TimelineStore {
            override suspend fun append(item: TimelineItem) {}
            override suspend fun loadAll(): List<TimelineItem> = emptyList()
            override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {
                pruneCalls++
            }
        }
        val feed = TimelineFeed(store)

        repeat(400) { feed.record(notification("n$it", 100L + it)) }

        assertEquals(0, pruneCalls)
    }

    /** 上限を超える追記でも、その 1 件は表示に載る（新しいものが落ちない）。 */
    @Test
    fun newestItemSurvivesTheCap() = runTest {
        val feed = TimelineFeed(store(), maxItems = 1)

        feed.record(notification("old", 100))
        feed.record(notification("new", 200))

        assertEquals(listOf("new"), feed.items.value.map { it.id })
    }
}
