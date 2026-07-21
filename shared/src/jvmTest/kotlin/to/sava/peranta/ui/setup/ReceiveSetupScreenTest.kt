package to.sava.peranta.ui.setup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import to.sava.peranta.ui.FixAid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReceiveSetupScreenTest {

    /** 呼び出しごとに順番に結果を返す供給元（再チェックで状態が変わる様子を模す）。 */
    private class QueueProvider(private val results: List<List<SetupItemUi>>) : SetupItemsProvider {
        var calls = 0
        override suspend fun items(): List<SetupItemUi> {
            val index = minOf(calls, results.size - 1)
            calls++
            return results[index]
        }
    }

    private fun provider(vararg items: SetupItemUi): QueueProvider =
        QueueProvider(listOf(items.toList()))

    private fun item(
        id: String,
        status: SetupStatus = SetupStatus.DONE,
        aids: List<FixAid> = emptyList(),
        action: SetupAction? = null,
    ): SetupItemUi = SetupItemUi(
        id = id,
        title = ReceiveSetupSteps.titleOf(id),
        description = ReceiveSetupSteps.descriptionOf(id),
        status = status,
        statusDetail = null,
        aids = aids,
        action = action,
    )

    /**
     * この画面の存在理由の回帰テスト。全項目がオールグリーン（DONE）でも、
     * コピーチップと主操作ボタンが同じ位置に出続ける。
     */
    @Test
    fun standingToolsRemainWhenAllDone() = runComposeUiTest {
        setContent {
            ReceiveSetupScreen(
                provider = provider(
                    item(
                        id = ReceiveSetupSteps.NTFY_INSTALLED_ID,
                        action = SetupAction(label = "ストアで開く", run = {}),
                    ),
                    item(
                        id = ReceiveSetupSteps.SERVER_CONFIG_ID,
                        aids = listOf(FixAid.Copy(label = "サーバーURL", value = "https://example.com")),
                    ),
                    item(
                        id = ReceiveSetupSteps.UNIFIED_PUSH_ID,
                        action = SetupAction(label = "登録し直す", run = {}),
                    ),
                ),
                onCopyText = { _, _ -> },
            )
        }
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}${ReceiveSetupSteps.NTFY_INSTALLED_ID}").assertTextEquals("✓")
        onNodeWithTag("${TAG_SETUP_ACTION_PREFIX}${ReceiveSetupSteps.NTFY_INSTALLED_ID}").assertIsDisplayed()
        onNodeWithTag("${TAG_SETUP_AID_PREFIX}${ReceiveSetupSteps.SERVER_CONFIG_ID}-0").assertIsDisplayed()
        onNodeWithTag("${TAG_SETUP_ACTION_PREFIX}${ReceiveSetupSteps.UNIFIED_PUSH_ID}").assertIsDisplayed()
    }

    /** 「今すぐ再チェック」で供給元を再度呼び、新しい結果を反映する。 */
    @Test
    fun recheckReloadsProvider() = runComposeUiTest {
        val provider = QueueProvider(
            listOf(
                listOf(item(id = ReceiveSetupSteps.UNIFIED_PUSH_ID, status = SetupStatus.TODO)),
                listOf(item(id = ReceiveSetupSteps.UNIFIED_PUSH_ID, status = SetupStatus.DONE)),
            ),
        )
        setContent { ReceiveSetupScreen(provider = provider) }

        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}${ReceiveSetupSteps.UNIFIED_PUSH_ID}").assertTextEquals("✗")
        onNodeWithTag(TAG_RECEIVE_SETUP_RECHECK).performClick()
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}${ReceiveSetupSteps.UNIFIED_PUSH_ID}").assertTextEquals("✓")
        assertEquals(2, provider.calls)
    }

    /** 手順の操作を実行すると、その操作のあとに自動再チェックが走り、新しい結果を反映する。 */
    @Test
    fun actionRunTriggersRecheck() = runComposeUiTest {
        var actionRan = false
        val provider = QueueProvider(
            listOf(
                listOf(
                    item(
                        id = ReceiveSetupSteps.UNIFIED_PUSH_ID,
                        status = SetupStatus.TODO,
                        action = SetupAction(label = "登録する", run = { actionRan = true }),
                    ),
                ),
                listOf(item(id = ReceiveSetupSteps.UNIFIED_PUSH_ID, status = SetupStatus.DONE)),
            ),
        )
        setContent { ReceiveSetupScreen(provider = provider) }

        onNodeWithTag("${TAG_SETUP_ACTION_PREFIX}${ReceiveSetupSteps.UNIFIED_PUSH_ID}").performClick()
        assertTrue(actionRan)
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}${ReceiveSetupSteps.UNIFIED_PUSH_ID}").assertTextEquals("✓")
        assertTrue(provider.calls >= 2)
    }

    /** コピーチップ（FixAid.Copy）押下では再チェックが走らず、操作（FixAid.Action）押下でのみ走る。 */
    @Test
    fun copyChipDoesNotRecheckButActionChipDoes() = runComposeUiTest {
        val provider = provider(
            item(
                id = ReceiveSetupSteps.SERVER_CONFIG_ID,
                aids = listOf(
                    FixAid.Copy(label = "サーバーURL", value = "https://example.com"),
                    FixAid.Action(label = "ntfy を開く", onRun = {}),
                ),
            ),
        )
        setContent { ReceiveSetupScreen(provider = provider, onCopyText = { _, _ -> }) }

        onNodeWithTag("${TAG_SETUP_AID_PREFIX}${ReceiveSetupSteps.SERVER_CONFIG_ID}-0").performClick()
        waitForIdle()
        assertEquals(1, provider.calls)
        onNodeWithTag("${TAG_SETUP_AID_PREFIX}${ReceiveSetupSteps.SERVER_CONFIG_ID}-1").performClick()
        waitForIdle()
        assertTrue(provider.calls >= 2)
    }

    /** externalRefreshKey が変わると再読込する（Android の ON_RESUME 再チェック相当）。 */
    @Test
    fun externalRefreshKeyReloadsProvider() = runComposeUiTest {
        val provider = QueueProvider(
            listOf(
                listOf(item(id = ReceiveSetupSteps.NTFY_BATTERY_ID, status = SetupStatus.TODO)),
                listOf(item(id = ReceiveSetupSteps.NTFY_BATTERY_ID, status = SetupStatus.DONE)),
            ),
        )
        val key = mutableStateOf(0)
        setContent { ReceiveSetupScreen(provider = provider, externalRefreshKey = key.value) }

        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}${ReceiveSetupSteps.NTFY_BATTERY_ID}").assertTextEquals("✗")
        runOnIdle { key.value = 1 }
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}${ReceiveSetupSteps.NTFY_BATTERY_ID}").assertTextEquals("✓")
        assertEquals(2, provider.calls)
    }
}
