package to.sava.peranta

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.DEFAULT_EMPTY_TIMELINE_MESSAGE
import kotlin.test.Test
import kotlin.test.assertTrue

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

    /** ツールバーの各導線は全端末共通で出る。設定ボタンを押すと onOpenSettings が呼ばれる。 */
    @Test
    fun toolbarActionsAreAvailable() = runComposeUiTest {
        var openedSettings = false
        setContent {
            App(items = items(), onOpenSettings = { openedSettings = true })
        }
        onAllNodesWithText("設定").assertCountEquals(1)
        onNodeWithText("設定").performClick()
        assertTrue(openedSettings)
    }
}
