package to.sava.peranta.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.model.MAX_REPLY_TEXT_BYTES
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

/** 元通知が生きている受信アイテムの、バブル右上のボタンのタグ。 */
const val TAG_TIMELINE_SOURCE_ALIVE_BUTTON: String = "timeline-source-alive-button"

/** 元通知が消えた受信アイテムの、バブル右上のボタンのタグ。 */
const val TAG_TIMELINE_SOURCE_DISMISSED_BUTTON: String = "timeline-source-dismissed-button"

/** コンテキストメニューの「送信元の通知を消す」項目のタグ。 */
const val TAG_TIMELINE_MENU_DISMISS: String = "timeline-menu-dismiss"

/** コンテキストメニューの「タイムラインから消す」項目のタグ。 */
const val TAG_TIMELINE_MENU_HIDE: String = "timeline-menu-hide"

/** 元通知が生きている通知をまとめて消すボタンのタグ。 */
const val TAG_TIMELINE_DISMISS_ALL: String = "timeline-dismiss-all"

/** まとめて消す確認ダイアログの実行ボタンのタグ。 */
const val TAG_TIMELINE_DISMISS_ALL_CONFIRM: String = "timeline-dismiss-all-confirm"

/** コンテキストメニューの「このアプリからの通知を非表示」項目のタグ。 */
const val TAG_TIMELINE_MENU_MUTE: String = "timeline-menu-mute"

/** コンテキストメニューのアクション項目のタグ接頭辞（末尾に action の index を付ける）。 */
const val TAG_TIMELINE_MENU_ACTION_PREFIX: String = "timeline-menu-action-"

/** 受信可能な端末でタイムラインが空のときの文言。 */
const val DEFAULT_EMPTY_TIMELINE_MESSAGE: String = "まだ通知はありません"

/**
 * 通知アクションのボタンラベルに付ける接頭辞（§10.1）。同じバブルには添付を操作するボタンも
 * 並ぶが、そちらはこの端末で完結する。通知アクションは送信元の端末で実行されるため、
 * 名前が同じでも別物であることを示す。
 * 送信元は端末名（[deviceLabel]）で名指す。転送元はスマホとは限らず、どの端末も送信側になれる（§2）。
 */
fun actionRunsOnSenderPrefix(deviceLabel: String): String = "$deviceLabel: "

/** 発出元で画面が開くアクションのボタンラベルに付ける注記（§10.1）。押しても手元には何も出ない。 */
const val ACTION_OPENS_ON_SENDER_SUFFIX: String = "（画面が開きます）"

/** 返信入力欄のタグ。 */
const val TAG_TIMELINE_REPLY_INPUT: String = "timeline-reply-input"

/** 返信送信ボタンのタグ。 */
const val TAG_TIMELINE_REPLY_SEND: String = "timeline-reply-send"

/** 返信本文が上限バイト数を超えているときの警告表示のタグ。 */
const val TAG_TIMELINE_REPLY_LIMIT_WARNING: String = "timeline-reply-limit-warning"

/** 元通知が生きていることを示し、押すと元端末と他の受信端末から消す記号（§10.1）。 */
const val SOURCE_ALIVE_GLYPH: String = "✓"

/** 元通知が消えていることを示し、押すとこの端末のタイムラインから消す記号（§10.1）。 */
const val SOURCE_DISMISSED_GLYPH: String = "×"

/** 輪郭線で示す吹き出しの線幅。 */
private val BUBBLE_BORDER_WIDTH = 1.dp

/** 返信本文が上限バイト数を超えているときに出す警告文言。 */
private val REPLY_LIMIT_WARNING: String =
    "返信本文が上限 $MAX_REPLY_TEXT_BYTES バイトを超えています。超過分は切り詰めて送信されます"

/** 返信本文 [text] の UTF-8 バイト長が送信上限を超えているか。 */
private fun exceedsReplyLimit(text: String): Boolean =
    text.encodeToByteArray().size > MAX_REPLY_TEXT_BYTES

/**
 * [payload] の [index] 番アクションの表示ラベル。どれも送信元の端末で実行されるため接頭辞を付け、
 * さらに画面が開くものには注記を添える。
 */
private fun actionLabel(payload: NotificationPayload, index: Int, name: String): String {
    val prefix = actionRunsOnSenderPrefix(payload.speakerName())
    return if (payload.actionKindAt(index) == ActionExecutionKind.OPENS_ON_SENDER) {
        "$prefix$name$ACTION_OPENS_ON_SENDER_SUFFIX"
    } else {
        "$prefix$name"
    }
}

