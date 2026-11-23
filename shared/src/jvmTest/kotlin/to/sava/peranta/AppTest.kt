package to.sava.peranta

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.DEFAULT_EMPTY_TIMELINE_MESSAGE
import to.sava.peranta.ui.TAG_TIMELINE_DISMISS_ALL
import to.sava.peranta.ui.TAG_TIMELINE_DISMISS_ALL_CONFIRM
import to.sava.peranta.ui.TimelineActions
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppTest {

    private fun items(): MutableStateFlow<List<TimelineItem>> =
        MutableStateFlow(
            listOf(
                ReceivedNotification(
                    id = "n1",
                    timestampEpochMillis = 1000L,
                    payload = NotificationPayload(
                        id = "n1",
                        from = "phone",
                        to = "*",
                        sentAtEpochMillis = 1000L,
                        packageName = "com.example",
                        appName = "Example",
                        title = "Verification",
                        text = "code 123456",
                        notificationKey = "0|com.example|1|null|10",
                        actions = emptyList(),
                        postedAtEpochMillis = 1000L,
                    ),
                ),
            ),
        )

    /** タイムライン UI 用のスロット（操作・添付）を持たない状態でも、受信済みアイテムは表示される。 */
    @Test
    fun showsTimelineItemsWithoutReceiveSlots() = runComposeUiTest {
        setContent { App(items = items()) }
        onAllNodesWithText("Verification").assertCountEquals(1)
        onAllNodesWithText("code 123456").assertCountEquals(1)
    }

    /** 受信可能な端末で空のときは既定の空状態文言を出す。 */
    @Test
    fun emptyTimelineShowsDefaultMessage() = runComposeUiTest {
        setContent { App(items = MutableStateFlow(emptyList())) }
        onAllNodesWithText(DEFAULT_EMPTY_TIMELINE_MESSAGE).assertCountEquals(1)
    }

    /** 受信設定が未完の端末では、空状態に設定完了を促す差し替え文言を出す。 */
    @Test
    fun emptyTimelineShowsProvidedMessageWhenNotReady() = runComposeUiTest {
        val notReady = "受信の設定が完了すると通知が表示されます"
        setContent { App(items = MutableStateFlow(emptyList()), emptyStateMessage = notReady) }
        onAllNodesWithText(notReady).assertCountEquals(1)
        onAllNodesWithText(DEFAULT_EMPTY_TIMELINE_MESSAGE).assertCountEquals(0)
    }

    /** confirmDismissAll = false をタイムラインへ渡し、まとめて消すは確認を経ずに対象を渡す（§10.1）。 */
    @Test
    fun passesConfirmDismissAllToTimeline() = runComposeUiTest {
        var dismissedAll: List<String>? = null
        setContent {
            App(
                items = items(),
                timelineActions = TimelineActions(dismissAll = { list -> dismissedAll = list.map { it.id } }),
                confirmDismissAll = false,
            )
        }
        onNodeWithTag(TAG_TIMELINE_DISMISS_ALL).performClick()
        assertEquals(listOf("n1"), dismissedAll)
        onAllNodesWithTag(TAG_TIMELINE_DISMISS_ALL_CONFIRM).assertCountEquals(0)
    }
}
