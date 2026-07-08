package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineScreenTest {

    private fun notification(
        actions: List<String> = listOf("アーカイブ", "返信"),
        key: String = "0|com.example|1|null|10",
        from: String = "phone",
        packageName: String = "com.example",
    ) = NotificationPayload(
        id = "n1",
        from = from,
        to = "*",
        sentAtEpochMillis = 1000L,
        packageName = packageName,
        appName = "Example",
        title = "Verification",
        text = "code 123456",
        notificationKey = key,
        actions = actions,
        postedAtEpochMillis = 1000L,
    )

    private fun items(payload: NotificationPayload = notification()): MutableStateFlow<List<TimelineItem>> =
        MutableStateFlow(listOf(ReceivedNotification(id = payload.id, timestampEpochMillis = 1000L, payload = payload)))

    /** インラインのアクションボタンは、対応する index の invokeAction を送信元へ返送する。 */
    @Test
    fun inlineActionButtonSendsInvokeActionWithIndex() = runComposeUiTest {
        var invoked: Triple<String, String, Int>? = null
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(invokeAction = { p, i -> invoked = Triple(p.from, p.notificationKey, i) }),
            )
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}1").performClick()
        assertEquals(Triple("phone", "0|com.example|1|null|10", 1), invoked)
    }

    /** 左スワイプで dismiss を発火し、往復を待たず自分の表示から取り下げる。 */
    @Test
    fun swipeDismissesAndHidesLocally() = runComposeUiTest {
        var dismissedId: String? = null
        setContent {
            TimelineScreen(items(), actions = TimelineActions(dismiss = { dismissedId = it.payload.id }))
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performTouchInput { swipeLeft() }
        assertEquals("n1", dismissedId)
        onAllNodesWithTag(TAG_TIMELINE_RECEIVED).assertCountEquals(0)
    }

    /** 長押しで開くコンテキストメニューの「消す」は dismiss を送り、表示からも消す。 */
    @Test
    fun contextMenuDismissSendsDismissAndHides() = runComposeUiTest {
        var dismissedKey: String? = null
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(dismiss = { dismissedKey = (it.payload as NotificationPayload).notificationKey }),
            )
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onNodeWithTag(TAG_TIMELINE_MENU_DISMISS).performClick()
        assertEquals("0|com.example|1|null|10", dismissedKey)
        onAllNodesWithTag(TAG_TIMELINE_RECEIVED).assertCountEquals(0)
    }

    /** コンテキストメニューの「このアプリからの通知を非表示」は muteApp を送信元へ返送する。 */
    @Test
    fun contextMenuMuteSendsMuteApp() = runComposeUiTest {
        var muted: Pair<String, String>? = null
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(muteApp = { muted = it.from to it.packageName }),
            )
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onNodeWithTag(TAG_TIMELINE_MENU_MUTE).performClick()
        assertEquals("phone" to "com.example", muted)
    }

    /** コンテキストメニューのアクション項目も invokeAction を返送する。 */
    @Test
    fun contextMenuActionSendsInvokeAction() = runComposeUiTest {
        var invokedIndex: Int? = null
        setContent {
            TimelineScreen(items(), actions = TimelineActions(invokeAction = { _, i -> invokedIndex = i }))
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onNodeWithTag("${TAG_TIMELINE_MENU_ACTION_PREFIX}0").performClick()
        assertEquals(0, invokedIndex)
    }

    /** actions を渡さない画面（送信ロール・空状態）では操作アフォーダンスを出さない。 */
    @Test
    fun withoutActionsNoInteractiveAffordances() = runComposeUiTest {
        setContent { TimelineScreen(items()) }
        onAllNodesWithTag(TAG_TIMELINE_RECEIVED).assertCountEquals(0)
        onAllNodesWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").assertCountEquals(0)
    }

    /** アクションが無い通知はボタンを出さないが、コンテキストメニューは開ける。 */
    @Test
    fun notificationWithoutActionsShowsNoActionButtons() = runComposeUiTest {
        setContent { TimelineScreen(items(notification(actions = emptyList())), actions = TimelineActions()) }
        onAllNodesWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").assertCountEquals(0)
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onAllNodesWithTag(TAG_TIMELINE_MENU_DISMISS).assertCountEquals(1)
    }
}
