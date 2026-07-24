package to.sava.peranta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import to.sava.peranta.model.ActionExecutionKind
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.actionKindAt
import to.sava.peranta.send.MAX_REPLY_TEXT_BYTES
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineItem

/** 受信通知バブルのタグ（コンテキストメニューを開くジェスチャの対象）。 */
const val TAG_TIMELINE_RECEIVED: String = "timeline-received"

/** インラインのアクションボタンのタグ接頭辞（末尾に action の index を付ける）。 */
const val TAG_TIMELINE_ACTION_PREFIX: String = "timeline-action-"

/** コンテキストメニューの「消す」項目のタグ。 */
const val TAG_TIMELINE_MENU_DISMISS: String = "timeline-menu-dismiss"

/** コンテキストメニューの「このアプリからの通知を非表示」項目のタグ。 */
const val TAG_TIMELINE_MENU_MUTE: String = "timeline-menu-mute"

/** コンテキストメニューのアクション項目のタグ接頭辞（末尾に action の index を付ける）。 */
const val TAG_TIMELINE_MENU_ACTION_PREFIX: String = "timeline-menu-action-"

/** 受信可能な端末でタイムラインが空のときの文言。 */
const val DEFAULT_EMPTY_TIMELINE_MESSAGE: String = "まだ通知はありません"

/** 発出元で画面が開くアクションのボタンラベルに付ける注記（§10.1）。 */
const val ACTION_OPENS_ON_SENDER_SUFFIX: String = "（スマホで）"

/** 返信入力欄のタグ。 */
const val TAG_TIMELINE_REPLY_INPUT: String = "timeline-reply-input"

/** 返信送信ボタンのタグ。 */
const val TAG_TIMELINE_REPLY_SEND: String = "timeline-reply-send"

/** 返信本文が上限バイト数を超えているときの警告表示のタグ。 */
const val TAG_TIMELINE_REPLY_LIMIT_WARNING: String = "timeline-reply-limit-warning"

/** 元通知が消えた受信アイテムに出す注記のタグ。 */
const val TAG_TIMELINE_SOURCE_DISMISSED_NOTE: String = "timeline-source-dismissed-note"

/** 元通知が消えた受信アイテムに出す注記（§10.1）。 */
const val SOURCE_DISMISSED_NOTE: String = "元の通知は消えています"

/** 返信本文が上限バイト数を超えているときに出す警告文言。 */
private val REPLY_LIMIT_WARNING: String =
    "返信本文が上限 $MAX_REPLY_TEXT_BYTES バイトを超えています。超過分は切り詰めて送信されます"

/** 返信本文 [text] の UTF-8 バイト長が送信上限を超えているか。 */
private fun exceedsReplyLimit(text: String): Boolean =
    text.encodeToByteArray().size > MAX_REPLY_TEXT_BYTES

/** [payload] の [index] 番アクションの表示ラベル。発出元で開くアクションには注記を付ける。 */
private fun actionLabel(payload: NotificationPayload, index: Int, name: String): String =
    if (payload.actionKindAt(index) == ActionExecutionKind.OPENS_ON_SENDER) {
        "$name$ACTION_OPENS_ON_SENDER_SUFFIX"
    } else {
        name
    }

/**
 * 受信通知アイテムに対する操作（§3.4 / §10.1）。受信端末から送信元へ command を返送する。
 * すべて fire-and-forget で、呼び出し側（プラットフォーム配線）がコルーチンで実際の送信を行う。
 * 既定は no-op で、コマンド送信能力を持たない画面（送信ロール・空状態）では操作 UI を出さない。
 */
class TimelineActions(
    val invokeAction: (payload: NotificationPayload, actionIndex: Int) -> Unit = { _, _ -> },
    val dismiss: (item: ReceivedNotification) -> Unit = {},
    val muteApp: (payload: NotificationPayload) -> Unit = {},
    val reply: (payload: NotificationPayload, actionIndex: Int, text: String) -> Unit = { _, _, _ -> },
)

/**
 * 表示中の並び [visible] から id が [targetId] のアイテムの index を返す。見つからなければ null
 * （剪定済み・ローカル非表示等、§10.1）。
 */
fun timelineScrollTargetIndex(visible: List<TimelineItem>, targetId: String): Int? =
    visible.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }

