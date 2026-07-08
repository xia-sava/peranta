package to.sava.peranta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineItem

/** チャット風タイムライン。受信通知は左寄せ、エラー・送信通知は右寄せに並べる。 */
@Composable
fun TimelineScreen(items: StateFlow<List<TimelineItem>>, modifier: Modifier = Modifier) {
    val list by items.collectAsStateWithLifecycle()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (list.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { TimelineRow(it) }
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
private fun TimelineRow(item: TimelineItem) {
    when (item) {
        is ReceivedNotification -> ReceivedBubble(item)
        is SentNotification -> SentBubble(item)
        is ErrorItem -> ErrorBubble(item)
    }
}

@Composable
private fun ReceivedBubble(item: ReceivedNotification) {
    val header = item.payload.displayHeader()
    Bubble(
        alignment = Alignment.CenterStart,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        time = item.timestampEpochMillis,
    ) {
        Text(text = header, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        item.payload.displayTitle()?.let {
            Text(text = it, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        }
        Text(text = item.payload.displayText(), style = MaterialTheme.typography.bodyMedium)
    }
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
