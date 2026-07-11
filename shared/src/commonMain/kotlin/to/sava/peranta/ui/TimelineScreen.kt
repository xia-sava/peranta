package to.sava.peranta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.FilePayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedFile
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

/**
 * 受信通知アイテムに対する操作（§3.4 / §10.1）。受信端末から送信元へ command を返送する。
 * すべて fire-and-forget で、呼び出し側（プラットフォーム配線）がコルーチンで実際の送信を行う。
 * 既定は no-op で、コマンド送信能力を持たない画面（送信ロール・空状態）では操作 UI を出さない。
 */
class TimelineActions(
    val invokeAction: (payload: NotificationPayload, actionIndex: Int) -> Unit = { _, _ -> },
    val dismiss: (item: ReceivedNotification) -> Unit = {},
    val muteApp: (payload: NotificationPayload) -> Unit = {},
)

/**
 * チャット風タイムライン（§10.1）。受信通知は左寄せ、エラー・送信通知は右寄せに並べる。
 * [actions] が渡されると受信通知にアクションボタン・スワイプで消す・長押し/右クリックメニューを付ける。
 * 「消す」はブロードキャスト送信と同時に、往復を待たず自端末の表示から即座に取り下げる。
 */
@Composable
fun TimelineScreen(
    items: StateFlow<List<TimelineItem>>,
    modifier: Modifier = Modifier,
    actions: TimelineActions? = null,
    attachments: AttachmentUi? = null,
) {
    val list by items.collectAsState()
    val locallyDismissed = remember { mutableStateListOf<String>() }
    val visible = list.filterNot { it.id in locallyDismissed }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (visible.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { item ->
                    TimelineRow(
                        item = item,
                        actions = actions,
                        attachments = attachments,
                        onLocalDismiss = { locallyDismissed.add(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "まだ通知はありません",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineRow(
    item: TimelineItem,
    actions: TimelineActions?,
    attachments: AttachmentUi?,
    onLocalDismiss: () -> Unit,
) {
    when (item) {
        is ReceivedNotification ->
            if (actions == null) ReceivedBubble(item) else InteractiveReceivedBubble(item, actions, onLocalDismiss)

        is ReceivedFile -> ReceivedFileBubble(item, attachments)
        is SentNotification -> SentBubble(item)
        is ErrorItem -> ErrorBubble(item)
    }
}

@Composable
private fun ReceivedFileBubble(item: ReceivedFile, attachments: AttachmentUi?) {
    Bubble(
        alignment = Alignment.CenterStart,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        time = item.timestampEpochMillis,
    ) {
        FilePayloadContent(item.payload, attachments)
    }
}

@Composable
private fun FilePayloadContent(payload: FilePayload, attachments: AttachmentUi?) {
    payload.caption?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium)
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
private fun ReceivedBubble(item: ReceivedNotification) {
    Bubble(
        alignment = Alignment.CenterStart,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        time = item.timestampEpochMillis,
    ) {
        ReceivedContent(item.payload)
    }
}

/**
 * 操作可能な受信通知バブル。左右スワイプで消し、長押し/右クリックでコンテキストメニューを開く。
 * 通知に元アクションがあればボタンとして並べ、押すと送信元へ invokeAction を返送する。
 */
@Composable
private fun InteractiveReceivedBubble(
    item: ReceivedNotification,
    actions: TimelineActions,
    onLocalDismiss: () -> Unit,
) {
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
            )
        },
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .testTag(TAG_TIMELINE_RECEIVED)
                    .timelineContextGesture(enabled = true) { menuOpen = true },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ReceivedContent(item.payload)
                    ActionButtons(item.payload, actions)
                    Text(
                        text = formatTimeOfDay(item.timestampEpochMillis),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            ContextMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                payload = item.payload,
                actions = actions,
                onDismissNotification = dismiss,
            )
        }
    }
}

@Composable
private fun ActionButtons(payload: Payload, actions: TimelineActions) {
    if (payload !is NotificationPayload || payload.actions.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        payload.actions.forEachIndexed { index, name ->
            TextButton(
                onClick = { actions.invokeAction(payload, index) },
                modifier = Modifier.testTag("$TAG_TIMELINE_ACTION_PREFIX$index"),
            ) {
                Text(text = name)
            }
        }
    }
}

@Composable
private fun ContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    payload: Payload,
    actions: TimelineActions,
    onDismissNotification: () -> Unit,
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
        if (payload is NotificationPayload) {
            payload.actions.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onDismissRequest()
                        actions.invokeAction(payload, index)
                    },
                    modifier = Modifier.testTag("$TAG_TIMELINE_MENU_ACTION_PREFIX$index"),
                )
            }
        }
    }
}

@Composable
private fun ReceivedContent(payload: Payload) {
    Text(
        text = payload.displayHeader(),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
    )
    payload.displayTitle()?.let {
        Text(text = it, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
    Text(text = payload.displayText(), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SentBubble(item: SentNotification) {
    Bubble(
        alignment = Alignment.CenterEnd,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        time = item.timestampEpochMillis,
    ) {
        Text(text = item.payload.displayHeader(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        Text(text = item.payload.displayText(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorBubble(item: ErrorItem) {
    Bubble(
        alignment = Alignment.CenterEnd,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
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
    time: Long,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                content()
                Text(
                    text = formatTimeOfDay(time),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

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
