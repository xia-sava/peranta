package to.sava.peranta.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HealthCheckScreenTest {

    /** 呼び出しごとに順番に結果を返すチェッカ（再チェックで状態が変わる様子を模す）。 */
    private class QueueHealthChecker(private val results: List<List<HealthCheckItem>>) : HealthChecker {
        var calls = 0
        override suspend fun check(): List<HealthCheckItem> {
            val index = minOf(calls, results.size - 1)
            calls++
            return results[index]
        }
    }

    private fun checker(vararg items: HealthCheckItem): QueueHealthChecker =
        QueueHealthChecker(listOf(items.toList()))

    /** 不合格項目は ✗ マーカーと「直す」ボタンを出し、押すと onFix が呼ばれる。 */
    @Test
    fun failingItemShowsFixAndInvokesOnFix() = runComposeUiTest {
        var fixed = false
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(
                        id = "nls",
                        label = "通知へのアクセス",
                        state = HealthCheckState.FAILING,
                        fixLabel = "権限を許可",
                        onFix = { fixed = true },
                    ),
                ),
            )
        }
        onNodeWithTag("${TAG_HEALTH_STATE_PREFIX}nls").assertIsDisplayed()
        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}nls").performClick()
        assertTrue(fixed)
    }

    /** onFix が例外を送出すると、そのメッセージをエラー文として表示し、再チェックは走らない。 */
    @Test
    fun failingOnFixShowsErrorAndDoesNotRecheck() = runComposeUiTest {
        val checker = checker(
            HealthCheckItem(
                id = "autostart",
                label = "ログオン時の自動起動",
                state = HealthCheckState.FAILING,
                fixLabel = "登録する",
                onFix = { error("自動起動の登録に失敗しました。しばらくしてから再試行してください。") },
            ),
        )
        setContent { HealthCheckScreen(checker = checker) }

        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}autostart").performClick()
        onNodeWithTag("${TAG_HEALTH_FIX_ERROR_PREFIX}autostart").assertIsDisplayed()
        assertEquals(1, checker.calls)
    }

    /** 合格項目は「直す」ボタンを出さない。 */
    @Test
    fun passingItemHasNoFixButton() = runComposeUiTest {
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(id = "sms", label = "SMS の受信", state = HealthCheckState.PASS),
                ),
            )
        }
        onNodeWithTag("${TAG_HEALTH_STATE_PREFIX}sms").assertIsDisplayed()
        onAllNodesWithTag("${TAG_HEALTH_FIX_PREFIX}sms").assertCountEquals(0)
    }

    /** 対象外（NOT_APPLICABLE）項目は画面に描画しない。 */
    @Test
    fun notApplicableItemIsHidden() = runComposeUiTest {
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(id = "autostart", label = "自動起動", state = HealthCheckState.NOT_APPLICABLE),
                ),
            )
        }
        onAllNodesWithTag("${TAG_HEALTH_STATE_PREFIX}autostart").assertCountEquals(0)
    }

    /** 全項目が合格なら「すべて問題ありません」の案内を出す。 */
    @Test
    fun allPassShowsAllClear() = runComposeUiTest {
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(id = "sms", label = "SMS", state = HealthCheckState.PASS),
                ),
            )
        }
        onNodeWithTag(TAG_HEALTH_ALL_CLEAR).assertIsDisplayed()
    }

    /** 「今すぐ再チェック」を押すとチェッカを再実行し、新しい結果を反映する。 */
    @Test
    fun recheckReRunsChecker() = runComposeUiTest {
        val checker = QueueHealthChecker(
            listOf(
                listOf(HealthCheckItem(id = "unifiedpush", label = "登録", state = HealthCheckState.FAILING, fixLabel = "登録する", onFix = {})),
                listOf(HealthCheckItem(id = "unifiedpush", label = "登録", state = HealthCheckState.PASS)),
            ),
        )
        setContent { HealthCheckScreen(checker = checker) }

        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}unifiedpush").assertIsDisplayed()
        onNodeWithTag(TAG_HEALTH_RECHECK).performClick()
        onAllNodesWithTag("${TAG_HEALTH_FIX_PREFIX}unifiedpush").assertCountEquals(0)
        onNodeWithTag(TAG_HEALTH_ALL_CLEAR).assertIsDisplayed()
        assertEquals(2, checker.calls)
    }

    /** externalRefreshKey が変わると再チェックする（Android の ON_RESUME 再チェック相当）。 */
    @Test
    fun externalRefreshKeyReRunsChecker() = runComposeUiTest {
        val checker = QueueHealthChecker(
            listOf(
                listOf(HealthCheckItem(id = "x", label = "x", state = HealthCheckState.FAILING, fixLabel = "直す", onFix = {})),
                listOf(HealthCheckItem(id = "x", label = "x", state = HealthCheckState.PASS)),
            ),
        )
        val key = mutableStateOf(0)
        setContent { HealthCheckScreen(checker = checker, externalRefreshKey = key.value) }

        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}x").assertIsDisplayed()
        runOnIdle { key.value = 1 }
        onAllNodesWithTag("${TAG_HEALTH_FIX_PREFIX}x").assertCountEquals(0)
        assertEquals(2, checker.calls)
    }
}
