package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.blob.MAX_NOTIFICATION_IMAGE_BYTES
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
        sizeBytes: Long = 2048,
    ) = AttachmentRef(
        blobId = blobId,
        url = "https://peranta.example.com/file/abc",
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
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
        autoDisplayImages: Boolean = false,
    ) = AttachmentUi(states, onDownload, onCancel, onOpen, onSave, onShare, canShare, now = { now }, autoDisplayImages = autoDisplayImages)

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

    /** 未取得の画像は「表示」、非画像は「ダウンロード」とボタン表記を出し分ける（動作は同一）。 */
    @Test
    fun buttonLabelDiffersByCategoryForNotDownloaded() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        setContent {
            TimelineScreen(items(), attachments = ui(states))
        }
        onNodeWithText("表示").assertExists()
        onAllNodesWithText("ダウンロード").assertCountEquals(0)
    }

    /** 非画像は未取得時に「ダウンロード」と表記する。 */
    @Test
    fun nonImageButtonLabelIsDownload() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        val fileRef = ref(fileName = "report.pdf", mimeType = "application/pdf", kind = AttachmentKind.FILE)
        setContent {
            TimelineScreen(items(ref = fileRef), attachments = ui(states))
        }
        onNodeWithText("ダウンロード").assertExists()
        onAllNodesWithText("表示").assertCountEquals(0)
    }

    /** 画像・未キャッシュ・進捗なし・期限内でトグル ON なら、カードの表示だけで自動的に onDownload が呼ばれる。 */
    @Test
    fun autoDisplayTriggersDownloadForUncachedImageWhenToggleOn() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = true))
        }
        waitForIdle()
        assertEquals(blobId, requested)
    }

    /** トグル OFF では画像であっても自動発火しない（既存の手動ボタンのみ残る）。 */
    @Test
    fun autoDisplayDoesNotTriggerWhenToggleOff() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = false))
        }
        waitForIdle()
        assertEquals(null, requested)
    }

    /** 画像以外（非 IMAGE カテゴリ）はトグル ON でも自動発火しない。 */
    @Test
    fun autoDisplayDoesNotTriggerForNonImage() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        val fileRef = ref(fileName = "report.pdf", mimeType = "application/pdf", kind = AttachmentKind.FILE)
        var requested: String? = null
        setContent {
            TimelineScreen(
                items(ref = fileRef),
                attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = true),
            )
        }
        waitForIdle()
        assertEquals(null, requested)
    }

    /** 失敗状態（再試行待ち）では自動発火しない（自動リトライしない、再試行ボタンのまま）。 */
    @Test
    fun autoDisplayDoesNotTriggerWhenFailed() = runComposeUiTest {
        val states = MutableStateFlow(
            mapOf(blobId to AttachmentDownloadState(progress = TransferProgress(0, 2048, TransferState.FAILED))),
        )
        var requested: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = true))
        }
        waitForIdle()
        assertEquals(null, requested)
    }

    /** 既にキャッシュ済みでは自動発火しない（再ダウンロード不要）。 */
    @Test
    fun autoDisplayDoesNotTriggerWhenCached() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        var requested: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = true))
        }
        waitForIdle()
        assertEquals(null, requested)
    }

    /** サーバ側の期限を過ぎている添付では自動発火しない。 */
    @Test
    fun autoDisplayDoesNotTriggerWhenExpired() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(
                items(expiresAt = 500L),
                attachments = ui(states, onDownload = { requested = it.blobId }, now = 1000L, autoDisplayImages = true),
            )
        }
        waitForIdle()
        assertEquals(null, requested)
    }

    /**
     * 自動表示の上限を超える画像は自動発火しない（§4.3.1）。
     * 手動の導線は残るので、押せば従来どおり取得できる。
     */
    @Test
    fun autoDisplayDoesNotTriggerAboveTheSizeLimit() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(
                items(ref = ref(sizeBytes = MAX_NOTIFICATION_IMAGE_BYTES + 1)),
                attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = true),
            )
        }
        waitForIdle()
        assertEquals(null, requested)
        onNodeWithTag("$TAG_ATTACHMENT_DOWNLOAD_PREFIX$blobId").performClick()
        assertEquals(blobId, requested)
    }

    /** 表示するだけの種別（画像・PDF 等）は確認なしで開ける。確認が読み飛ばされる状態を作らない（§4.3.2）。 */
    @Test
    fun viewableAttachmentOpensWithoutConfirmation() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        var opened: String? = null
        setContent { TimelineScreen(items(), attachments = ui(states, onOpen = { opened = it })) }

        onNodeWithTag("$TAG_ATTACHMENT_OPEN_PREFIX$blobId").performClick()
        assertEquals(blobId, opened)
        onAllNodesWithTag("$TAG_ATTACHMENT_OPEN_CONFIRM_PREFIX$blobId").assertCountEquals(0)
    }

    /** 素性を確かめられない種別は、確認を経てからでないと開かない（§4.3.2）。 */
    @Test
    fun unknownTypeAsksBeforeOpening() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        var opened: String? = null
        val archive = ref(fileName = "backup.zip", mimeType = "application/zip", kind = AttachmentKind.FILE)
        setContent { TimelineScreen(items(ref = archive), attachments = ui(states, onOpen = { opened = it })) }

        onNodeWithTag("$TAG_ATTACHMENT_OPEN_PREFIX$blobId").performClick()
        assertEquals(null, opened)

        onNodeWithTag("$TAG_ATTACHMENT_OPEN_CONFIRM_PREFIX$blobId").performClick()
        assertEquals(blobId, opened)
    }

    /** 確認をキャンセルすれば OS へは渡さない。 */
    @Test
    fun cancellingTheConfirmationDoesNotOpen() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        var opened: String? = null
        val archive = ref(fileName = "backup.zip", mimeType = "application/zip", kind = AttachmentKind.FILE)
        setContent { TimelineScreen(items(ref = archive), attachments = ui(states, onOpen = { opened = it })) }

        onNodeWithTag("$TAG_ATTACHMENT_OPEN_PREFIX$blobId").performClick()
        onNodeWithTag("$TAG_ATTACHMENT_OPEN_CANCEL_PREFIX$blobId").performClick()
        assertEquals(null, opened)
        onAllNodesWithTag("$TAG_ATTACHMENT_OPEN_CONFIRM_PREFIX$blobId").assertCountEquals(0)
    }

    /**
     * 画像を名乗る実行ファイルには開く導線を出さず、保存へ誘導する（§4.3.2）。
     * 保存は OS のダイアログを通るため残す。
     */
    @Test
    fun executableDisguisedAsImageHasNoOpenButton() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        val disguised = ref(fileName = "invoice.pdf.exe", mimeType = "image/png", kind = AttachmentKind.IMAGE)
        setContent { TimelineScreen(items(ref = disguised), attachments = ui(states)) }

        onAllNodesWithTag("$TAG_ATTACHMENT_OPEN_PREFIX$blobId").assertCountEquals(0)
        onNodeWithTag("$TAG_ATTACHMENT_OPEN_REFUSED_PREFIX$blobId").assertExists()
        onNodeWithTag("$TAG_ATTACHMENT_SAVE_PREFIX$blobId").assertExists()
    }

    /** mimeType と拡張子が食い違う添付も開く導線を出さない（§4.3.2）。 */
    @Test
    fun mismatchedMimeAndExtensionHasNoOpenButton() = runComposeUiTest {
        val states = MutableStateFlow(mapOf(blobId to AttachmentDownloadState(cached = true)))
        val mismatched = ref(fileName = "report.pdf", mimeType = "image/png", kind = AttachmentKind.IMAGE)
        setContent { TimelineScreen(items(ref = mismatched), attachments = ui(states)) }

        onAllNodesWithTag("$TAG_ATTACHMENT_OPEN_PREFIX$blobId").assertCountEquals(0)
        onNodeWithTag("$TAG_ATTACHMENT_OPEN_REFUSED_PREFIX$blobId").assertExists()
    }
}
