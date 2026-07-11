package to.sava.peranta.blob

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentCachePruneTest {

    private val hour = 60L * 60 * 1000

    /** 最終アクセスから保持上限を超えたキャッシュを削除対象にする。 */
    @Test
    fun removesEntriesPastMaxAge() {
        val now = 100L * hour
        val entries = listOf(
            CachedAttachment("fresh", sizeBytes = 10, lastAccessEpochMillis = now - 1 * hour),
            CachedAttachment("stale", sizeBytes = 10, lastAccessEpochMillis = now - 30 * hour),
        )
        val pruned = selectAttachmentsToPrune(entries, now)
        assertEquals(listOf("stale"), pruned)
    }

    /** 合計サイズが上限を超える分を、最終アクセスが古い順に削除する。 */
    @Test
    fun removesOldestUntilUnderTotalLimit() {
        val now = 100L * hour
        val entries = listOf(
            CachedAttachment("newest", sizeBytes = 600, lastAccessEpochMillis = now - 1 * hour),
            CachedAttachment("middle", sizeBytes = 600, lastAccessEpochMillis = now - 2 * hour),
            CachedAttachment("oldest", sizeBytes = 600, lastAccessEpochMillis = now - 3 * hour),
        )
        val pruned = selectAttachmentsToPrune(entries, now, maxAgeMillis = Long.MAX_VALUE, maxTotalBytes = 1000)
        // 合計 1800 > 1000。古い順に削って 1000 以下（1200 ではまだ超過なので 2 件削除）。
        assertEquals(listOf("oldest", "middle"), pruned)
    }

    /** 上限内なら何も削除しない。 */
    @Test
    fun keepsEverythingWithinLimits() {
        val now = 100L * hour
        val entries = listOf(
            CachedAttachment("a", sizeBytes = 100, lastAccessEpochMillis = now - 1 * hour),
            CachedAttachment("b", sizeBytes = 100, lastAccessEpochMillis = now - 2 * hour),
        )
        assertTrue(selectAttachmentsToPrune(entries, now, maxTotalBytes = 1000).isEmpty())
    }

    /** 期限切れ削除後にサイズ超過が解消されれば、サイズ剪定は追加で削除しない。 */
    @Test
    fun ageAndSizePruningCombine() {
        val now = 100L * hour
        val entries = listOf(
            CachedAttachment("staleBig", sizeBytes = 900, lastAccessEpochMillis = now - 30 * hour),
            CachedAttachment("freshSmall", sizeBytes = 100, lastAccessEpochMillis = now - 1 * hour),
        )
        val pruned = selectAttachmentsToPrune(entries, now, maxTotalBytes = 500)
        assertEquals(listOf("staleBig"), pruned)
    }
}
