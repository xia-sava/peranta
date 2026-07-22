package to.sava.peranta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.AttachmentUi
import to.sava.peranta.ui.DEFAULT_EMPTY_TIMELINE_MESSAGE
import to.sava.peranta.ui.FullTextUi
import to.sava.peranta.ui.TimelineActions
import to.sava.peranta.ui.TimelineScreen

/** タイムライン本体。ナビゲーションは画面シェルへ委ね、ここは受信済みアイテムの表示に専念する。 */
@Composable
fun App(
    items: StateFlow<List<TimelineItem>>,
    timelineActions: TimelineActions? = null,
    attachmentUi: AttachmentUi? = null,
    fullTextUi: FullTextUi? = null,
    lazyScrollbarContent: @Composable BoxScope.(listState: LazyListState) -> Unit = {},
    emptyStateMessage: String = DEFAULT_EMPTY_TIMELINE_MESSAGE,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TimelineScreen(
            items,
            actions = timelineActions,
            attachments = attachmentUi,
            fullText = fullTextUi,
            lazyScrollbarContent = lazyScrollbarContent,
            emptyStateMessage = emptyStateMessage,
        )
    }
}

/** 設定が未完了のときに表示する画面。設定 UI 自体は後続マイルストーンで実装する。 */
@Composable
fun App() {
    MessageScreen(
        title = "設定が必要です",
        body = "サーバ・トークン・共有鍵・端末名を設定すると通知の受信を開始します。",
    )
}

/** 受信が続行不能なエラーで停止したときに表示する画面。 */
@Composable
fun App(errorMessage: String) {
    MessageScreen(title = "エラー", body = errorMessage)
}

@Composable
private fun MessageScreen(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
