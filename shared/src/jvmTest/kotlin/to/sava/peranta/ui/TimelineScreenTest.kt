package to.sava.peranta.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationActionDetail
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SemanticActionKind
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.model.MAX_REPLY_TEXT_BYTES
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineScreenTest {

    /** 画面と同じ規則で整形した時刻表記。テストのアイテムは当日ではないので日付つきになる。 */
    private fun stamp(epochMillis: Long): String = formatTimestamp(epochMillis, nowEpochMillis())

    private fun notification(
        actions: List<String> = listOf("アーカイブ", "返信"),
        actionDetails: List<NotificationActionDetail> = emptyList(),
        key: String = "0|com.example|1|null|10",
        from: String = "phone",
        packageName: String = "com.example",
        text: String = "code 123456",
    ) = NotificationPayload(
        id = "n1",
        from = from,
        to = "*",
        sentAtEpochMillis = 1000L,
        packageName = packageName,
        appName = "Example",
        title = "Verification",
        text = text,
        notificationKey = key,
        actions = actions,
        actionDetails = actionDetails,
        postedAtEpochMillis = 1000L,
    )

    private fun items(
        payload: NotificationPayload = notification(),
        sourceDismissed: Boolean = false,
        hiddenFromTimeline: Boolean = false,
    ): MutableStateFlow<List<TimelineItem>> =
        MutableStateFlow(
            listOf(
                ReceivedNotification(
                    id = payload.id,
                    timestampEpochMillis = 1000L,
                    payload = payload,
                    sourceDismissed = sourceDismissed,
                    hiddenFromTimeline = hiddenFromTimeline,
                ),
            ),
        )

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

    /**
     * 本文が長い通知でも状態兼ボタンは押し出されず表示される（§10.1）。
     * 本文に幅を取り切らせるとボタンの幅が残らず、画面から消える不具合の回帰。
     */
    @Test
    fun sourceStateButtonStaysVisibleWithLongBody() = runComposeUiTest {
        val longText = "長い本文です。".repeat(60)
        setContent {
            TimelineScreen(items(notification(text = longText)), actions = TimelineActions())
        }
        onNodeWithTag(TAG_TIMELINE_SOURCE_ALIVE_BUTTON).assertIsDisplayed()
    }

    /** 元通知が生きているアイテムの ✓ ボタンは dismiss だけを送り、タイムラインには残す。 */
    @Test
    fun sourceAliveButtonDismissesSourceOnly() = runComposeUiTest {
        var dismissedId: String? = null
        var hiddenId: String? = null
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(
                    dismiss = { dismissedId = it.payload.id },
                    hideFromTimeline = { hiddenId = it.id },
                ),
            )
        }
        onNodeWithTag(TAG_TIMELINE_SOURCE_ALIVE_BUTTON).performClick()
        assertEquals("n1", dismissedId)
        assertNull(hiddenId)
    }

    /** コンテキストメニューの「送信元の通知を消す」も dismiss だけを送る。 */
    @Test
    fun contextMenuDismissSendsDismissOnly() = runComposeUiTest {
        var dismissedKey: String? = null
        var hiddenId: String? = null
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(
                    dismiss = { dismissedKey = (it.payload as NotificationPayload).notificationKey },
                    hideFromTimeline = { hiddenId = it.id },
                ),
            )
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onNodeWithTag(TAG_TIMELINE_MENU_DISMISS).performClick()
        assertEquals("0|com.example|1|null|10", dismissedKey)
        assertNull(hiddenId)
    }

    /** コンテキストメニューの「タイムラインから消す」は command を送らず、この端末からだけ消す。 */
    @Test
    fun contextMenuHideRemovesFromTimelineOnly() = runComposeUiTest {
        var dismissedId: String? = null
        var hiddenId: String? = null
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(
                    dismiss = { dismissedId = it.payload.id },
                    hideFromTimeline = { hiddenId = it.id },
                ),
            )
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onNodeWithTag(TAG_TIMELINE_MENU_HIDE).performClick()
        assertNull(dismissedId)
        assertEquals("n1", hiddenId)
    }

    /** まとめて消すバーは確認を挟んでから、対象を一括で渡す（§10.1）。 */
    @Test
    fun dismissAllPassesTargetsAfterConfirmation() = runComposeUiTest {
        var dismissedAll: List<String>? = null
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(dismissAll = { list -> dismissedAll = list.map { it.id } }),
            )
        }
        onNodeWithTag(TAG_TIMELINE_DISMISS_ALL).performClick()
        assertNull(dismissedAll)
        onNodeWithTag(TAG_TIMELINE_DISMISS_ALL_CONFIRM).performClick()
        assertEquals(listOf("n1"), dismissedAll)
    }

    /** 元通知が消えたアイテムしか無ければ、まとめて消すバーを出さない。 */
    @Test
    fun dismissAllBarHiddenWhenNoLiveSources() = runComposeUiTest {
        setContent {
            TimelineScreen(items(sourceDismissed = true), actions = TimelineActions())
        }
        onAllNodesWithTag(TAG_TIMELINE_DISMISS_ALL).assertCountEquals(0)
    }

    /** タイムラインから消したアイテムは表示に出ない（§10.1）。 */
    @Test
    fun hiddenFromTimelineItemIsNotShown() = runComposeUiTest {
        setContent {
            TimelineScreen(items(hiddenFromTimeline = true), actions = TimelineActions())
        }
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

    /** opensActivity=true に分類されるアクションには、実行先の接頭辞に加えて画面が開く旨が付記される。 */
    @Test
    fun opensOnSenderActionButtonShowsSuffix() = runComposeUiTest {
        val payload = notification(
            actions = listOf("地図"),
            actionDetails = listOf(NotificationActionDetail(opensActivity = true)),
        )
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions())
        }
        onNodeWithText("${actionRunsOnSenderPrefix("phone")}地図$ACTION_OPENS_ON_SENDER_SUFFIX").assertExists()
    }

    /**
     * SENDER_EFFECT・UNKNOWN に分類されるアクションにも実行先の接頭辞が付く。添付を操作する
     * 同名のボタンと取り違えないようにするため、画面が開くかどうかに依らず区別する。
     */
    @Test
    fun senderEffectAndUnknownActionButtonsShowExecutionPrefix() = runComposeUiTest {
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
        onNodeWithText("${actionRunsOnSenderPrefix("phone")}アーカイブ").assertExists()
        onNodeWithText("${actionRunsOnSenderPrefix("phone")}開く").assertExists()
    }

    /**
     * アクションのラベルは実行先を送信元の端末名で名指す（§10.1）。
     * 転送元はスマホとは限らず、どの端末も送信側になれるため固定の呼び名を使わない。
     */
    @Test
    fun actionLabelNamesSenderDevice() = runComposeUiTest {
        setContent {
            TimelineScreen(items(notification().copy(fromName = "タブレット")), actions = TimelineActions())
        }
        onNodeWithText("タブレット: アーカイブ").assertExists()
    }

    /** 端末名を運ばないペイロードは deviceId で名指す（発信元表示と同じ規則）。 */
    @Test
    fun actionLabelFallsBackToDeviceId() = runComposeUiTest {
        setContent {
            TimelineScreen(items(notification()), actions = TimelineActions())
        }
        onNodeWithText("phone: アーカイブ").assertExists()
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

    /** トーストから届いた返信要求は、対象アイテムの入力欄を開いて消費を通知する（§3.3）。 */
    @Test
    fun replyRequestOpensInlineInput() = runComposeUiTest {
        var handled = false
        val payload = notification(
            actions = listOf("アーカイブ", "返信"),
            actionDetails = listOf(
                NotificationActionDetail(opensActivity = false),
                NotificationActionDetail(hasRemoteInput = true),
            ),
        )
        setContent {
            TimelineScreen(
                items(payload),
                actions = TimelineActions(),
                replyRequest = TimelineReplyRequest(itemId = payload.id, actionIndex = 1),
                onReplyRequestHandled = { handled = true },
            )
        }
        onNodeWithTag(TAG_TIMELINE_REPLY_INPUT).assertExists()
        assertTrue(handled)
    }

    /** 元通知が消えたアイテム宛の返信要求は、入力欄を開かずに消費だけする（操作 UI を出さないため）。 */
    @Test
    fun replyRequestOnDismissedSourceOnlyConsumes() = runComposeUiTest {
        var handled = false
        val payload = notification(
            actions = listOf("返信"),
            actionDetails = listOf(NotificationActionDetail(hasRemoteInput = true)),
        )
        setContent {
            TimelineScreen(
                items(payload, sourceDismissed = true),
                actions = TimelineActions(),
                replyRequest = TimelineReplyRequest(itemId = payload.id, actionIndex = 0),
                onReplyRequestHandled = { handled = true },
            )
        }
        onAllNodesWithTag(TAG_TIMELINE_REPLY_INPUT).assertCountEquals(0)
        assertTrue(handled)
    }

    /** 表示リストに無いアイテム宛の返信要求は、受け取り手が居ないので画面側で消費する。 */
    @Test
    fun replyRequestForMissingItemIsConsumed() = runComposeUiTest {
        var handled = false
        setContent {
            TimelineScreen(
                items(),
                actions = TimelineActions(),
                replyRequest = TimelineReplyRequest(itemId = "no-such-item", actionIndex = 0),
                onReplyRequestHandled = { handled = true },
            )
        }
        onAllNodesWithTag(TAG_TIMELINE_REPLY_INPUT).assertCountEquals(0)
        assertTrue(handled)
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

    /**
     * actionDetails を持たない payload（旧送信元由来）でも実行先の接頭辞は付く。
     * 画面が開くかどうかは判定できないため、そちらの注記だけが落ちる。
     */
    @Test
    fun payloadWithoutActionDetailsStillShowsExecutionPrefix() = runComposeUiTest {
        val payload = notification(actions = listOf("アーカイブ", "地図"), actionDetails = emptyList())
        setContent {
            TimelineScreen(items(payload), actions = TimelineActions())
        }
        onNodeWithText("${actionRunsOnSenderPrefix("phone")}アーカイブ").assertExists()
        onNodeWithText("${actionRunsOnSenderPrefix("phone")}地図").assertExists()
    }

    /** actions を渡さない画面（送信ロール・空状態）では操作アフォーダンスを出さない。 */
    @Test
    fun withoutActionsNoInteractiveAffordances() = runComposeUiTest {
        setContent { TimelineScreen(items()) }
        onAllNodesWithTag(TAG_TIMELINE_RECEIVED).assertCountEquals(0)
        onAllNodesWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").assertCountEquals(0)
        onAllNodesWithTag(TAG_TIMELINE_SOURCE_ALIVE_BUTTON).assertCountEquals(0)
    }

    /** アクションが無い通知はボタンを出さないが、コンテキストメニューは開ける。 */
    @Test
    fun notificationWithoutActionsShowsNoActionButtons() = runComposeUiTest {
        setContent { TimelineScreen(items(notification(actions = emptyList())), actions = TimelineActions()) }
        onAllNodesWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").assertCountEquals(0)
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onAllNodesWithTag(TAG_TIMELINE_MENU_DISMISS).assertCountEquals(1)
    }

    /** sourceDismissed のアイテムはアクションボタンを出さない（§10.1）。 */
    @Test
    fun sourceDismissedItemHidesActionButtons() = runComposeUiTest {
        setContent {
            TimelineScreen(items(sourceDismissed = true), actions = TimelineActions())
        }
        onAllNodesWithTag("${TAG_TIMELINE_ACTION_PREFIX}0").assertCountEquals(0)
        onAllNodesWithTag("${TAG_TIMELINE_ACTION_PREFIX}1").assertCountEquals(0)
    }

    /** 元通知の状態は右上のボタンの図柄で示す。生きていればチェック、消えていれば罰点（§10.1）。 */
    @Test
    fun sourceStateIsShownByButtonGlyph() = runComposeUiTest {
        setContent {
            TimelineScreen(items(), actions = TimelineActions())
        }
        onNodeWithTag(TAG_TIMELINE_SOURCE_ALIVE_BUTTON).assertContentDescriptionEquals(SOURCE_ALIVE_DESCRIPTION)
        onNodeWithTag(TAG_TIMELINE_SOURCE_ALIVE_BUTTON).assertIsDisplayed()
        onAllNodesWithTag(TAG_TIMELINE_SOURCE_DISMISSED_BUTTON).assertCountEquals(0)
    }

    /** sourceDismissed のアイテムは罰点を出し、チェックは出さない。 */
    @Test
    fun sourceDismissedItemShowsDismissedGlyph() = runComposeUiTest {
        setContent {
            TimelineScreen(items(sourceDismissed = true), actions = TimelineActions())
        }
        onNodeWithTag(TAG_TIMELINE_SOURCE_DISMISSED_BUTTON).assertContentDescriptionEquals(SOURCE_DISMISSED_DESCRIPTION)
        onNodeWithTag(TAG_TIMELINE_SOURCE_DISMISSED_BUTTON).assertIsDisplayed()
        onAllNodesWithTag(TAG_TIMELINE_SOURCE_ALIVE_BUTTON).assertCountEquals(0)
    }

    /**
     * sourceDismissed のアイテムはコンテキストメニューのアクション項目と「送信元の通知を消す」を
     * 出さないが、「タイムラインから消す」「このアプリからの通知を非表示」は引き続き出す。
     */
    @Test
    fun sourceDismissedItemKeepsOnlyLocalContextMenuItems() = runComposeUiTest {
        setContent {
            TimelineScreen(items(sourceDismissed = true), actions = TimelineActions())
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onAllNodesWithTag("${TAG_TIMELINE_MENU_ACTION_PREFIX}0").assertCountEquals(0)
        onAllNodesWithTag(TAG_TIMELINE_MENU_DISMISS).assertCountEquals(0)
        onAllNodesWithTag(TAG_TIMELINE_MENU_HIDE).assertCountEquals(1)
        onAllNodesWithTag(TAG_TIMELINE_MENU_MUTE).assertCountEquals(1)
    }

    /** sourceDismissed のアイテムの × ボタンは、command を送らずこの端末のタイムラインから消す。 */
    @Test
    fun sourceDismissedButtonHidesFromTimelineOnly() = runComposeUiTest {
        var dismissedId: String? = null
        var hiddenId: String? = null
        setContent {
            TimelineScreen(
                items(sourceDismissed = true),
                actions = TimelineActions(
                    dismiss = { dismissedId = it.payload.id },
                    hideFromTimeline = { hiddenId = it.id },
                ),
            )
        }
        onNodeWithTag(TAG_TIMELINE_SOURCE_DISMISSED_BUTTON).performClick()
        assertNull(dismissedId)
        assertEquals("n1", hiddenId)
    }

    private fun attachmentRef(fileName: String = "photo.jpg", sizeBytes: Long = 2048) = AttachmentRef(
        blobId = "blob-1",
        url = "https://peranta.example.com/file/abc",
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
        onNodeWithText("xia-phone・${stamp(1000L)}").assertExists()
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
        onNodeWithText("xia-phone・${stamp(1000L)}").assertExists()
    }

    /** fromName が無ければ from（deviceId）を時刻行に表示する（旧バージョン発のアイテム互換）。 */
    @Test
    fun speakerRowFallsBackToDeviceIdWhenFromNameAbsent() = runComposeUiTest {
        setContent {
            TimelineScreen(items(notification(from = "phone")))
        }
        onNodeWithText("phone・${stamp(1000L)}").assertExists()
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
        onNodeWithText(stamp(1000L)).assertExists()
    }

    /** 日をまたいだアイテムの時刻行には日付を添える（過去の記録が今のことに見えないように）。 */
    @Test
    fun timeRowShowsDateForItemsFromAnotherDay() = runComposeUiTest {
        setContent {
            TimelineScreen(
                MutableStateFlow(
                    listOf(ErrorItem(id = "e1", timestampEpochMillis = 1000L, message = "送信に失敗しました", kind = ErrorKind.OTHER)),
                ),
            )
        }
        onNodeWithText(formatTimestamp(1000L, 1000L)).assertDoesNotExist()
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
     * 最下部表示中に新着が短時間に連続で来て、前の新着への追従アニメーションが終わらないうちに
     * 次の新着が続いても、最終的に最新アイテムまで追従できる（§10.1）。
     */
    @Test
    fun followsLatestItemWhenNewItemsArriveInRapidSuccession() = runComposeUiTest {
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

        mainClock.autoAdvance = false
        repeat(RAPID_ARRIVAL_COUNT) { offset ->
            flow.value = flow.value + receivedItem(TEST_ITEM_COUNT + 1 + offset)
            mainClock.advanceTimeByFrame()
        }
        mainClock.autoAdvance = true
        waitForIdle()

        onNodeWithText("item-${TEST_ITEM_COUNT + RAPID_ARRIVAL_COUNT}").assertExists()
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

    /** scrollToItemId を渡すと、初期表示位置（最下部）から離れた対象アイテムまでスクロールする。 */
    @Test
    fun scrollToItemIdScrollsToTargetItem() = runComposeUiTest {
        val listState = LazyListState()
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        setContent {
            TimelineScreen(
                items = flow,
                listState = listState,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
                scrollToItemId = "n1",
            )
        }
        waitForIdle()

        onNodeWithText("item-1").assertExists()
    }

    /** スクロール消費後は onScrollToItemHandled が呼ばれ、対象アイテム id が消費されたことを通知する。 */
    @Test
    fun scrollToItemIdInvokesHandledCallbackAfterConsuming() = runComposeUiTest {
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        var handledCount = 0
        setContent {
            TimelineScreen(
                items = flow,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
                scrollToItemId = "n1",
                onScrollToItemHandled = { handledCount++ },
            )
        }
        waitForIdle()

        assertEquals(1, handledCount)
    }

    /** 対象アイテムが表示リストに無いとき（剪定済み等）はスクロールせず、消費のみ通知する。 */
    @Test
    fun scrollToItemIdNotFoundKeepsBottomPositionButStillHandled() = runComposeUiTest {
        val listState = LazyListState()
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        var handled = false
        setContent {
            TimelineScreen(
                items = flow,
                listState = listState,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
                scrollToItemId = "not-in-timeline",
                onScrollToItemHandled = { handled = true },
            )
        }
        waitForIdle()

        assertTrue(handled)
        onNodeWithText("item-$TEST_ITEM_COUNT").assertExists()
    }

    /**
     * scrollToItemId による一回きりのジャンプで最下部から離れた直後に新着が来ても、表示位置は
     * 動かず追従しない（§10.1）。
     */
    @Test
    fun doesNotFollowAfterScrollToItemIdJumpEvenWhenNewItemArrives() = runComposeUiTest {
        val listState = LazyListState()
        val flow = MutableStateFlow(chronologicalItems(TEST_ITEM_COUNT))
        var scrollToItemId by mutableStateOf<String?>(null)
        setContent {
            TimelineScreen(
                items = flow,
                listState = listState,
                modifier = Modifier.size(width = LIST_TEST_WIDTH, height = LIST_TEST_HEIGHT),
                scrollToItemId = scrollToItemId,
                onScrollToItemHandled = { scrollToItemId = null },
            )
        }
        waitForIdle()

        scrollToItemId = "n1"
        waitForIdle()
        onNodeWithText("item-1").assertExists()

        flow.value = flow.value + receivedItem(TEST_ITEM_COUNT + 1)
        waitForIdle()

        onNodeWithText("item-${TEST_ITEM_COUNT + 1}").assertDoesNotExist()
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

    /** クリップボードへ書かれた文字列を記録するだけの差し替え（§10.1 のコピー確認用）。 */
    private class RecordingClipboard : Clipboard {
        val written = mutableListOf<String>()

        override suspend fun getClipEntry(): ClipEntry? = null

        @OptIn(ExperimentalComposeUiApi::class)
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            val transferable = clipEntry?.asAwtTransferable ?: return
            (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.let { written += it }
        }

        // nativeClipboard は Compose 側で非推奨だが、インターフェースの実装としては置く必要がある。
        @Suppress("OVERRIDE_DEPRECATION")
        override val nativeClipboard: Any get() = error("この差し替えでは native のクリップボードを使わない")
    }

    /** 右クリックしたコンテキストメニューは押した座標に開く（§10.1）。 */
    @Test
    fun rightClickOpensMenuAtThePointer() = runComposeUiTest {
        setContent { TimelineScreen(items(), actions = TimelineActions()) }

        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick(Offset(20f, 10f)) }
        val nearLeft = onNodeWithTag(TAG_TIMELINE_MENU_HIDE).getBoundsInRoot().left
        onNodeWithTag(TAG_TIMELINE_MENU_HIDE).performClick()

        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick(Offset(160f, 10f)) }
        val farLeft = onNodeWithTag(TAG_TIMELINE_MENU_HIDE).getBoundsInRoot().left
        assertTrue(farLeft > nearLeft)
    }

    /**
     * 右クリックで開くメニューは 1 つ（§10.1）。本文を選べるようにした結果、選択機構が持つ
     * 右クリックメニューが重なって出た回帰を捕まえる。吹き出しの本体と合わせて根は 2 つ。
     */
    @Test
    fun rightClickOpensOnlyTheTimelineMenu() = runComposeUiTest {
        setContent { TimelineScreen(items(), actions = TimelineActions()) }

        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        assertEquals(2, onAllNodes(isRoot()).fetchSemanticsNodes().size)
    }

    /** 選択が無い間は「コピー」を出さない（§10.1）。複写するものが無いため。 */
    @Test
    fun contextMenuHasNoCopyWithoutSelection() = runComposeUiTest {
        setContent { TimelineScreen(items(), actions = TimelineActions()) }

        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onAllNodesWithTag(TAG_TIMELINE_MENU_COPY).assertCountEquals(0)
    }

    /** 本文を選んでから右クリックすると「コピー」が並び、押すと選択がクリップボードへ入る（§10.1）。 */
    @Test
    fun contextMenuCopiesTheSelection() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        val body = "認証コードは 483920 です。"
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                TimelineScreen(items(notification(text = body)), actions = TimelineActions())
            }
        }

        onNodeWithText(body).performMouseInput {
            moveTo(Offset(1f, centerY))
            press()
            moveTo(Offset(width * 0.5f, centerY))
            moveTo(Offset(width - 1f, centerY))
            release()
        }
        onNodeWithTag(TAG_TIMELINE_RECEIVED).performMouseInput { rightClick() }
        onNodeWithTag(TAG_TIMELINE_MENU_COPY).performClick()

        waitUntil { clipboard.written.isNotEmpty() }
        assertEquals(body, clipboard.written.single())
    }

    /** メニューボタンでコンテキストメニューが開く（§10.1）。Android はこれが唯一の入口になる。 */
    @Test
    fun menuButtonOpensContextMenu() = runComposeUiTest {
        setContent { TimelineScreen(items(), actions = TimelineActions()) }

        onAllNodesWithTag(TAG_TIMELINE_MENU_HIDE).assertCountEquals(0)
        onNodeWithTag(TAG_TIMELINE_MENU_BUTTON).performClick()
        onAllNodesWithTag(TAG_TIMELINE_MENU_HIDE).assertCountEquals(1)
    }

    /** 本文中のコードを押すとクリップボードへ入り、押せたことを通知で返す（§10.1）。 */
    @Test
    fun tappingCodeCopiesItToClipboard() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                TimelineScreen(items(notification(text = "483920")), actions = TimelineActions())
            }
        }

        onNodeWithText("483920").performClick()
        waitUntil { clipboard.written.isNotEmpty() }
        assertEquals(listOf("483920"), clipboard.written)
        onNodeWithText(CODE_COPIED_MESSAGE).assertIsDisplayed()
    }

    /** 5 桁以下の数字列はコードとして扱わず、押してもクリップボードへ入らない（§10.1）。 */
    @Test
    fun tappingShortDigitRunCopiesNothing() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                TimelineScreen(items(notification(text = "48392")), actions = TimelineActions())
            }
        }

        onNodeWithText("48392").performClick()
        assertEquals(emptyList(), clipboard.written)
    }

    private companion object {
        /** スクロール確認用のアイテム件数。画面高に対して十分にオーバーフローする件数にする。 */
        const val TEST_ITEM_COUNT = 30
        const val READING_POSITION_INDEX = 10
        val LIST_TEST_WIDTH = 300.dp
        val LIST_TEST_HEIGHT = 200.dp
        const val RAPID_ARRIVAL_COUNT = 6
        const val SCROLLBAR_SLOT_TAG = "scrollbar-slot"
    }
}
