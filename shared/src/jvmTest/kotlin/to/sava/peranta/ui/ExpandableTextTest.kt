package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ExpandableTextTest {

    private val blobId = "txt-1"
    private val preview = "プレビュー本文…"
    private val full = "これは展開された全文の本文です。とても長い内容が続きます。"

    private fun textRef() = AttachmentRef(
        blobId = blobId,
        url = "https://peranta.example.com/file/abc",
        fileName = "message.txt",
        mimeType = "text/plain",
        sizeBytes = 2000,
        kind = AttachmentKind.TEXT,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    private fun items(
        attachments: List<AttachmentRef> = listOf(textRef()),
    ) = MutableStateFlow<List<TimelineItem>>(
        listOf(
            ReceivedNotification(
                id = "n1",
                timestampEpochMillis = 1000L,
                payload = NotificationPayload(
                    id = "n1",
                    from = "phone",
                    to = "*",
                    sentAtEpochMillis = 1000L,
                    packageName = "com.example.chat",
                    appName = "Chat",
                    title = "件名",
                    text = preview,
                    notificationKey = "0|com.example.chat|1|null|10",
                    postedAtEpochMillis = 1000L,
                    attachments = attachments,
                ),
            ),
        ),
    )

    /** TEXT 添付を持つ通知は、表示時に自動取得して全文へ差し替える（成功時）。 */
    @Test
    fun autoFetchExpandsToFullText() = runComposeUiTest {
        var fetched: String? = null
        setContent {
            TimelineScreen(
                items(),
                fullText = FullTextUi { ref ->
                    fetched = ref.blobId
                    full
                },
            )
        }
        waitForIdle()
        onNodeWithText(full).assertExists()
        onAllNodesWithText(preview).assertCountEquals(0)
        assert(fetched == blobId)
    }

    /** 取得失敗（null 返却）ではプレビューのまま据え置き、致命的エラー表示にはしない。 */
    @Test
    fun fetchFailureKeepsPreview() = runComposeUiTest {
        setContent {
            TimelineScreen(items(), fullText = FullTextUi { null })
        }
        waitForIdle()
        onNodeWithText(preview).assertExists()
        onAllNodesWithText(full).assertCountEquals(0)
    }

    /** 取得中（未完了）はプレビューを出しておく。完了したら全文へ差し替わる。 */
    @Test
    fun showsPreviewWhileFetchingThenExpands() = runComposeUiTest {
        val gate = CompletableDeferred<String>()
        setContent {
            TimelineScreen(items(), fullText = FullTextUi { gate.await() })
        }
        waitForIdle()
        onNodeWithText(preview).assertExists()
        onAllNodesWithText(full).assertCountEquals(0)

        gate.complete(full)
        waitForIdle()
        onNodeWithText(full).assertExists()
    }

    /** FullTextUi を渡さない画面では自動取得せず、プレビューをそのまま表示する。 */
    @Test
    fun withoutFullTextUiShowsPreview() = runComposeUiTest {
        setContent { TimelineScreen(items()) }
        waitForIdle()
        onNodeWithText(preview).assertExists()
    }
}