/**
 * チャット風タイムライン（§10.1）。受信通知は左寄せ、エラー・送信通知は右寄せに並べる。
 * [actions] が渡されると受信通知にアクションボタン・スワイプで消す・長押し/右クリックメニューを付ける。
 * 「消す」はブロードキャスト送信と同時に、往復を待たず自端末の表示から即座に取り下げる。
 * 並び順は時系列順（古い→新しい、上→下）で最新が最下部。起動時は最下部へジャンプし、
 * 最下部表示中の新着だけ追従する（§10.1）。[listState] を呼び出し側から注入でき、
 * [lazyScrollbarContent] スロットで Desktop 用スクロールバー等を注入できる（`AppFilterScreen` と同型）。
 * [scrollToItemId] が非 null になると、対象アイテムまでアニメーション付きでスクロールし、
 * 見つかった/見つからなかったに関わらず [onScrollToItemHandled] を呼んで消費を通知する
 * （対象が表示リストに無ければスクロールせず消費のみ通知する）。最下部追従ロジックとは独立して動く。
 */
@Composable
fun TimelineScreen(
    items: StateFlow<List<TimelineItem>>,
    modifier: Modifier = Modifier,
    actions: TimelineActions? = null,
    attachments: AttachmentUi? = null,
    fullText: FullTextUi? = null,
    listState: LazyListState = rememberLazyListState(),
    lazyScrollbarContent: @Composable BoxScope.(listState: LazyListState) -> Unit = {},
    emptyStateMessage: String = DEFAULT_EMPTY_TIMELINE_MESSAGE,
    scrollToItemId: String? = null,
    onScrollToItemHandled: () -> Unit = {},
) {
    val list by items.collectAsState()
    val locallyDismissed = remember { mutableStateListOf<String>() }
    val visible = list.filterNot { it.id in locallyDismissed }

    // 起動時は最下部（最新）へアニメーション無しでジャンプし、以降は末尾アイテムが変わるたびに
    // 追従するかどうかを決める（§10.1）。追従アニメーション中に次の新着が来た場合は、その時点の
    // 表示位置（アニメーション未達のため一時的に末尾ではない）で判定せず、進行中の追従を継続して
    // 最新の末尾へ追い直す。追従アニメーションが動いていないときだけ、下方向にこれ以上スクロール
    // できない＝末尾まで到達していたかで新規に追従するかを判定する。最新アイテムが画面内に見えて
    // いても、少しでも上へスクロールしていれば追従しない。
    var initialScrollDone by remember { mutableStateOf(false) }
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(listState) {
        var followJob: Job? = null
        snapshotFlow { currentVisible.lastOrNull()?.id }
            .collect {
                val lastIndex = currentVisible.lastIndex
                if (lastIndex < 0) return@collect
                if (!initialScrollDone) {
                    listState.scrollToItem(lastIndex)
                    initialScrollDone = true
                } else if (followJob?.isActive == true || !listState.canScrollForward) {
                    followJob?.cancel()
                    followJob = launch { listState.animateScrollToItem(lastIndex) }
                }
            }
    }

    // トーストクリック等、外部からの一度きりのジャンプ要求。上の追従ロジックとは別のジョブで動くため、
    // 追従状態（initialScrollDone・followJob）には触れない。初期表示の最下部ジャンプと競合しないよう、
    // それが済むまで待ってから動く。
    val onScrollToItemHandledState by rememberUpdatedState(onScrollToItemHandled)
    LaunchedEffect(scrollToItemId) {
        val targetId = scrollToItemId ?: return@LaunchedEffect
        // 表示アイテムが無いまま（＝最下部ジャンプが起こらないまま）のときも待ち続けないよう、
        // 空リストなら待たずに進む。
        snapshotFlow { initialScrollDone || currentVisible.isEmpty() }.first { it }
        timelineScrollTargetIndex(currentVisible, targetId)?.let { index -> listState.animateScrollToItem(index) }
        onScrollToItemHandledState()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (visible.isEmpty()) {
            EmptyState(emptyStateMessage)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.id }) { item ->
                        TimelineRow(
                            item = item,
                            actions = actions,
                            attachments = attachments,
                            fullText = fullText,
                            onLocalDismiss = { locallyDismissed.add(item.id) },
                        )
                    }
                }
                lazyScrollbarContent(listState)
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineRow(
    item: TimelineItem,
    actions: TimelineActions?,
    attachments: AttachmentUi?,
    fullText: FullTextUi?,
    onLocalDismiss: () -> Unit,
) {
    when (item) {
        is ReceivedNotification ->
            if (actions == null) {
                ReceivedBubble(item, fullText)
            } else {
                InteractiveReceivedBubble(item, actions, fullText, onLocalDismiss)
            }

        is ReceivedFile -> ReceivedFileBubble(item, attachments)
        is ReceivedMessage -> MessageBubble(item)
        is SentNotification -> SentBubble(item)
        is ErrorItem -> ErrorBubble(item)
    }
}