/**
 * 受信通知アイテムに対する操作（§3.4 / §10.1）。送信元へ command を返送するものと、この端末の
 * タイムラインだけを変える [hideFromTimeline] からなる。
 * すべて fire-and-forget で、呼び出し側（プラットフォーム配線）がコルーチンで実際の処理を行う。
 * 既定は no-op で、コマンド送信能力を持たない画面（送信ロール・空状態）では操作 UI を出さない。
 */
class TimelineActions(
    val invokeAction: (payload: NotificationPayload, actionIndex: Int) -> Unit = { _, _ -> },
    val dismiss: (item: ReceivedNotification) -> Unit = {},
    val muteApp: (payload: NotificationPayload) -> Unit = {},
    val reply: (payload: NotificationPayload, actionIndex: Int, text: String) -> Unit = { _, _, _ -> },
    val hideFromTimeline: (item: ReceivedNotification) -> Unit = {},
    val dismissAll: (items: List<ReceivedNotification>) -> Unit = {},
)

/**
 * 表示中の並び [visible] から id が [targetId] のアイテムの index を返す。見つからなければ null
 * （剪定済み・ローカル非表示等、§10.1）。
 */
fun timelineScrollTargetIndex(visible: List<TimelineItem>, targetId: String): Int? =
    visible.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }

/**
 * チャット風タイムライン（§10.1）。受信通知は左寄せ、エラー・送信通知は右寄せに並べる。
 * [actions] が渡されると受信通知にアクションボタン・右上の状態兼ボタン・長押し/右クリックメニューを
 * 付ける。「送信元の通知を消す」は command をブロードキャストするだけで、この端末のタイムラインには
 * 残す。タイムラインから消したアイテムは [ReceivedNotification.hiddenFromTimeline] で表示から外れ、
 * 実体は剪定で落ちる（§11）。
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
    val visible = list.filterNot { it is ReceivedNotification && it.hiddenFromTimeline }

    // 起動時は最下部（最新）へアニメーション無しでジャンプする。以降は、末尾アイテムの itemCount が
    // 変わっていない間のスクロール／レイアウト変化を常時観測して「最下部にいるか」を wasAtBottom
    // として記録し続け、新着（末尾アイテムの id が変わる）が来たら、その新着が反映される直前に
    // 記録していた wasAtBottom で追従するかどうかを決める。追従アニメーション中にさらに新着が来た
    // 場合は、wasAtBottom によらず進行中の追従を継続して最新の末尾へ追い直す。
    var initialScrollDone by remember { mutableStateOf(false) }
    val currentVisible by rememberUpdatedState(visible)
    var wasAtBottom by remember { mutableStateOf(true) }
    var trackedItemCount by remember { mutableStateOf(-1) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect {
                if (currentVisible.size == trackedItemCount) {
                    wasAtBottom = !listState.canScrollForward
                }
            }
    }
    LaunchedEffect(listState) {
        var followJob: Job? = null
        snapshotFlow { currentVisible.lastOrNull()?.id }
            .collect {
                val visibleNow = currentVisible
                val lastIndex = visibleNow.lastIndex
                if (lastIndex < 0) return@collect
                if (!initialScrollDone) {
                    listState.scrollToItem(lastIndex)
                    initialScrollDone = true
                } else if (followJob?.isActive == true || wasAtBottom) {
                    followJob?.cancel()
                    followJob = launch { listState.animateScrollToItem(lastIndex) }
                }
                trackedItemCount = visibleNow.size
            }
    }

    // トーストクリック等、外部からの一度きりのジャンプ要求。上の追従ロジックとは別のジョブで動くため、
    // 追従状態（initialScrollDone・followJob）には触れないが、ジャンプ後の実際の位置は wasAtBottom へ
    // 反映し、直後の新着に対する追従可否判定へ引き継ぐ。初期表示の最下部ジャンプと競合しないよう、
    // それが済むまで待ってから動く。
    val onScrollToItemHandledState by rememberUpdatedState(onScrollToItemHandled)
    LaunchedEffect(scrollToItemId) {
        val targetId = scrollToItemId ?: return@LaunchedEffect
        // 表示アイテムが無いまま（＝最下部ジャンプが起こらないまま）のときも待ち続けないよう、
        // 空リストなら待たずに進む。
        snapshotFlow { initialScrollDone || currentVisible.isEmpty() }.first { it }
        timelineScrollTargetIndex(currentVisible, targetId)?.let { index ->
            listState.animateScrollToItem(index)
            wasAtBottom = !listState.canScrollForward
        }
        onScrollToItemHandledState()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (visible.isEmpty()) {
            EmptyState(emptyStateMessage)
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                actions?.let { DismissAllBar(dismissableSources(visible), it.dismissAll) }
                Box(modifier = Modifier.weight(1f)) {
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
                            )
                        }
                    }
                    lazyScrollbarContent(listState)
                }
            }
        }
    }
}

/**
 * 表示中のうち「元通知がまだ生きていて、消す対象を指せる」受信通知（§10.1）。
 * まとめて消す操作の対象で、同じ元通知の再投稿（同一 notificationKey）が複数並んでいても
 * 1 件として数える。
 */
