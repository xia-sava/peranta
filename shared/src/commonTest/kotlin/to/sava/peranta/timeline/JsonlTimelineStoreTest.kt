package to.sava.peranta.timeline

import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonlTimelineStoreTest {

    private fun received(
        id: String,
        timestamp: Long,
        expiresAt: Long? = null,
        sourceDismissed: Boolean = false,
    ): ReceivedNotification = ReceivedNotification(
        id = id,
        timestampEpochMillis = timestamp,
        expiresAtEpochMillis = expiresAt,
        sourceDismissed = sourceDismissed,
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

    private fun sent(id: String, timestamp: Long): SentNotification = SentNotification(
        id = id,
        timestampEpochMillis = timestamp,
        expiresAtEpochMillis = null,
        payload = NotificationPayload(
            id = id,
            from = "desk",
            to = "phone",
            sentAtEpochMillis = timestamp,
            packageName = "com.example",
            appName = "Example",
            title = "T $id",
            text = "body $id",
            notificationKey = "0|com.example|$id|null|10",
            postedAtEpochMillis = timestamp,
        ),
    )

    /** SentNotification も append/loadAll で同じ値に往復する。 */
    @Test
    fun sentNotificationRoundTrips() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        val item = sent("s1", 100)
        store.append(item)
        assertEquals(listOf<TimelineItem>(item), store.loadAll())
    }

    /** prune の件数がちょうど上限のときは全件残る。 */
    @Test
    fun pruneKeepsAllWhenExactlyAtLimit() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        (1..3).forEach { store.append(received("n$it", it.toLong())) }
        store.prune(maxItems = 3, now = 0)
        assertEquals(listOf("n1", "n2", "n3"), store.loadAll().map { it.id })
    }

    /** 全件が失効している場合、prune 後は空になる。 */
    @Test
    fun pruneRemovesEverythingWhenAllExpired() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        store.append(received("a", 100, expiresAt = 10))
        store.append(received("b", 200, expiresAt = 20))
        store.prune(now = 1_000)
        assertTrue(store.loadAll().isEmpty())
    }

    /** 空のストアに対する prune は空のまま何も壊さない。 */
    @Test
    fun pruneOnEmptyStoreStaysEmpty() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        store.prune(now = 1_000)
        assertTrue(store.loadAll().isEmpty())
    }

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

    /**
     * prune は maxAgeMillis 指定時、cutoff（now - maxAgeMillis）より古い timestampEpochMillis の
     * アイテムを落とし、cutoff 以降のアイテムは残す（§11 の保持日数）。
     */
    @Test
    fun pruneDropsItemsOlderThanMaxAge() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        store.append(received("old", timestamp = 1_000))
        store.append(received("atCutoff", timestamp = 9_000))
        store.append(received("recent", timestamp = 9_500))
        store.prune(now = 10_000, maxAgeMillis = 1_000)
        assertEquals(listOf("atCutoff", "recent"), store.loadAll().map { it.id })
    }

    /** maxAgeMillis が null（既定）のときは、日数による剪定を行わず古いアイテムも残る。 */
    @Test
    fun pruneKeepsOldItemsWhenMaxAgeMillisIsNull() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        store.append(received("veryOld", timestamp = 0))
        store.prune(now = 1_000_000, maxAgeMillis = null)
        assertEquals(listOf("veryOld"), store.loadAll().map { it.id })
    }

    /**
     * maxAgeMillis による日数剪定と、既存の失効時刻（expiresAtEpochMillis）・件数上限の剪定は
     * 独立に効き、互いの挙動を変えない。
     */
    @Test
    fun pruneCombinesMaxAgeWithExpiryAndItemLimit() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        // 失効済み（maxAge の対象外の新しいタイムスタンプでも expiresAt で落ちる）。
        store.append(received("expired", timestamp = 9_900, expiresAt = 500))
        // maxAge で落ちる（失効はしていない）。
        store.append(received("tooOld", timestamp = 1_000))
        // 両方の条件を満たし残る。
        store.append(received("keep1", timestamp = 9_000))
        store.append(received("keep2", timestamp = 9_500))
        store.prune(maxItems = 1, now = 10_000, maxAgeMillis = 1_000)
        assertEquals(listOf("keep2"), store.loadAll().map { it.id })
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

    /** sourceDismissed=true（§3.4）の ReceivedNotification も append/loadAll で保持される。 */
    @Test
    fun sourceDismissedRoundTrips() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        val item = received("a", 100, sourceDismissed = true)
        store.append(item)
        assertEquals(listOf<TimelineItem>(item), store.loadAll())
    }

    /** sourceDismissed（§3.4）を持たない旧バージョン由来の行も読め、sourceDismissed は false になる。 */
    @Test
    fun decodeLegacyLineWithoutSourceDismissedFallsBackToFalse() = runTest {
        val json = """
            {"type":"received","id":"legacy1","timestampEpochMillis":100,
            "payload":{"type":"notification","id":"legacy1","from":"phone","to":"*","sentAtEpochMillis":100,
            "packageName":"com.example","appName":"Example","title":"t","text":"b","notificationKey":"k",
            "postedAtEpochMillis":100}}
        """.trimIndent()
        val store = JsonlTimelineStore(FakeTimelineFile(listOf(json)))
        val loaded = store.loadAll().single() as ReceivedNotification
        assertFalse(loaded.sourceDismissed)
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
