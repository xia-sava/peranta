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
import kotlin.test.assertFalse
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

    /** fixGuidance 付きの項目は「直す」で先に案内ダイアログを出し、続行して初めて onFix を実行する。 */
    @Test
    fun guidanceDialogShowsBeforeFix() = runComposeUiTest {
        var fixed = false
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(
                        id = "sms",
                        label = "SMS の受信",
                        state = HealthCheckState.FAILING,
                        fixLabel = "設定を開く",
                        onFix = { fixed = true },
                        fixGuidance = "アプリ情報画面で権限を変更してください。",
                    ),
                ),
            )
        }
        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}sms").performClick()
        assertFalse(fixed)
        onNodeWithTag("${TAG_HEALTH_FIX_GUIDANCE_OK_PREFIX}sms").performClick()
        assertTrue(fixed)
    }

    /**
     * fixGuidance + fixAids 付きの項目で「直す」を押すと、案内ダイアログに補助ボタンが並ぶ。
     * Copy ボタンを押すと onCopyText が (value, sensitive) で呼ばれ、Action ボタンを押すと onRun が
     * 呼ばれる。どちらもダイアログを閉じない（続けるボタンがまだ存在する）。その後「続ける」で onFix が呼ばれる。
     */
    @Test
    fun fixAidsInvokeCopyAndActionWithoutClosingDialog() = runComposeUiTest {
        var fixed = false
        var copiedValue: String? = null
        var copiedSensitive: Boolean? = null
        var actionRun = false
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(
                        id = "guided-fix",
                        label = "コピー補助つきの修復項目",
                        state = HealthCheckState.FAILING,
                        fixLabel = "登録し直す",
                        onFix = { fixed = true },
                        fixGuidance = "案内文",
                        fixAids = listOf(
                            FixAid.Copy(label = "サーバーURL", value = "https://example.com"),
                            FixAid.Copy(label = "認証ヘッダの値", value = "Bearer token", sensitive = true),
                            FixAid.Action(label = "ntfy を開く", onRun = { actionRun = true }),
                        ),
                    ),
                ),
                onCopyText = { text, sensitive ->
                    copiedValue = text
                    copiedSensitive = sensitive
                },
            )
        }
        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}guided-fix").performClick()

        onNodeWithTag("${TAG_HEALTH_FIX_AID_PREFIX}guided-fix-1").performClick()
        assertEquals("Bearer token", copiedValue)
        assertEquals(true, copiedSensitive)
        assertFalse(fixed)

        onNodeWithTag("${TAG_HEALTH_FIX_AID_PREFIX}guided-fix-2").performClick()
        assertTrue(actionRun)
        onNodeWithTag("${TAG_HEALTH_FIX_GUIDANCE_OK_PREFIX}guided-fix").assertIsDisplayed()
        assertFalse(fixed)

        onNodeWithTag("${TAG_HEALTH_FIX_GUIDANCE_OK_PREFIX}guided-fix").performClick()
        assertTrue(fixed)
    }

    /** 再チェックで項目リストの構成が変わっても、開いている案内ダイアログは維持される。 */
    @Test
    fun guidanceDialogSurvivesRecheckWithChangedItemList() = runComposeUiTest {
        val guidanceItem = HealthCheckItem(
            id = "guided-fix",
            label = "コピー補助つきの修復項目",
            state = HealthCheckState.FAILING,
            fixLabel = "登録し直す",
            onFix = {},
            fixGuidance = "ntfy 側の設定を変更してください。",
        )
        val checker = QueueHealthChecker(
            listOf(
                listOf(guidanceItem),
                listOf(
                    HealthCheckItem(id = "extra", label = "追加項目", state = HealthCheckState.FAILING, fixLabel = "直す", onFix = {}),
                    guidanceItem,
                ),
            ),
        )
        val key = mutableStateOf(0)
        setContent { HealthCheckScreen(checker = checker, externalRefreshKey = key.value) }

        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}guided-fix").performClick()
        onNodeWithTag("${TAG_HEALTH_FIX_GUIDANCE_OK_PREFIX}guided-fix").assertIsDisplayed()

        runOnIdle { key.value = 1 }
        onNodeWithTag("${TAG_HEALTH_FIX_GUIDANCE_OK_PREFIX}guided-fix").assertIsDisplayed()
    }

    /** onFix が成功すると、項目に実行済みの案内文を出す。 */
    @Test
    fun successfulFixShowsDoneNotice() = runComposeUiTest {
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(
                        id = "unifiedpush",
                        label = "UnifiedPush の登録",
                        state = HealthCheckState.FAILING,
                        fixLabel = "登録する",
                        onFix = {},
                    ),
                ),
            )
        }
        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}unifiedpush").performClick()
        onNodeWithTag("${TAG_HEALTH_FIX_PENDING_PREFIX}unifiedpush").assertIsDisplayed()
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

    /** INFO 状態の項目でも fixLabel と onFix が揃っていれば操作ボタンを表示し、押下で onFix が呼ばれる。 */
    @Test
    fun infoItemWithFixShowsFixButtonAndInvokesOnFix() = runComposeUiTest {
        var fixed = false
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(
                        id = "up-self-test",
                        label = "サーバ経由の受信テスト",
                        state = HealthCheckState.INFO,
                        fixLabel = "テスト実行",
                        onFix = { fixed = true },
                    ),
                ),
            )
        }
        onNodeWithTag("${TAG_HEALTH_FIX_PREFIX}up-self-test").performClick()
        assertTrue(fixed)
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

    /** 誘導リンクを持つ項目は「直す」ボタンを出さず、リンクを押すと onOpen が呼ばれる。 */
    @Test
    fun linkItemShowsLinkAndInvokesOnOpen() = runComposeUiTest {
        var opened = false
        setContent {
            HealthCheckScreen(
                checker = checker(
                    HealthCheckItem(
                        id = "unifiedpush",
                        label = "3. UnifiedPush の登録",
                        state = HealthCheckState.FAILING,
                        link = HealthCheckLink(label = "セットアップを開く", onOpen = { opened = true }),
                    ),
                ),
            )
        }
        onAllNodesWithTag("${TAG_HEALTH_FIX_PREFIX}unifiedpush").assertCountEquals(0)
        onNodeWithTag("${TAG_HEALTH_LINK_PREFIX}unifiedpush").performClick()
        assertTrue(opened)
    }

    /** 誘導リンクの押下は実行済み案内も自動再チェックも起こさない（onFix と挙動が異なる）。 */
    @Test
    fun linkTapDoesNotShowDoneOrRecheck() = runComposeUiTest {
        val checker = checker(
            HealthCheckItem(
                id = "up-self-test",
                label = "5. 受信テスト",
                state = HealthCheckState.INFO,
                link = HealthCheckLink(label = "セットアップを開く", onOpen = {}),
            ),
        )
        setContent { HealthCheckScreen(checker = checker) }

        onNodeWithTag("${TAG_HEALTH_LINK_PREFIX}up-self-test").performClick()
        onAllNodesWithTag("${TAG_HEALTH_FIX_PENDING_PREFIX}up-self-test").assertCountEquals(0)
        assertEquals(1, checker.calls)
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