private fun dismissableSources(visible: List<TimelineItem>): List<ReceivedNotification> =
    visible.asSequence()
        .filterIsInstance<ReceivedNotification>()
        .filterNot { it.sourceDismissed }
        .filter { it.payload.notificationKeyOrNull() != null }
        .distinctBy { it.payload.notificationKeyOrNull() }
        .toList()

/**
 * 元通知が生きている通知をまとめて消すバー（§10.1）。対象が無いときは出さない。
 * 全端末へ及んで取り消せない操作のため、確認を挟んでから実行する。
 */
@Composable
private fun DismissAllBar(
    targets: List<ReceivedNotification>,
    onDismissAll: (items: List<ReceivedNotification>) -> Unit,
) {
    if (targets.isEmpty()) return
    var confirming by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { confirming = true },
                modifier = Modifier.testTag(TAG_TIMELINE_DISMISS_ALL),
            ) { Text("$SOURCE_ALIVE_GLYPH の通知をまとめて消す（${targets.size}）") }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("送信元の通知をまとめて消す") },
            text = {
                Text(
                    "${targets.size} 件の元通知を消します。" +
                        "元端末と他の受信端末からも消え、取り消せません。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissAll(targets)
                        confirming = false
                    },
                    modifier = Modifier.testTag(TAG_TIMELINE_DISMISS_ALL_CONFIRM),
                ) { Text("消す") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("キャンセル") }
            },
        )
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
) {
    when (item) {
        is ReceivedNotification ->
            if (actions == null) {
                ReceivedBubble(item, attachments, fullText)
            } else {
                InteractiveReceivedBubble(item, actions, attachments, fullText)
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
private fun ReceivedBubble(item: ReceivedNotification, attachments: AttachmentUi?, fullText: FullTextUi?) {
    Bubble(
        alignment = Alignment.CenterStart,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        speaker = item.payload.speakerName(),
        time = item.timestampEpochMillis,
    ) {
        ReceivedContent(item.payload, attachments, fullText)
    }
}

/**
 * 操作可能な受信通知バブル。右上のボタンが元通知の状態を示し、長押し/右クリックでコンテキスト
 * メニューを開く。通知に元アクションがあればボタンとして並べる。REPLY 分類のアクションは押すと
 * インライン返信入力を開き、それ以外は押すと送信元へ invokeAction を返送する（§10.1）。
 * 元通知が生きている間は ✓ を出し、押すと元端末と他の受信端末から消す。
 * 元通知が既に消えている（[ReceivedNotification.sourceDismissed]）アイテムは × を出し、押すと
 * この端末のタイムラインから消す。あわせてアクションボタン・返信入力・コンテキストメニューの
 * アクション項目と「送信元の通知を消す」を出さない。
 */
@Composable
private fun InteractiveReceivedBubble(
    item: ReceivedNotification,
    actions: TimelineActions,
    attachments: AttachmentUi?,
    fullText: FullTextUi?,
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
    val dismissSource: () -> Unit = { actions.dismiss(item) }
    val hideFromTimeline: () -> Unit = { actions.hideFromTimeline(item) }
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
            // バルーン幅は本文と × ボタンを合わせた幅。× は本文に被せず右隣（上寄せ）に置く。
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    ReceivedContent(payload, attachments, fullText)
                    if (!item.sourceDismissed) {
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
                SourceStateButton(
                    sourceDismissed = item.sourceDismissed,
                    onClick = if (item.sourceDismissed) hideFromTimeline else dismissSource,
                )
            }
        }
        ContextMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            payload = payload,
            actions = actions,
            onActionClick = onActionClick,
            onDismissNotification = dismissSource,
            onHideFromTimeline = hideFromTimeline,
            showActionItems = !item.sourceDismissed,
        )
    }
}

