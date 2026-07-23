package to.sava.peranta.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SemanticActionKind
import to.sava.peranta.send.MAX_REPLY_TEXT_BYTES
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedMessage
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
        actionDetails: List<NotificationActionDetail> = emptyList(),
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
        actionDetails = actionDetails,
        postedAtEpochMillis = 1000L,
    )

    private fun items(payload: NotificationPayload = notification()): MutableStateFlow<List<TimelineItem>> =
        MutableStateFlow(listOf(ReceivedNotification(id = payload.id, timestampEpochMillis = 1000L, payload = payload)))

    /** 並び順テスト用の通知アイテム。id/本文/時刻は index で一意にし、追記順（古い順）を再現する。 */
    private fun timelineNotification(index: Int) = NotificationPayload(
        id = "n$index",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1000L + index,
        packageName = "com.example",
        appName = "Example",
        title = "Verification",
        text = "item-$index",
        notificationKey = "k$index",
        actions = emptyList(),
        postedAtEpochMillis = 1000L + index,
    )

    private fun receivedItem(index: Int): TimelineItem {
        val payload = timelineNotification(index)
        return ReceivedNotification(id = payload.id, timestampEpochMillis = payload.postedAtEpochMillis, payload = payload)
    }

    /** 追記順（古い順）で [count] 件のタイムラインアイテム列を作る。 */
    private fun chronologicalItems(count: Int): List<TimelineItem> = (1..count).map { receivedItem(it) }

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

    /** opensActivity=true に分類されるアクションのボタンラベルには「（スマホで）」が付記される。 */
    @Test
    fun opensOnSenderActionButtonShowsSuffix() = runComposeUiTest {
        val payload = notification(
            actions = listOf("地図"),
            actionDetails = listOf(NotificationActionDetail(opensActivity = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions())
        }
        onNodeWithText("地図${ACTION_OPENS_ON_SENDER_SUFFIX}").assertExists()
    }

    /** SENDER_EFFECT・UNKNOWN に分類されるアクションは従来どおりのラベルのまま表示される。 */
    @Test
    fun senderEffectAndUnknownActionButtonsShowUnchangedLabel() = runComposeUiTest {
        val payload = notification(
            actions = listOf("アーカイブ", "開く"),
            actionDetails = listOf(
                NotificationActionDetail(opensActivity = false),
                NotificationActionDetail(),
            ),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions())
        }
        onNodeWithText("アーカイブ").assertExists()
        onNodeWithText("開く").assertExists()
    }

    /** 付記付きボタンを押しても、従来どおり invokeAction が対応する index で送信される。 */
    @Test
    fun opensOnSenderActionButtonStillSendsInvokeAction() = runComposeUiTest {
        var invokedIndex: Int? = null
        val payload = notification(
            actions = listOf("地図"),
            actionDetails = listOf(NotificationActionDetail(opensActivity = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions(invokeAction = { _, i -> invokedIndex = i }))
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        assertEquals(0, invokedIndex)
    }

    /** REPLY 分類のアクションボタンを押すと、invokeAction を送らずインライン返信入力欄が開く。 */
    @Test
    fun replyActionButtonOpensInlineInputWithoutInvokingAction() = runComposeUiTest {
        var invoked = false
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions(invokeAction = { _, _ -> invoked = true }))
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).assertExists()
        assertTrue(!invoked)
    }

    /** 返信入力欄に本文を入れて送信すると reply(payload, index, text) が呼ばれ入力欄が閉じる。 */
    @Test
    fun replySendCallsReplyAndClosesInput() = runComposeUiTest {
        var replied: Triple<String, Int, String>? = null
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(semanticAction = SemanticActionKind.REPLY)),
        )
        setContent {
            TimelineScreen(
                items(payload),
                actions = TimelineActions(reply = { p, i, text -> replied = Triple(p.notificationKey, i, text) }),
            )
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).performTextInput("了解しました")
        onNodeWithTag(TAG_TIMELINE_REPLY_SEND).performClick()
        assertEquals(Triple("0|com.example|1|null|10", 0, "了解しました"), replied)
        onAllNodesWithTag(TAG_TIMELINE_REPLY_INPUT).assertCountEquals(0)
    }

    /** 返信入力欄が空白のみのときは送信ボタンが無効で、押しても reply は呼ばれない。 */
    @Test
    fun replyWithBlankTextDisablesSendButton() = runComposeUiTest {
        var replied = false
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions(reply = { _, _, _ -> replied = true }))
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).performTextInput("   ")
        onNodeWithTag(TAG_TIMELINE_REPLY_SEND).assertIsNotEnabled()
        assertTrue(!replied)
    }

    /** 返信入力欄の「キャンセル」を押すと reply を送らず入力欄が閉じる。 */
    @Test
    fun replyCancelClosesInputWithoutSending() = runComposeUiTest {
        var replied = false
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions(reply = { _, _, _ -> replied = true }))
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).performTextInput("下書き")
        onNodeWithText("キャンセル").performClick()
        onAllNodesWithTag(TAG_TIMELINE_REPLY_INPUT).assertCountEquals(0)
        assertTrue(!replied)
    }

    /** REPLY ボタンを再度押すと入力欄が閉じる（開閉のトグル）。 */
    @Test
    fun replyActionButtonPressedAgainClosesInput() = runComposeUiTest {
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions())
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).assertExists()
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        onAllNodesWithTag(TAG_TIMELINE_REPLY_INPUT).assertCountEquals(0)
    }

    /** 返信本文が上限バイト数を超えると、送信は無効化されず切り詰め警告のみ表示される。 */
    @Test
    fun replyOverLimitShowsTruncationWarning() = runComposeUiTest {
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions())
        }
        onNodeWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").performClick()
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).performTextInput("a".repeat(MAX_REPLY_TEXT_BYTES + 1))
        onNodeWithTag(TAG_TIMELINE_REPLY_LIMIT_WARNING, useUnmergedTree = true).assertExists()
    }

    /** コンテキストメニューの REPLY 分類の項目を押しても、invokeAction を送らず入力欄が開く。 */
    @Test
    fun contextMenuReplyActionOpensInlineInputWithoutInvokingAction() = runComposeUiTest {
        var invoked = false
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions(invokeAction = { _, _ -> invoked = true }))
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onNodeWithTag("${TAG_TIMELINE_MENU_ACTION_PREFIX}0").performClick()
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).assertExists()
        assertTrue(!invoked)
    }

    /** actionDetails を持たない payload（旧送信元由来）は全ボタンが従来どおりのラベルで表示される。 */
    @Test
    fun payloadWithoutActionDetailsShowsUnchangedLabels() = runComposeUiTest {
        val payload = notification(actions = listOf("アーカイブ", "地図"), actionDetails = emptyList())
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions())
        }
        onNodeWithText("アーカイブ").assertExists()
        onNodeWithText("地図").assertExists()
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

    private fun messagePayload(fromName: String? = "xia-phone") = MessagePayload(
        id = "m1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1000L,
        text = "会議は 15 時からです",
        fromName = fromName,
    )

    /** 受信メッセージは左バブルに本文と「{端末名}・{時刻}」を表示する（§3.4）。 */
    @Test
    fun receivedMessageShowsTextAndSpeakerTimeOnLeftBubble() = runComposeUiTest {
        setContent {
            TimelineScreen(
                MutableStateFlow(listOf(ReceivedMessage(id = "m1", timestampEpochMillis = 1000L, payload = messagePayload()))),
            )
        }
        onNodeWithText("会議は 15 時からです").assertExists()
        onNodeWithText("xia-phone・${formatTimeOfDay(1000L)}").assertExists()
    }

    /** URL を含むメッセージ本文はリンク化されても、テキスト全体はそのまま表示される。 */
    @Test
    fun messageWithUrlStillShowsFullText() = runComposeUiTest {
        val text = "資料は https://example.com/doc です"
        setContent {
            TimelineScreen(
                MutableStateFlow(listOf(ReceivedMessage(id = "m1", timestampEpochMillis = 1000L, payload = messagePayload().copy(text = text)))),
            )
        }
        onNodeWithText(text).assertExists()
    }

    /** 送信済みメッセージは右バブルで本文のみ表示し、生の deviceId ヘッダは出ない（§3.4）。 */
    @Test
    fun sentMessageShowsTextWithoutDeviceIdHeader() = runComposeUiTest {
        setContent {
            TimelineScreen(
                MutableStateFlow(listOf(SentNotification(id = "m1", timestampEpochMillis = 1000L, payload = messagePayload(fromName = null)))),
            )
        }
        onNodeWithText("会議は 15 時からです").assertExists()
        onNodeWithText("phone").assertDoesNotExist()
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

    /** 初期表示ではアニメーション無しで最下部へジャンプし、最新アイテムが見え最古アイテムは見えない（§10.1）。 */
    @Test
    fun initialDisplayShowsLatestItemAtBottom() = runComposeUiTest {
        val listState = LazyListState()
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        setContent {
            TimelineScreen(
                items = flow,
                listState = listState,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
            )
        }
        waitForIdle()

        onNodeWithText("item-$TEST_ITEM_COUNT").assertExists()
        onNodeWithText("item-1").assertDoesNotExist()
    }

    /** 最下部表示中に新着が追加されると自動で追従スクロールし、新着アイテムが可視になる（§10.1）。 */
    @Test
    fun followsNewestItemWhenAtBottom() = runComposeUiTest {
        val listState = LazyListState()
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        setContent {
            TimelineScreen(
                items = flow,
                listState = listState,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
            )
        }
        waitForIdle()

        flow.value = flow.value + receivedItem(TEST_ITEM_COUNT + 1)
        waitForIdle()

        onNodeWithText("item-${TEST_ITEM_COUNT + 1}").assertExists()
    }

    /**
     * 読み返し中（最下部から離れている）に新着が来ても表示位置が維持され、最下部へ勝手に戻らない（§10.1）。
     * 新着は末尾への追記のみで既存アイテムの並びは変わらないため、読み返し位置の先頭可視 index は
     * 新着追加の前後で変化しないはずである。
     */
    @Test
    fun preservesReadingPositionWhenNewItemArrivesWhileScrolledAway() = runComposeUiTest {
        val listState = LazyListState()
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        var scrollTarget by mutableStateOf<Int?>(null)
        setContent {
            LaunchedEffect(scrollTarget) {
                scrollTarget?.let { listState.scrollToItem(it) }
            }
            TimelineScreen(
                items = flow,
                listState = listState,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
            )
        }
        waitForIdle()
        scrollTarget = READING_POSITION_INDEX
        waitForIdle()
        val indexBeforeNewItem = listState.firstVisibleItemIndex

        flow.value = flow.value + receivedItem(TEST_ITEM_COUNT + 1)
        waitForIdle()

        assertEquals(indexBeforeNewItem, listState.firstVisibleItemIndex)
        onNodeWithText("item-${TEST_ITEM_COUNT + 1}").assertDoesNotExist()
    }

    /**
     * 最新アイテムが画面内に見えていても、末尾までスクロールしきっていなければ新着に追従しない
     * （追従の基準は「下方向にこれ以上スクロールできない」こと。§10.1）。
     */
    @Test
    fun doesNotFollowWhenSlightlyScrolledUpEvenIfLatestVisible() = runComposeUiTest {
        val listState = LazyListState()
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        var scrollUp by mutableStateOf(false)
        setContent {
            LaunchedEffect(scrollUp) {
                if (scrollUp) listState.scrollBy(-20f)
            }
            TimelineScreen(
                items = flow,
                listState = listState,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
            )
        }
        waitForIdle()
        scrollUp = true
        waitForIdle()
        assertTrue(listState.canScrollForward)
        onNodeWithText("item-$TEST_ITEM_COUNT").assertExists()
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset

        flow.value = flow.value + receivedItem(TEST_ITEM_COUNT + 1)
        waitForIdle()

        assertEquals(indexBefore, listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
    }

    /** スクロールバースロットには LazyColumn と同一の listState が渡され、注入した内容が描画される。 */
    @Test
    fun lazyScrollbarContentSlotIsInvokedWithListState() = runComposeUiTest {
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        var receivedListState: LazyListState? = null
        setContent {
            TimelineScreen(
                items = flow,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
                lazyScrollbarContent = { listState ->
                    receivedListState = listState
                    Text(text = "scrollbar", modifier = Modifier.testTag(SCROLLBAR_SLOT_TAG))
                },
            )
        }
        onNodeWithTag(SCROLLBAR_SLOT_TAG).assertIsDisplayed()
        assertTrue(receivedListState != null)
    }

    private companion object {
        /** スクロール確認用のアイテム件数。画面高に対して十分にオーバーフローする件数にする。 */
        const val TEST_ITEM_COUNT = 30
        const val READING_POSITION_INDEX = 10
        val LIST_TEST_WIDTH = 300.dp
        val LIST_TEST_HEIGHT = 200.dp
        const val SCROLLBAR_SLOT_TAG = "scrollbar-slot"
    }
}