@Composable
private fun MessageBubble(item: ReceivedMessage) {
    Bubble(
        alignment = Alignment.CenterStart,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        speaker = item.payload.speakerName(),
        time = item.timestampEpochMillis,
    ) {
        LinkifiedText(text = item.payload.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReceivedFileBubble(item: ReceivedFile, attachments: AttachmentUi?) {
    Bubble(
        alignment = Alignment.CenterStart,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        speaker = item.payload.speakerName(),
        time = item.timestampEpochMillis,
    ) {
        FilePayloadContent(item.payload, attachments)
    }
}

@Composable
private fun FilePayloadContent(payload: FilePayload, attachments: AttachmentUi?) {
    payload.caption?.let {
        LinkifiedText(text = it, style = MaterialTheme.typography.bodyMedium)
    }
    payload.attachments.forEach { ref ->
        if (attachments == null) {
            Text(
                text = "${ref.fileName} (${formatFileSize(ref.sizeBytes)})",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            AttachmentCard(ref, attachments)
        }
    }
}

@Composable
private fun ReceivedBubble(item: ReceivedNotification, fullText: FullTextUi?) {
    Bubble(
        alignment = Alignment.CenterStart,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        speaker = item.payload.speakerName(),
        time = item.timestampEpochMillis,
    ) {
        ReceivedContent(item.payload, fullText)
    }
}

/**
 * 操作可能な受信通知バブル。左右スワイプで消し、長押し/右クリックでコンテキストメニューを開く。
 * 通知に元アクションがあればボタンとして並べる。REPLY 分類のアクションは押すとインライン返信入力を
 * 開き、それ以外は押すと送信元へ invokeAction を返送する（§10.1）。
 * 元通知が既に消えている（[ReceivedNotification.sourceDismissed]）アイテムはアクションボタン・
 * 返信入力・コンテキストメニューのアクション項目を出さず、代わりに注記を表示する。
 * スワイプ・「消す」（ローカル非表示）は引き続き行える。
 */
@Composable
private fun InteractiveReceivedBubble(
    item: ReceivedNotification,
    actions: TimelineActions,
    fullText: FullTextUi?,
    onLocalDismiss: () -> Unit,
) {
    val payload = item.payload
    var replyingIndex by remember { mutableStateOf<Int?>(null) }
    val onActionClick: (Int) -> Unit = { index ->
        val notificationPayload = payload as? NotificationPayload
        when {
            notificationPayload == null -> Unit
            notificationPayload.actionKindAt(index) == ActionExecutionKind.REPLY ->
                replyingIndex = if (replyingIndex == index) null else index
            else -> actions.invokeAction(notificationPayload, index)
        }
    }
    val dismiss: () -> Unit = {
        actions.dismiss(item)
        onLocalDismiss()
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                false
            } else {
                dismiss()
                true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // 静止時に吹出しの背後へ赤が透けないよう、スワイプ中だけ描く。
            if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer),
                )
            }
        },
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .testTag(TAG_TIMELINE_RECEIVED)
                    .timelineContextGesture(enabled = true) { menuOpen = true },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ReceivedContent(payload, fullText)
                    if (item.sourceDismissed) {
                        SourceDismissedNote()
                    } else {
                        ActionButtons(payload, onActionClick)
                        replyingIndex?.let { index ->
                            (payload as? NotificationPayload)?.let { notificationPayload ->
                                ReplyInput(
                                    onSend = { text ->
                                        actions.reply(notificationPayload, index, text)
                                        replyingIndex = null
                                    },
                                    onCancel = { replyingIndex = null },
                                )
                            }
                        }
                    }
                    SpeakerTimeRow(speaker = payload.speakerName(), time = item.timestampEpochMillis)
                }
            }
            ContextMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                payload = payload,
                actions = actions,
                onActionClick = onActionClick,
                onDismissNotification = dismiss,
                showActionItems = !item.sourceDismissed,
            )
        }
    }
}

/** 元通知が消えた受信アイテムに出す控えめな注記（§10.1）。 */
@Composable
private fun SourceDismissedNote() {
    Text(
        text = SOURCE_DISMISSED_NOTE,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(TAG_TIMELINE_SOURCE_DISMISSED_NOTE),
    )
}

@Composable
private fun ActionButtons(payload: Payload, onActionClick: (index: Int) -> Unit) {
    if (payload !is NotificationPayload || payload.actions.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        payload.actions.forEachIndexed { index, name ->
            TextButton(
                onClick = { onActionClick(index) },
                modifier = Modifier.testTag("$TAG_TIMELINE_ACTION_PREFIX$index"),
            ) {
                Text(text = actionLabel(payload, index, name))
            }
        }
    }
}

/**
 * REPLY 分類のアクション用インライン返信入力（§10.1）。上限バイト数超過時は切り詰め警告のみ出し、
 * 実際の切り詰めは送信経路（[to.sava.peranta.send.CommandSender.reply]）側で行う。
 * 空白のみの入力では送信を無効化する。
 */
