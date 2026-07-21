package to.sava.peranta.ui

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthCheckModelTest {

    private fun item(id: String, state: HealthCheckState): HealthCheckItem =
        HealthCheckItem(id = id, label = id, state = state)

    /** 不合格項目が 1 つでもあれば対処要と判定する。 */
    @Test
    fun failingItemNeedsAttention() {
        val items = listOf(
            item("a", HealthCheckState.PASS),
            item("b", HealthCheckState.FAILING),
        )
        assertTrue(healthCheckNeedsAttention(items))
    }

    /** 合格・情報・対象外だけなら対処不要と判定する（情報項目は対処を強制しない）。 */
    @Test
    fun onlyNonFailingItemsDoNotNeedAttention() {
        val items = listOf(
            item("a", HealthCheckState.PASS),
            item("b", HealthCheckState.INFO),
            item("c", HealthCheckState.NOT_APPLICABLE),
        )
        assertFalse(healthCheckNeedsAttention(items))
    }

    /** 空の結果は対処不要とみなす。 */
    @Test
    fun emptyItemsDoNotNeedAttention() {
        assertFalse(healthCheckNeedsAttention(emptyList()))
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

    /** 誘導リンクだけを持つ項目は生成でき、対処要否は状態だけで決まる。 */
    @Test
    fun linkOnlyItemIsAllowedAndAttentionFollowsState() {
        val item = HealthCheckItem(
            id = "x",
            label = "x",
            state = HealthCheckState.FAILING,
            link = HealthCheckLink(label = "開く", onOpen = {}),
        )
        assertTrue(healthCheckNeedsAttention(listOf(item)))
    }
}