/**
 * 受信通知バブル右上の、元通知の状態を示す兼ボタン（§10.1）。
 * 元通知が生きていれば ✓ を出し、押すと元端末と他の受信端末から消す。既に消えていれば × を出し、
 * 押すとこの端末のタイムラインから消す。バブルの内容を邪魔しない控えめな見た目にする。
 */
@Composable
private fun SourceStateButton(
    sourceDismissed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (sourceDismissed) SOURCE_DISMISSED_GLYPH else SOURCE_ALIVE_GLYPH,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(6.dp)
            .testTag(
                if (sourceDismissed) TAG_TIMELINE_SOURCE_DISMISSED_BUTTON else TAG_TIMELINE_SOURCE_ALIVE_BUTTON,
            ),
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
 * アイテム、§10.1）はアクション項目と「送信元の通知を消す」を出さない。
 * 「タイムラインから消す」「このアプリからの通知を非表示」は元通知の状態に依らず常に出す。
 */
@Composable
private fun ContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    payload: Payload,
    actions: TimelineActions,
    onActionClick: (index: Int) -> Unit,
    onDismissNotification: () -> Unit,
    onHideFromTimeline: () -> Unit,
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
        if (showActionItems) {
            DropdownMenuItem(
                text = { Text("送信元の通知を消す") },
                onClick = {
                    onDismissRequest()
                    onDismissNotification()
                },
                modifier = Modifier.testTag(TAG_TIMELINE_MENU_DISMISS),
            )
        }
        DropdownMenuItem(
            text = { Text("タイムラインから消す") },
            onClick = {
                onDismissRequest()
                onHideFromTimeline()
            },
            modifier = Modifier.testTag(TAG_TIMELINE_MENU_HIDE),
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

/**
 * 受信した通知・SMS の内容（§10.1）。本文に続けて画像・ファイル添付のカードを並べる（§4.3.1）。
 * 添付操作を持たない画面（[attachments] が null）ではカードを出さない。
 */
@Composable
private fun ReceivedContent(payload: Payload, attachments: AttachmentUi?, fullText: FullTextUi?) {
    ReceivedHeader(payload, attachments)
    payload.displayTitle()?.let {
        Text(text = it, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
    val textAttachment = payload.fullTextAttachment()
    if (fullText != null && textAttachment != null) {
        ExpandableText(preview = payload.displayText(), ref = textAttachment, fullText = fullText)
    } else {
        LinkifiedText(text = payload.displayText(), style = MaterialTheme.typography.bodyMedium)
    }
    if (attachments != null) {
        payload.displayAttachments().forEach { ref -> AttachmentCard(ref, attachments) }
    }
}

/**
 * 受信バブルのヘッダ行（§10.1）。送信者アイコンが届いていればアプリ名の左に丸く並べる（§4.3.1）。
 * 添付操作を持たない画面（[attachments] が null）ではアイコンを取得できないため名前だけ出す。
 */
@Composable
private fun ReceivedHeader(payload: Payload, attachments: AttachmentUi?) {
    val header = @Composable {
        Text(
            text = payload.displayHeader(),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    val icon = payload.senderIcon()
    if (attachments == null || icon == null) {
        header()
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SenderIcon(icon, attachments)
        header()
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

/**
 * エラーの吹き出し（§10.1）。起きた出来事の記録として残るものなので、面を塗らず輪郭線で示す。
 * 塗り潰すと解決済みのエラーも現在の異常のように読めてしまう（今まさに対処の要る未達は
 * タイムライン上部の警告バナーが担う、§10.5）。
 */
@Composable
private fun ErrorBubble(item: ErrorItem) {
    Bubble(
        alignment = Alignment.CenterEnd,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        borderColor = MaterialTheme.colorScheme.error,
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
    borderColor: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.medium,
            border = borderColor?.let { BorderStroke(BUBBLE_BORDER_WIDTH, it) },
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
    val stamp = formatTimestamp(time, nowEpochMillis())
    val text = if (speaker != null) "$speaker・$stamp" else stamp
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
