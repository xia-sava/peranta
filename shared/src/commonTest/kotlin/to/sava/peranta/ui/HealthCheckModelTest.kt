package to.sava.peranta.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HealthCheckModelTest {

    private fun item(id: String, state: HealthCheckState): HealthCheckItem =
        HealthCheckItem(id = id, label = id, state = state)

    /** 不合格項目の id だけを集め、合格・情報・対象外は含めない。 */
    @Test
    fun failingIdsCollectOnlyFailingItems() {
        val items = listOf(
            item("a", HealthCheckState.PASS),
            item("b", HealthCheckState.FAILING),
            item("c", HealthCheckState.INFO),
            item("d", HealthCheckState.FAILING),
            item("e", HealthCheckState.NOT_APPLICABLE),
        )
        assertEquals(setOf("b", "d"), failingHealthCheckIds(items))
    }

    /** 不合格が無ければ空集合を返す。 */
    @Test
    fun failingIdsEmptyWhenNoFailure() {
        val items = listOf(item("a", HealthCheckState.PASS), item("b", HealthCheckState.INFO))
        assertTrue(failingHealthCheckIds(items).isEmpty())
    }

    /** 空の結果は不合格なしとして空集合を返す。 */
    @Test
    fun emptyItemsYieldNoFailingIds() {
        assertTrue(failingHealthCheckIds(emptyList()).isEmpty())
    }

    /** 誘導リンクと実行系の「直す」（fixLabel/onFix）は排他で、両方指定はアサートで弾く。 */
    @Test
    fun linkAndFixCannotCoexist() {
        assertFailsWith<IllegalArgumentException> {
            HealthCheckItem(
                id = "x",
                label = "x",
                state = HealthCheckState.FAILING,
                fixLabel = "直す",
                onFix = {},
                link = HealthCheckLink(label = "開く", onOpen = {}),
            )
        }
    }

    /** 誘導リンクだけを持つ項目は生成でき、不合格の集計は状態だけで決まる。 */
    @Test
    fun linkOnlyItemIsAllowedAndCountedByState() {
        val item = HealthCheckItem(
            id = "x",
            label = "x",
            state = HealthCheckState.FAILING,
            link = HealthCheckLink(label = "開く", onOpen = {}),
        )
        assertEquals(setOf("x"), failingHealthCheckIds(listOf(item)))
    }
}
