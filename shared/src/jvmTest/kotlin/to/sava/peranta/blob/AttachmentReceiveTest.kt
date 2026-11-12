package to.sava.peranta.blob

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.AttachmentDownloadStates
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 受信した添付の取得済みの反映（[primeCachedAttachments]）と全文添付の取得（[FullTextCache]）（§4.3）。
 * Android と Desktop が共有する判断を、プラットフォームのデコードと転送を差し替えて確かめる。
 */
class AttachmentReceiveTest {

    private val baseDir: File = Files.createTempDirectory("peranta-attach-receive-test").toFile()

    @AfterTest
    fun cleanup() {
        baseDir.deleteRecursively()
    }

    private fun ref(
        blobId: String,
        kind: AttachmentKind = AttachmentKind.IMAGE,
        sizeBytes: Long = 2048,
    ) = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "$blobId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        kind = kind,
        enc = BlobEnc(keyId = "k1", saltBase64 = "c2FsdA==", chunkSize = 1024, totalChunks = 2),
    )

    private fun receivedFile(vararg refs: AttachmentRef) = ReceivedFile(
        id = "f1",
        timestampEpochMillis = 1_000L,
        payload = FilePayload(
            id = "f1",
            from = "desk",
            to = "*",
            sentAtEpochMillis = 900L,
            attachments = refs.toList(),
            postedAtEpochMillis = 900L,
        ),
    )

    private fun receivedNotification(senderIcon: AttachmentRef, id: String = "n1") = ReceivedNotification(
        id = id,
        timestampEpochMillis = 1_000L,
        payload = NotificationPayload(
            id = id,
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            packageName = "com.example.chat",
            appName = "Chat",
            title = "相手",
            text = "本文",
            notificationKey = "0|com.example.chat|1|null|10",
            postedAtEpochMillis = 900L,
            senderIcon = senderIcon,
        ),
    )

    /** [blobIds] の復号済みファイルを作り、blobId からそれを引く口を返す。 */
    private fun cachedFiles(vararg blobIds: String): (AttachmentRef) -> File? {
        val files = blobIds.associateWith { File(baseDir, it).apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        return { ref -> files[ref.blobId] }
    }

    private val thumbnail: ImageBitmap = ImageBitmap(1, 1)

    /** 復号済みファイルが在る添付だけが、デコードしたサムネイル付きの取得済みになる。 */
    @Test
    fun onlyAttachmentsWithCachedFileBecomeCached() {
        val states = AttachmentDownloadStates()
        val items: List<TimelineItem> = listOf(receivedFile(ref("blob-cached"), ref("blob-missing")))

        primeCachedAttachments(states, items, cachedFiles("blob-cached")) { _, _ -> thumbnail }

        val cached = states.stateOf("blob-cached")
        assertEquals(true, cached?.cached)
        assertEquals(TransferState.COMPLETED, cached?.progress?.state)
        assertSame(thumbnail, cached?.thumbnail)
        assertNull(states.stateOf("blob-missing"))
    }

    /** 進行中・失敗・キャンセル済みの状態を持つ blob は、復号済みファイルが在っても取得済みに書き換えない。 */
    @Test
    fun blobsWithStateAreNotOverwritten() {
        val states = AttachmentDownloadStates()
        states.markProgress("blob-running", transferredBytes = 512, totalBytes = 2048)
        states.markFailed("blob-failed", totalBytes = 2048)
        states.markCancelled("blob-cancelled")
        val items: List<TimelineItem> =
            listOf(receivedFile(ref("blob-running"), ref("blob-failed"), ref("blob-cancelled")))

        primeCachedAttachments(
            states,
            items,
            cachedFiles("blob-running", "blob-failed", "blob-cancelled"),
        ) { _, _ -> thumbnail }

        assertEquals(TransferState.RUNNING, states.stateOf("blob-running")?.progress?.state)
        assertEquals(512L, states.stateOf("blob-running")?.progress?.transferredBytes)
        assertEquals(TransferState.FAILED, states.stateOf("blob-failed")?.progress?.state)
        assertEquals(TransferState.CANCELLED, states.stateOf("blob-cancelled")?.progress?.state)
    }

    /** 通知の送信者アイコンも、添付カードの添付と同じく取得済みにする。 */
    @Test
    fun senderIconIsPrimed() {
        val states = AttachmentDownloadStates()
        val items: List<TimelineItem> = listOf(receivedNotification(senderIcon = ref("blob-icon")))

        primeCachedAttachments(states, items, cachedFiles("blob-icon")) { _, _ -> thumbnail }

        assertEquals(true, states.stateOf("blob-icon")?.cached)
    }

    /** 同じ送信者アイコンを参照する通知が複数あっても、デコードは 1 回だけ行う。 */
    @Test
    fun sharedSenderIconIsDecodedOnce() {
        val states = AttachmentDownloadStates()
        val icon = ref("blob-icon")
        val items: List<TimelineItem> = listOf(
            receivedNotification(senderIcon = icon, id = "n1"),
            receivedNotification(senderIcon = icon, id = "n2"),
        )
        var decodes = 0

        primeCachedAttachments(states, items, cachedFiles("blob-icon")) { _, _ ->
            decodes++
            thumbnail
        }

        assertEquals(1, decodes)
        assertEquals(true, states.stateOf("blob-icon")?.cached)
    }

    /** サムネイルにできなかった添付も、取得済みにはする（カードは種別アイコンで開く・保存を出す）。 */
    @Test
    fun undecodableAttachmentIsStillCached() {
        val states = AttachmentDownloadStates()
        val items: List<TimelineItem> = listOf(receivedFile(ref("blob-file", kind = AttachmentKind.FILE)))

        primeCachedAttachments(states, items, cachedFiles("blob-file")) { _, _ -> null }

        assertEquals(true, states.stateOf("blob-file")?.cached)
        assertNull(states.stateOf("blob-file")?.thumbnail)
    }

    /** 全文添付の本文を書いた復号済みファイルを作る。 */
    private fun textFile(text: String): File = File.createTempFile("full", ".txt", baseDir).apply { writeText(text) }

    /** 一度取得した本文は保持し、次からは取得し直さずにそれを返す。 */
    @Test
    fun retainedTextIsReturnedWithoutDownload() = runTest {
        val cache = FullTextCache()
        var downloads = 0
        val download: suspend (AttachmentRef) -> File = {
            downloads++
            textFile("全文の本文")
        }
        val fullText = ref("blob-text", kind = AttachmentKind.TEXT)

        val first = cache.fetch(fullText, download)
        val second = cache.fetch(fullText, download)

        assertEquals("全文の本文", first)
        assertEquals("全文の本文", second)
        assertEquals(1, downloads)
    }

    /** 宣言サイズが自動取得の上限を超える全文添付は、取得せずに null を返す。 */
    @Test
    fun overLimitIsSkippedWithoutDownload() = runTest {
        val cache = FullTextCache()
        var downloads = 0
        val oversized = ref("blob-text", kind = AttachmentKind.TEXT, sizeBytes = MAX_FULL_TEXT_ATTACHMENT_BYTES + 1)

        val text = cache.fetch(oversized) {
            downloads++
            textFile("届かないはずの本文")
        }

        assertNull(text)
        assertEquals(0, downloads)
    }

    /** 宣言サイズが自動取得の上限ちょうどの全文添付は取得する。 */
    @Test
    fun exactlyAtLimitIsFetched() = runTest {
        val cache = FullTextCache()
        val atLimit = ref("blob-text", kind = AttachmentKind.TEXT, sizeBytes = MAX_FULL_TEXT_ATTACHMENT_BYTES)

        val text = cache.fetch(atLimit) { textFile("上限ちょうどの本文") }

        assertEquals("上限ちょうどの本文", text)
    }

    /** 取得の失敗は null を返し、保持しないため次の表示で取り直す。 */
    @Test
    fun failureReturnsNullAndIsRetriedNextTime() = runTest {
        val cache = FullTextCache()
        val fullText = ref("blob-text", kind = AttachmentKind.TEXT)

        val failed = cache.fetch(fullText) { throw IOException("offline") }
        val retried = cache.fetch(fullText) { textFile("取り直した本文") }

        assertNull(failed)
        assertEquals("取り直した本文", retried)
    }

    /** 取得中にキャンセルされたら、null を返さずキャンセルとして呼び出し元へ伝える。 */
    @Test
    fun cancellationPropagates() = runTest {
        val cache = FullTextCache()
        val started = CompletableDeferred<Unit>()
        var result: String? = "not assigned"

        val job = launch {
            result = cache.fetch(ref("blob-text", kind = AttachmentKind.TEXT)) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()

        assertEquals("not assigned", result)
    }
}
