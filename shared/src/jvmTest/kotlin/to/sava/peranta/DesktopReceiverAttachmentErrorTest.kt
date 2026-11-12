package to.sava.peranta

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.sava.peranta.blob.AttachmentUploadRequest
import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.blob.BlobTransport
import to.sava.peranta.blob.FakeBlobTransport
import to.sava.peranta.blob.TransferState
import to.sava.peranta.blob.UploadedBlob
import to.sava.peranta.blob.uploadAttachment
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.sharedKeyBytes
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.send.FakeTimelineStore
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.toast.ReceivedNotificationToast
import to.sava.peranta.toast.ToastResult
import to.sava.peranta.toast.Toaster
import to.sava.peranta.ui.ATTACHMENT_DOWNLOAD_FAILED_MESSAGE
import to.sava.peranta.ui.ATTACHMENT_NO_VIEWER_MESSAGE
import to.sava.peranta.ui.ATTACHMENT_SAVE_FAILED_MESSAGE
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 積まれた処理が尽きるのを待つ上限。進まないまま止まり続けるのを防ぐための値で、通常はすぐ終わる。 */
private const val DRAIN_TIMEOUT_MILLIS: Long = 10_000L

/**
 * Desktop の添付の操作の失敗を、タイムラインへ記録するかどうか（§4.3・§10.1）。
 * 記録するのは利用者が添付カードのボタンで始めた操作（ダウンロード・開く・保存）の失敗だけで、自動取得
 * （画像の自動表示・送信者アイコン・トーストへの画像の差し込み）の失敗と利用者によるキャンセルは記録しない。
 * トーストは出さない。
 *
 * タイムラインはメモリ、添付の置き場は一時ディレクトリ、blob の転送と OS への受け渡しはフェイクに差し替え、
 * アプリデータの領域・ネットワーク・実際のアプリやダイアログには触れない。受信機の処理は 1 本のスレッドで順に動かし、
 * 確かめる前に積まれた処理が尽きるのを待つ（[SerialTestDispatcher.drain]）。順序は合図で固定し、時間待ちに頼らない。
 */
class DesktopReceiverAttachmentErrorTest {

    private val attachmentsDir: File = Files.createTempDirectory("peranta-receiver-attach-test").toFile()
    private val serial = SerialTestDispatcher()
    private val config = PerantaConfig(
        deviceId = "recv-device",
        sharedKeyBase64 = Base64.encode(generateKey()),
        keyId = "k1",
        controlTopic = null,
    )
    private val toaster = RecordingToaster()
    private val receivers = mutableListOf<DesktopReceiver>()

    @AfterTest
    fun cleanup() {
        runBlocking { receivers.forEach { it.close() } }
        serial.close()
        attachmentsDir.deleteRecursively()
    }

    /**
     * 受信機の処理を 1 本のスレッドで順に動かすディスパッチャ。[drain] は、それまでに積まれた処理と、
     * その処理が続けて積んだ処理が尽きるまで待つ。処理がスレッドを塞いで進まないときは、
     * [DRAIN_TIMEOUT_MILLIS] で打ち切ってテストを失敗させる。
     */
    private class SerialTestDispatcher : AutoCloseable {
        private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

