package to.sava.peranta.ui.setup

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import to.sava.peranta.ui.FixAid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SetupChecklistTest {

    private fun item(
        id: String = "unifiedpush",
        title: String = "UnifiedPush の登録",
        description: String? = "登録してください。",
        status: SetupStatus = SetupStatus.TODO,
        statusDetail: String? = null,
        aids: List<FixAid> = emptyList(),
        action: SetupAction? = null,
    ): SetupItemUi = SetupItemUi(
        id = id,
        title = title,
        description = description,
        status = status,
        statusDetail = statusDetail,
        aids = aids,
        action = action,
    )

    /** 常設モードは番号付きタイトル・状態バッジ・説明文・主操作ボタンを描く。 */
    @Test
    fun standingModeRendersNumberedTitleBadgeDescriptionAndAction() = runComposeUiTest {
        setContent {
            SetupChecklist(
                items = listOf(item(action = SetupAction(label = "登録する", run = {}))),
                mode = SetupChecklistMode.STANDING,
            )
        }
        onNodeWithText("1. UnifiedPush の登録").assertIsDisplayed()
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}unifiedpush").assertIsDisplayed()
        onNodeWithText("登録してください。").assertIsDisplayed()
        onNodeWithTag("${TAG_SETUP_ACTION_PREFIX}unifiedpush").assertIsDisplayed()
    }

    /** ページ内モードは番号と説明文を省き、タイトルだけを描く。 */
    @Test
    fun inPageModeOmitsNumberAndDescription() = runComposeUiTest {
        setContent {
            SetupChecklist(
                items = listOf(item()),
                mode = SetupChecklistMode.IN_PAGE,
            )
        }
        onNodeWithText("UnifiedPush の登録").assertIsDisplayed()
        onNodeWithText("1. UnifiedPush の登録").assertDoesNotExist()
        onNodeWithText("登録してください。").assertDoesNotExist()
    }

    /** 未確認（UNKNOWN）は ─ バッジに「未確認」の文言を添える。 */
    @Test
    fun unknownStatusShowsUnknownLabel() = runComposeUiTest {
        setContent {
            SetupChecklist(
                items = listOf(item(id = "up-server-config", status = SetupStatus.UNKNOWN)),
                mode = SetupChecklistMode.STANDING,
            )
        }
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}up-server-config").assertIsDisplayed()
        onNodeWithText("未確認").assertIsDisplayed()
    }

    /** 状態バッジは合格 ✓ / 未達 ✗ / 未確認 ─ を描き分ける。 */
    @Test
    fun statusBadgeDistinguishesDoneTodoAndUnknown() = runComposeUiTest {
        setContent {
            SetupChecklist(
                items = listOf(
                    item(id = "ntfy-installed", status = SetupStatus.DONE),
                    item(id = "unifiedpush", status = SetupStatus.TODO),
                    item(id = "up-server-config", status = SetupStatus.UNKNOWN),
                ),
                mode = SetupChecklistMode.STANDING,
            )
        }
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}ntfy-installed").assertTextEquals("✓")
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}unifiedpush").assertTextEquals("✗")
        onNodeWithTag("${TAG_SETUP_STATUS_PREFIX}up-server-config").assertTextEquals("─")
    }

    /** コピーチップを押すと押した行に「コピーしました」が表示される。 */
    @Test
    fun copyChipShowsCopiedLabel() = runComposeUiTest {
        setContent {
            SetupChecklist(
                items = listOf(
                    item(
                        id = "up-server-config",
                        aids = listOf(FixAid.Copy(label = "サーバーURL", value = "https://example.com")),
                    ),
                ),
                mode = SetupChecklistMode.STANDING,
                onCopyText = { _, _ -> },
            )
        }
        onNodeWithText("コピーしました").assertDoesNotExist()
        onNodeWithTag("${TAG_SETUP_AID_PREFIX}up-server-config-0").performClick()
        onNodeWithText("コピーしました").assertIsDisplayed()
    }

    /** 主操作ボタンを押すと action.run が呼ばれる。 */
    @Test
    fun actionButtonInvokesRun() = runComposeUiTest {
        var ran = false
        setContent {
            SetupChecklist(
                items = listOf(item(action = SetupAction(label = "登録する", run = { ran = true }))),
                mode = SetupChecklistMode.STANDING,
            )
        }
        onNodeWithTag("${TAG_SETUP_ACTION_PREFIX}unifiedpush").performClick()
        assertTrue(ran)
    }

    /** コピーチップを押すと onCopyText が (value, sensitive) で呼ばれ、Action チップは onRun を呼ぶ。 */
    @Test
    fun copyChipInvokesOnCopyTextAndActionChipInvokesOnRun() = runComposeUiTest {
        var copiedValue: String? = null
        var copiedSensitive: Boolean? = null
        var actionRun = false
        setContent {
            SetupChecklist(
                items = listOf(
                    item(
                        id = "up-server-config",
                        aids = listOf(
                            FixAid.Copy(label = "サーバーURL", value = "https://example.com"),
                            FixAid.Copy(label = "ヘッダ値", value = "Bearer tk", sensitive = true),
                            FixAid.Action(label = "ntfy を開く", onRun = { actionRun = true }),
                        ),
                    ),
                ),
                mode = SetupChecklistMode.STANDING,
                onCopyText = { text, sensitive ->
                    copiedValue = text
                    copiedSensitive = sensitive
                },
            )
        }
        onNodeWithTag("${TAG_SETUP_AID_PREFIX}up-server-config-1").performClick()
        assertEquals("Bearer tk", copiedValue)
        assertEquals(true, copiedSensitive)
        assertFalse(actionRun)

        onNodeWithTag("${TAG_SETUP_AID_PREFIX}up-server-config-2").performClick()
        assertTrue(actionRun)
    }

    /** ページ内モードでもコピーチップは描画され機能する（ウィザードの手順2 で使う）。 */
    @Test
    fun inPageModeStillRendersCopyChips() = runComposeUiTest {
        var copiedValue: String? = null
        setContent {
            SetupChecklist(
                items = listOf(
                    item(
                        id = "up-server-config",
                        aids = listOf(FixAid.Copy(label = "サーバーURL", value = "https://example.com")),
                    ),
                ),
                mode = SetupChecklistMode.IN_PAGE,
                onCopyText = { text, _ -> copiedValue = text },
            )
        }
        onNodeWithTag("${TAG_SETUP_AID_PREFIX}up-server-config-0").performClick()
        assertEquals("https://example.com", copiedValue)
    }
}
