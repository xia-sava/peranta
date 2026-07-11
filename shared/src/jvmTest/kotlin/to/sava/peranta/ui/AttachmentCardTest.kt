package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AttachmentCardTest {

    private val blobId = "blob-1"

    private fun ref(
        expiresAt: Long? = null,
        fileName: String = "photo.jpg",
        mimeType: String = "image/jpeg",
        kind: AttachmentKind = AttachmentKind.IMAGE,
    ) = AttachmentRef(
        blobId = blobId,
        url = "https://peranta.sava.to/file/abc",
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = 2048,
        kind = kind,
        blobExpiresAtEpochMillis = expiresAt,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    private fun items(expiresAt: Long? = null, ref: AttachmentRef = ref(expiresAt)) =
        MutableStateFlow<List<TimelineItem>>(
            listOf(
                ReceivedFile(
                    id = "f1",
                    timestampEpochMillis = 1000L,
                    payload = FilePayload(
                        id = "f1",
                        from = "phone",
                        to = "*",
                        sentAtEpochMillis = 1000L,
                        caption = "写真です",
                        attachments = listOf(ref),
                        postedAtEpochMillis = 1000L,
                    ),
                ),
            ),
        )

    private fun ui(
        states: MutableStateFlow<Map<String, AttachmentDownloadState>>,
        onDownload: (AttachmentRef) -> Unit = {},
        onCancel: (String) -> Unit = {},
        onOpen: (String) -> Unit = {},
        onSave: (String) -> Unit = {},
        onShare: (String) -> Unit = {},
        canShare: Boolean = false,
        now: Long = 0L,
    ) = AttachmentUi(states, onDownload, onCancel, onOpen, onSave, onShare, canShare, now = { now })

    /** 未取得の添付はダウンロードボタンを出し、押すと onDownload が呼ばれる。 */
    @Test
    fun notDownloadedShowsDownloadButton() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onDownload = { requested = it.blobId }))
        }
        onNodeWithTag("$TAG_ATTACHMENT_DOWNLOAD_PREFIX$blobId").performClick()
        assertEquals(blobId, requested)
    }

    /** 進行中は進捗バーとキャンセルを出し、押すと onCancel が呼ばれる。 */
    @Test
    fun runningShowsProgressAndCancel() = runComposeUiTest {
        val states = MutableStateFlow(
            mapOf(blobId to AttachmentDownloadState(progress = TransferProgress(1024, 2048, TransferState.RUNNING))),
        )
        var cancelled: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onCancel = { cancelled = it }))
        }
        onAllNodesWithTag("$TAG_ATTACHMENT_PROGRESS_PREFIX$blobId").assertCountEquals(1)
        onNodeWithTag("$TAG_ATTACHMENT_CANCEL_PREFIX$blobId").performClick()
        assertEquals(blobId, cancelled)
    }

    /** 完了状態では開く・保存を出す。 */
    @Test
    fun completedShowsOpenAndSave() = runComposeUiTest {
        val states = MutableStateFlow(
            mapOf(blobId to AttachmentDownloadState(cached = true)),
        )
        var opened: String? = null
        var saved: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onOpen = { opened = it }, onSave = { saved = it }))
        }
        onNodeWithTag("$TAG_ATTACHMENT_OPEN_PREFIX$blobId").performClick()
        onNodeWithTag("$TAG_ATTACHMENT_SAVE_PREFIX$blobId").performClick()
        assertEquals(blobId, opened)
        assertEquals(blobId, saved)
    }

    /** 失敗状態では再試行ボタンを出し、押すと再ダウンロードを促す。 */
    @Test
    fun failedShowsRetry() = runComposeUiTest {
        val states = MutableStateFlow(
            mapOf(blobId to AttachmentDownloadState(progress = TransferProgress(0, 2048, TransferState.FAILED))),
        )
        var retried: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onDownload = { retried = it.blobId }))
        }
        onNodeWithTag("$TAG_ATTACHMENT_RETRY_PREFIX$blobId").performClick()
        assertEquals(blobId, retried)
    }

    /** 状態遷移: 未取得 → 進行中 → 完了 をボタンの出現で追える。 */
    @Test
    fun stateTransitionUpdatesButtons() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        setContent { TimelineScreen(items(), attachments = ui(states)) }

        onAllNodesWithTag("$TAG_ATTACHMENT_DOWNLOAD_PREFIX$blobId").assertCountEquals(1)

        states.value = mapOf(blobId to AttachmentDownloadState(progress = TransferProgress(100, 2048, TransferState.RUNNING)))
        waitForIdle()
        onAllNodesWithTag("$TAG_ATTACHMENT_PROGRESS_PREFIX$blobId").assertCountEquals(1)

        states.value = mapOf(blobId to AttachmentDownloadState(cached = true))
        waitForIdle()
        onAllNodesWithTag("$TAG_ATTACHMENT_OPEN_PREFIX$blobId").assertCountEquals(1)
    }

    /** 共有可能な端末（canShare=true）では完了状態で共有ボタンを出し、押すと onShare が呼ばれる。 */
    @Test
    fun completedShowsShareWhenShareable() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        var shared: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onShare = { shared = it }, canShare = true))
        }
        onNodeWithTag("$TAG_ATTACHMENT_SHARE_PREFIX$blobId").performClick()
        assertEquals(blobId, shared)
    }

    /** 共有非対応端末（Desktop 等）では共有ボタンを出さない。 */
    @Test
    fun completedHidesShareWhenNotShareable() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        setContent {
            TimelineScreen(items(), attachments = ui(states, canShare = false))
        }
        onAllNodesWithTag("$TAG_ATTACHMENT_SHARE_PREFIX$blobId").assertCountEquals(0)
    }

    /** 非画像ファイルはサムネイル無しでも未取得のダウンロードボタンを出し、ファイル名を表示する（種別アイコン表示）。 */
    @Test
    fun nonImageFileShowsDownloadWithoutThumbnail() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        val fileRef = ref(fileName = "report.pdf", mimeType = "application/pdf", kind = AttachmentKind.FILE)
        setContent {
            TimelineScreen(items(ref = fileRef), attachments = ui(states))
        }
        onAllNodesWithTag("$TAG_ATTACHMENT_DOWNLOAD_PREFIX$blobId").assertCountEquals(1)
        onNodeWithText("report.pdf").assertExists()
    }

    /** サーバ側の添付期限を過ぎた未取得の添付は、無効化された期限切れ表示にする。 */
    @Test
    fun expiredShowsDisabledExpiredLabel() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        setContent {
            TimelineScreen(items(expiresAt = 500L), attachments = ui(states, now = 1000L))
        }
        onAllNodesWithTag("$TAG_ATTACHMENT_EXPIRED_PREFIX$blobId").assertCountEquals(1)
        onAllNodesWithTag("$TAG_ATTACHMENT_DOWNLOAD_PREFIX$blobId").assertCountEquals(0)
    }
}