        fun drain() {
            do {
                runBlocking { withTimeout(DRAIN_TIMEOUT_MILLIS) { withContext(dispatcher) {} } }
            } while (executor.queue.isNotEmpty() || executor.activeCount > 0)
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    /** 表示したトーストの id を記録するだけのフェイク。 */
    private class RecordingToaster : Toaster {
        val shownIds = ConcurrentLinkedQueue<String>()
        override suspend fun show(item: ReceivedNotificationToast): ToastResult {
            shownIds.add(item.id)
            return ToastResult.Dismissed
        }
        override suspend fun close(id: String) = Unit
        override suspend fun update(item: ReceivedNotificationToast) = Unit
    }

    /** ダウンロードだけを持つ [BlobTransport]。アップロードは使わない。 */
    private abstract class DownloadOnlyTransport : BlobTransport {
        override suspend fun upload(
            topic: String,
            blobId: String,
            contentLength: Long,
            writeBody: suspend (ByteWriteChannel) -> Unit,
        ): UploadedBlob = error("not used")
    }

    /** [release] を待ってから失敗する転送。 */
    private class HeldFailingTransport : DownloadOnlyTransport() {
        val release = CompletableDeferred<Unit>()
        override suspend fun download(url: String, blobId: String, consume: suspend (ByteReadChannel) -> Unit) {
            release.await()
            throw IOException("offline")
        }
    }

    /**
     * キャンセルを待ち、キャンセルされてから [source] の本体を最後まで渡して止まる転送。
     * キャンセルの直後に一時ファイルへの書き出しが進捗を書く状況を作る。渡し終えたら [delivered] を完了する。
     */
    private class LateProgressTransport(private val source: BlobTransport) : DownloadOnlyTransport() {
        val delivered = CompletableDeferred<Unit>()
        override suspend fun download(url: String, blobId: String, consume: suspend (ByteReadChannel) -> Unit) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    source.download(url, blobId, consume)
                    delivered.complete(Unit)
                }
            }
        }
    }

    /**
     * 1 回目の取得はキャンセルを待ち、キャンセルされても [releaseFirst] までは止まり切らない。
     * 2 回目の取得は [releaseSecond] を待ってから失敗し、3 回目以降はすぐに失敗する。始めた回数を [calls] に数える。
     */
    private class StaggeredTransport : DownloadOnlyTransport() {
        val calls = AtomicInteger(0)
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        override suspend fun download(url: String, blobId: String, consume: suspend (ByteReadChannel) -> Unit) {
            when (calls.incrementAndGet()) {
                1 -> try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseFirst.await() }
                }
                2 -> {
                    releaseSecond.await()
                    throw IOException("offline")
                }
                else -> throw IOException("offline")
            }
        }
    }

    private fun receiver(
        transport: BlobTransport = FakeBlobTransport(),
        feed: TimelineFeed = TimelineFeed(FakeTimelineStore()),
        openFile: (File) -> Unit = { error("not expected to open") },
        chooseSaveDestination: (String) -> File? = { error("not expected to save") },
    ) = testDesktopReceiver(
        attachmentsDir = attachmentsDir,
        config = config,
        toaster = toaster,
        feed = feed,
        transport = transport,
        dispatcher = serial.dispatcher,
        openFile = openFile,
        chooseSaveDestination = chooseSaveDestination,
    ).also { receivers.add(it) }

    private fun imageRef(blobId: String = "blob-1") = AttachmentRef(
        blobId = blobId,
        url = "https://ntfy.example/file/$blobId",
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        kind = AttachmentKind.IMAGE,
        enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
    )

    private fun notification(attachments: List<AttachmentRef>, revision: Int) = ReceivedNotification(
        id = "notif-1",
        timestampEpochMillis = 1000L,
        payload = NotificationPayload(
            id = "notif-1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 1000L,
            packageName = "com.example.chat",
            appName = "Chat",
            title = "相手",
            text = "写真を送りました",
            notificationKey = "0|com.example.chat|1|null|10",
            postedAtEpochMillis = 1000L,
            attachments = attachments,
            revision = revision,
        ),
    )

    /** [ref] を添付に持つ受信ファイルが載ったタイムライン。 */
    private fun timelineWith(ref: AttachmentRef): TimelineFeed = TimelineFeed(FakeTimelineStore()).also { feed ->
        runBlocking {
            feed.record(
                ReceivedFile(
                    id = "file-1",
                    timestampEpochMillis = 1000L,
                    payload = FilePayload(
                        id = "file-1",
                        from = "phone",
                        to = "*",
                        sentAtEpochMillis = 1000L,
                        attachments = listOf(ref),
                        postedAtEpochMillis = 1000L,
                    ),
                ),
            )
        }
    }

    /** [ref] の復号済みファイルを、受信機の添付の置き場に作る。 */
    private fun cacheFileOf(ref: AttachmentRef): File =
        File(attachmentsDir, "${ref.blobId}/${ref.fileName}").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    private fun DesktopReceiver.errors(): List<ErrorItem> = items.value.filterIsInstance<ErrorItem>()

    private fun DesktopReceiver.stateOf(blobId: String): TransferState? =
        attachmentUi().states.value[blobId]?.progress?.state

    /** ボタンで始めた取得の失敗は、ダウンロードの失敗としてタイムラインへ記録し、トーストは出さない。 */
    @Test
    fun buttonDownloadFailureIsRecorded() {
        val receiver = receiver()

        receiver.attachmentUi().onDownload(imageRef())
        serial.drain()

        assertEquals(TransferState.FAILED, receiver.stateOf("blob-1"))
        val errors = receiver.errors()
        assertEquals(listOf(ATTACHMENT_DOWNLOAD_FAILED_MESSAGE), errors.map { it.message })
        assertEquals(ErrorKind.OTHER, errors.single().kind)
        assertTrue(toaster.shownIds.isEmpty())
    }

    /** 自動取得の口（画像の自動表示・送信者アイコン）から始めた取得の失敗は記録しない。 */
    @Test
    fun autoFetchFailureIsNotRecorded() {
        val receiver = receiver()

        receiver.attachmentUi().onAutoFetch(imageRef())
        serial.drain()

        assertEquals(TransferState.FAILED, receiver.stateOf("blob-1"))
        assertTrue(receiver.errors().isEmpty())
    }

    /** 改版で届いた画像をトーストへ差し込むための取得の失敗は記録しない（§4.3.1）。 */
    @Test
    fun toastImageFetchFailureIsNotRecorded() {
        val receiver = receiver()

        receiver.handleUpdated(
            notification(attachments = emptyList(), revision = 0),
            notification(attachments = listOf(imageRef()), revision = 1),
        )
        serial.drain()

        assertEquals(TransferState.FAILED, receiver.stateOf("blob-1"))
        assertTrue(receiver.errors().isEmpty())
    }

    /** 自動取得が進行中の blob にボタンが押されたら、その取得の失敗は利用者が求めた取得として記録する。 */
    @Test
    fun buttonPressedDuringAutoFetchIsRecorded() {
        val transport = HeldFailingTransport()
        val receiver = receiver(transport)
        val ui = receiver.attachmentUi()

        ui.onAutoFetch(imageRef())
        serial.drain()
        ui.onDownload(imageRef())
        transport.release.complete(Unit)
        serial.drain()

        assertEquals(TransferState.FAILED, receiver.stateOf("blob-1"))
        assertEquals(listOf(ATTACHMENT_DOWNLOAD_FAILED_MESSAGE), receiver.errors().map { it.message })
    }

    /**
     * 利用者がキャンセルした取得は記録しない。キャンセルの直後に一時ファイルへの書き出しが進捗を書いても、
     * 取得が止まった時点でキャンセル済みに戻り、カードが進行中の表示へ戻らない。
     */
    @Test
    fun cancelledDownloadIsNotRecordedAndDoesNotReturnToRunning() {
        val source = FakeBlobTransport()
        val ref = runBlocking { uploadImage(source) }
        val transport = LateProgressTransport(source)
        val receiver = receiver(transport)
        val ui = receiver.attachmentUi()

        ui.onDownload(ref)
        serial.drain()
        ui.onCancel(ref.blobId)
        serial.drain()

        assertTrue(transport.delivered.isCompleted)
        assertEquals(TransferState.CANCELLED, receiver.stateOf(ref.blobId))
        assertTrue(receiver.errors().isEmpty())
    }

    /**
     * キャンセルした取得が止まり切る前にボタンを押し直したとき、2 本目の取得は 1 本目の後始末に影響されない。
     * 1 本目が止まっても 2 本目の進行中の表示は未取得へ戻らず、2 本目の登録も残る（自動取得は 2 本目を使う）。
     * 2 本目が失敗したら、利用者が求めた取得の失敗として記録する。
     */
    @Test
    fun downloadRestartedBeforeCancelledOneStopsIsTrackedOnItsOwn() {
        val transport = StaggeredTransport()
        val receiver = receiver(transport)
        val ui = receiver.attachmentUi()
        val ref = imageRef()

        ui.onDownload(ref)
        serial.drain()
        ui.onCancel(ref.blobId)
        serial.drain()
        ui.onDownload(ref)
        serial.drain()
        assertEquals(2, transport.calls.get())

        transport.releaseFirst.complete(Unit)
        serial.drain()
        assertEquals(TransferState.RUNNING, receiver.stateOf(ref.blobId))
        ui.onAutoFetch(ref)
        serial.drain()
        assertEquals(2, transport.calls.get())

        transport.releaseSecond.complete(Unit)
        serial.drain()
        assertEquals(TransferState.FAILED, receiver.stateOf(ref.blobId))
        assertEquals(listOf(ATTACHMENT_DOWNLOAD_FAILED_MESSAGE), receiver.errors().map { it.message })
    }

    /** 受信機を閉じて止まった取得は記録せず、キャンセル済みで終える。 */
    @Test
    fun closingReceiverStopsDownloadWithoutRecording() {
        val receiver = receiver(HeldFailingTransport())

        receiver.attachmentUi().onDownload(imageRef())
        serial.drain()
        receivers.remove(receiver)
        runBlocking { receiver.close() }

        assertEquals(TransferState.CANCELLED, receiver.stateOf("blob-1"))
        assertTrue(receiver.errors().isEmpty())
    }

    /** タイムラインの添付を開けなかったときは、開けるアプリが無い旨を OTHER で 1 件記録し、トーストは出さない。 */
    @Test
    fun openFailureIsRecordedOnTimeline() {
        val ref = imageRef()
        val receiver = receiver(feed = timelineWith(ref), openFile = { throw IOException("no association") })
        cacheFileOf(ref)

        receiver.attachmentUi().onOpen(ref.blobId)
        serial.drain()

        val errors = receiver.errors()
        assertEquals(listOf(ATTACHMENT_NO_VIEWER_MESSAGE), errors.map { it.message })
        assertEquals(ErrorKind.OTHER, errors.single().kind)
        assertTrue(toaster.shownIds.isEmpty())
    }

    /** タイムラインの添付を保存できなかったときは、保存の失敗を OTHER で 1 件記録し、トーストは出さない。 */
    @Test
    fun saveFailureIsRecordedOnTimeline() {
        val ref = imageRef()
        val receiver = receiver(
            feed = timelineWith(ref),
            chooseSaveDestination = { throw IllegalStateException("no display") },
        )
        cacheFileOf(ref)

        receiver.attachmentUi().onSave(ref.blobId)
        serial.drain()

        val errors = receiver.errors()
        assertEquals(listOf(ATTACHMENT_SAVE_FAILED_MESSAGE), errors.map { it.message })
        assertEquals(ErrorKind.OTHER, errors.single().kind)
        assertTrue(toaster.shownIds.isEmpty())
    }

    /** 受信機を閉じた後の開く・保存は、OS へ渡さず、何も記録しない。 */
    @Test
    fun nothingIsRecordedAfterClose() {
        val ref = imageRef()
        val handedToOs = ConcurrentLinkedQueue<String>()
        val receiver = receiver(
            feed = timelineWith(ref),
            openFile = { handedToOs.add("open") },
            chooseSaveDestination = {
                handedToOs.add("save")
                null
            },
        )
        cacheFileOf(ref)
        val ui = receiver.attachmentUi()
        receivers.remove(receiver)
        runBlocking { receiver.close() }

        ui.onOpen(ref.blobId)
        ui.onSave(ref.blobId)
        serial.drain()

        assertTrue(handedToOs.isEmpty())
        assertTrue(receiver.errors().isEmpty())
    }

    /** 受信機の共有鍵で暗号化した画像を [transport] へ置き、その参照を返す。 */
    private suspend fun uploadImage(transport: FakeBlobTransport): AttachmentRef {
        val plain = Random(7).nextBytes(4096)
        return uploadAttachment(
            transport = transport,
            blobCipher = BlobCipher(config.sharedKeyBytes(), "k1"),
            blobTopic = "blob-topic",
            request = AttachmentUploadRequest(
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = plain.size.toLong(),
                kind = AttachmentKind.IMAGE,
                openSource = { ByteReadChannel(plain) },
            ),
            newBlobId = { "blob-1" },
        )
    }
}
