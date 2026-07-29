package to.sava.peranta.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.blob.MAX_SENDER_ICON_BYTES
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** バブルのヘッダに出す送信者アイコン（§4.3.1）。 */
@OptIn(ExperimentalTestApi::class)
class SenderIconTest {

    private val blobId = "blob-icon"

    private fun iconRef(expiresAt: Long? = null, sizeBytes: Long = 512) = AttachmentRef(
        blobId = blobId,
        url = "https://peranta.example.com/file/icon",
        fileName = "sender-icon-1000.png",
        mimeType = "image/png",
        sizeBytes = sizeBytes,
        kind = AttachmentKind.IMAGE,
        blobExpiresAtEpochMillis = expiresAt,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    private fun items(senderIcon: AttachmentRef? = iconRef()) = MutableStateFlow<List<TimelineItem>>(
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
                    title = "田中さん",
                    text = "写真を送りました",
                    notificationKey = "0|com.example.chat|1|null|10",
                    postedAtEpochMillis = 1000L,
                    senderIcon = senderIcon,
                ),
            ),
        ),
    )

    private fun ui(
        states: MutableStateFlow<Map<String, AttachmentDownloadState>>,
        onDownload: (AttachmentRef) -> Unit = {},
        now: Long = 0L,
        autoDisplayImages: Boolean = false,
    ) = AttachmentUi(states, onDownload = onDownload, now = { now }, autoDisplayImages = autoDisplayImages)

    /** サムネイルが取得できていればヘッダにアイコンを描く。 */
    @Test
    fun cachedIconIsShownInHeader() = runComposeUiTest {
        val states = MutableStateFlow(
            mapOf(blobId to AttachmentDownloadState(cached = true, thumbnail = ImageBitmap(16, 16))),
        )
        setContent { TimelineScreen(items(), attachments = ui(states)) }

        onNodeWithTag("$TAG_SENDER_ICON_PREFIX$blobId").assertExists()
    }

    /** サムネイルが無い間は何も描かない（本文の位置をずらさない）。 */
    @Test
    fun iconIsAbsentUntilThumbnailArrives() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        setContent { TimelineScreen(items(), attachments = ui(states)) }

        onAllNodesWithTag("$TAG_SENDER_ICON_PREFIX$blobId").assertCountEquals(0)
    }

    /**
     * 画像添付の自動表示が OFF でもアイコンは取得する。
     * 数 KB と小さく、手動で取得させる導線を持たないため。
     */
    @Test
    fun iconIsFetchedEvenWhenAutoDisplayImagesIsOff() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(
                items(),
                attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = false),
            )
        }

        assertEquals(blobId, requested)
    }

    /** 期限切れの blob は取得しに行かない（必ず失敗するため）。 */
    @Test
    fun expiredIconIsNotFetched() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(
                items(iconRef(expiresAt = 500L)),
                attachments = ui(states, onDownload = { requested = it.blobId }, now = 1000L),
            )
        }

        assertNull(requested)
    }

    /**
     * 上限を超える大きさを宣言したアイコンは取りに行かない（§4.3.1）。
     * 送信側の符号化上限（64 KiB）と同じ値で、正しく作られたアイコンは常に収まる。
     */
    @Test
    fun oversizedIconIsNotFetched() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        var requested: String? = null
        setContent {
            TimelineScreen(
                items(iconRef(sizeBytes = MAX_SENDER_ICON_BYTES + 1)),
                attachments = ui(states, onDownload = { requested = it.blobId }, autoDisplayImages = true),
            )
        }

        assertNull(requested)
    }

    /**
     * 取りに行かなかったアイコンは跡を残す（§4.3.1）。
     * 送信者アイコンには手動取得の導線が無く、何も描かないと
     * 「アイコンを持たない通知」と区別が付かなくなる。
     */
    @Test
    fun skippedIconLeavesAMark() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        setContent {
            TimelineScreen(items(iconRef(sizeBytes = MAX_SENDER_ICON_BYTES + 1)), attachments = ui(states))
        }

        onNodeWithTag("$TAG_SENDER_ICON_SKIPPED_PREFIX$blobId").assertExists()
    }

    /** 上限に収まるアイコンが未取得の間は跡を出さない（本文の位置をずらさない）。 */
    @Test
    fun pendingIconLeavesNoMark() = runComposeUiTest {
        val states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
        setContent { TimelineScreen(items(), attachments = ui(states)) }

        onAllNodesWithTag("$TAG_SENDER_ICON_SKIPPED_PREFIX$blobId").assertCountEquals(0)
    }

    /** 取得に失敗した後は自動で再取得しない（無限リトライを避ける）。 */
    @Test
    fun failedIconIsNotRefetched() = runComposeUiTest {
        val states = MutableStateFlow(
            mapOf(blobId to AttachmentDownloadState(progress = TransferProgress(0, 512, TransferState.FAILED))),
        )
        var requested: String? = null
        setContent {
            TimelineScreen(items(), attachments = ui(states, onDownload = { requested = it.blobId }))
        }

        assertNull(requested)
    }
}