@Composable
private fun ReplyInput(onSend: (text: String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val overLimit = exceedsReplyLimit(text)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().testTag(TAG_TIMELINE_REPLY_INPUT),
            minLines = 1,
            maxLines = 3,
            isError = overLimit,
            supportingText = if (overLimit) {
                {
                    Text(
                        text = REPLY_LIMIT_WARNING,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TAG_TIMELINE_REPLY_LIMIT_WARNING),
                    )
                }
            } else {
                null
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCancel) { Text("キャンセル") }
            TextButton(
                onClick = { onSend(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(TAG_TIMELINE_REPLY_SEND),
            ) { Text("送信") }
        }
    }
}

/**
 * 受信通知アイテムのコンテキストメニュー。[showActionItems] が false のとき（元通知が消えた
 * アイテム、§10.1）はアクション項目を出さず、「消す」「このアプリからの通知を非表示」は常に出す。
 */
@Composable
private fun ContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    payload: Payload,
    actions: TimelineActions,
    onActionClick: (index: Int) -> Unit,
    onDismissNotification: () -> Unit,
    showActionItems: Boolean,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        if (payload is NotificationPayload) {
            DropdownMenuItem(
                text = { Text("このアプリからの通知を非表示") },
                onClick = {
                    onDismissRequest()
                    actions.muteApp(payload)
                },
                modifier = Modifier.testTag(TAG_TIMELINE_MENU_MUTE),
            )
        }
        DropdownMenuItem(
            text = { Text("消す") },
            onClick = {
                onDismissRequest()
                onDismissNotification()
            },
            modifier = Modifier.testTag(TAG_TIMELINE_MENU_DISMISS),
        )
        if (payload is NotificationPayload && showActionItems) {
            payload.actions.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(actionLabel(payload, index, name)) },
                    onClick = {
                        onDismissRequest()
                        onActionClick(index)
                    },
                    modifier = Modifier.testTag("$TAG_TIMELINE_MENU_ACTION_PREFIX$index"),
                )
            }
        }
    }
}

@Composable
private fun ReceivedContent(payload: Payload, fullText: FullTextUi?) {
    Text(
        text = payload.displayHeader(),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
    )
    payload.displayTitle()?.let {
        Text(text = it, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
    val textAttachment = payload.fullTextAttachment()
    if (fullText != null && textAttachment != null) {
        ExpandableText(preview = payload.displayText(), ref = textAttachment, fullText = fullText)
    } else {
        LinkifiedText(text = payload.displayText(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 送信済みバブル（§3.3）。[FilePayload] は受信側と同じ表示（ファイル名+サイズ+キャプション）を再利用し、
 * それ以外はヘッダ+タイトル+本文のテキスト表示にとどめる。自分の端末からの送信のため、アクションボタンや
 * 全文添付の展開は出さない（手元の通知は端末側で直接操作でき、全文も手元にある）。
 */
@Composable
private fun SentBubble(item: SentNotification) {
    Bubble(
        alignment = Alignment.CenterEnd,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        speaker = item.payload.speakerName(),
        time = item.timestampEpochMillis,
    ) {
        val payload = item.payload
        if (payload is FilePayload) {
            FilePayloadContent(payload, attachments = null)
        } else if (payload is MessagePayload) {
            LinkifiedText(text = payload.text, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(text = payload.displayHeader(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            payload.displayTitle()?.let {
                Text(text = it, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            }
            LinkifiedText(text = payload.displayText(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorBubble(item: ErrorItem) {
    Bubble(
        alignment = Alignment.CenterEnd,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        speaker = null,
        time = item.timestampEpochMillis,
    ) {
        Text(text = item.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Bubble(
    alignment: Alignment,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    speaker: String?,
    time: Long,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                content()
                SpeakerTimeRow(speaker, time)
            }
        }
    }
}

/** 時刻行（§3.2）。発言者名があれば「{名前}・{時刻}」、無ければ（ErrorItem 等）時刻のみ表示する。 */
@Composable
private fun SpeakerTimeRow(speaker: String?, time: Long) {
    val text = if (speaker != null) "$speaker・${formatTimeOfDay(time)}" else formatTimeOfDay(time)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 発言者表示名（§3.2）。送信元端末名があればそれ、無ければ deviceId にフォールバックする。 */
private fun Payload.speakerName(): String = fromName ?: from

private fun Payload.displayHeader(): String = when (this) {
    is NotificationPayload -> appName
    is SmsPayload -> senderName ?: senderNumber
    else -> from
}

private fun Payload.displayTitle(): String? = when (this) {
    is NotificationPayload -> title.ifBlank { null }
    else -> null
}

private fun Payload.displayText(): String = when (this) {
    is NotificationPayload -> text
    is SmsPayload -> text
    else -> ""
}
