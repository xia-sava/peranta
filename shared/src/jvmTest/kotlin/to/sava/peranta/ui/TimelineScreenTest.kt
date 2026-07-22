package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.SentNotification
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

    private fun attachmentRef(fileName: String = "photo.jpg", sizeBytes: Long = 2048) = AttachmentRef(
        blobId = "blob-1",
        url = "https://peranta.sava.to/file/abc",
        fileName = fileName,
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    /** 送信 FilePayload バブルは受信側と同じ表示（ファイル名+サイズ+キャプション）を再利用する（§3.3）。 */
    @Test
    fun sentFilePayloadShowsFileNameSizeAndCaption() = runComposeUiTest {
        val ref = attachmentRef(fileName = "report.pdf", sizeBytes = 4096)
        val payload = FilePayload(
            id = "f1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 1000L,
            caption = "会議資料です",
            attachments = listOf(ref),
            postedAtEpochMillis = 1000L,
        )
        setContent {
            TimelineScreen(
                MutableStateFlow(listOf(SentNotification(id = "f1", timestampEpochMillis = 1000L, payload = payload))),
            )
        }
        onNodeWithText("会議資料です").assertExists()
        onNodeWithText("report.pdf (${formatFileSize(4096)})").assertExists()
    }

    /** fromName が設定されていれば、時刻行にその端末名を表示する（§3.2）。 */
    @Test
    fun speakerRowShowsFromNameWhenPresent() = runComposeUiTest {
        val payload = notification().copy(fromName = "xia-phone")
        setContent {
            TimelineScreen(MutableStateFlow(listOf(ReceivedNotification(id = "n1", timestampEpochMillis = 1000L, payload = payload))))
        }
        onNodeWithText("xia-phone・${formatTimeOfDay(1000L)}").assertExists()
    }

    /** fromName が無ければ from（deviceId）を時刻行に表示する（旧バージョン発のアイテム互換）。 */
    @Test
    fun speakerRowFallsBackToDeviceIdWhenFromNameAbsent() = runComposeUiTest {
        setContent {
            TimelineScreen(items(notification(from = "phone")))
        }
        onNodeWithText("phone・${formatTimeOfDay(1000L)}").assertExists()
    }

    /** ErrorItem は payload を持たないため、時刻行に発言者名を出さず時刻のみ表示する（§3.2）。 */
    @Test
    fun errorItemShowsTimeOnly() = runComposeUiTest {
        setContent {
            TimelineScreen(
                MutableStateFlow(
                    listOf(ErrorItem(id = "e1", timestampEpochMillis = 1000L, message = "送信に失敗しました", kind = ErrorKind.OTHER)),
                ),
            )
        }
        onNodeWithText(formatTimeOfDay(1000L)).assertExists()
    }
}
