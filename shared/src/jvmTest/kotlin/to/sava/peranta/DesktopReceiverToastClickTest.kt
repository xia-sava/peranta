package to.sava.peranta

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.toast.ReceivedNotificationToast
import to.sava.peranta.toast.ToastResult
import to.sava.peranta.toast.Toaster
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 受信ファイル・受信メッセージ・エラーのトーストクリックが前面化＋スクロール（onToastClicked）へ
 * 配線されていることを検証する（§3.3）。「消す」ボタン押下（ButtonDismiss）は何もしないことも確認する。
 */
class DesktopReceiverToastClickTest {

    /** [result] を即答するフェイク。表示内容は [shown] に記録する。 */
    private class FixedResultToaster(private val result: ToastResult) : Toaster {
        @Volatile
        var shown: ReceivedNotificationToast? = null
        override suspend fun show(item: ReceivedNotificationToast): ToastResult {
            shown = item
            return result
        }
        override suspend fun close(id: String) = Unit
        override suspend fun update(item: ReceivedNotificationToast) = Unit
    }

    private fun testConfig(): PerantaConfig = PerantaConfig(
        deviceId = "recv-device",
        sharedKeyBase64 = Base64.encode(generateKey()),
        keyId = "k1",
        controlTopic = null,
    )

    private fun receiver(toaster: Toaster, clicked: ConcurrentLinkedQueue<String>) = DesktopReceiver(
        config = testConfig(),
        repository = ConfigRepository(MapSettings()),
        toaster = toaster,
        onToastClicked = { clicked.add(it) },
    )

    private val filePayload = FilePayload(
        id = "file-1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1000L,
        attachments = listOf(
            AttachmentRef(
                blobId = "blob-1",
                url = "https://peranta.sava.to/file/abc",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 2048,
                kind = AttachmentKind.IMAGE,
                enc = BlobEnc(keyId = "k1", saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==", chunkSize = 1_048_576, totalChunks = 1),
            ),
        ),
        postedAtEpochMillis = 1000L,
    )

    private val messagePayload = MessagePayload(
        id = "msg-1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1000L,
        text = "hello",
    )

    /** クリックした結果が非同期で反映されるまで待つ。届かなければタイムアウトで失敗する。 */
    private fun awaitClicked(clicked: ConcurrentLinkedQueue<String>) = runBlocking {
        withTimeout(5_000) {
            while (clicked.isEmpty()) delay(20)
        }
    }

    /** トースト表示が完了する（[FixedResultToaster.shown] が埋まる）まで待ってから、少し猶予を置く。 */
    private fun awaitShownThenSettle(toaster: FixedResultToaster) = runBlocking {
        withTimeout(1_000) {
            while (toaster.shown == null) delay(20)
        }
        delay(50)
    }

    @Test
    fun receivedFileToastClickTriggersOnToastClicked() {
        val clicked = ConcurrentLinkedQueue<String>()
        val receiver = receiver(FixedResultToaster(ToastResult.Clicked), clicked)
        try {
            receiver.handleAppended(ReceivedFile(id = "file-1", timestampEpochMillis = 1000L, payload = filePayload))
            awaitClicked(clicked)
            assertEquals(listOf("file-1"), clicked.toList())
        } finally {
            runBlocking { receiver.close() }
        }
    }

    @Test
    fun receivedMessageToastClickTriggersOnToastClicked() {
        val clicked = ConcurrentLinkedQueue<String>()
        val receiver = receiver(FixedResultToaster(ToastResult.Clicked), clicked)
        try {
            receiver.handleAppended(ReceivedMessage(id = "msg-1", timestampEpochMillis = 1000L, payload = messagePayload))
            awaitClicked(clicked)
            assertEquals(listOf("msg-1"), clicked.toList())
        } finally {
            runBlocking { receiver.close() }
        }
    }

    @Test
    fun errorItemToastClickTriggersOnToastClicked() {
        val clicked = ConcurrentLinkedQueue<String>()
        val receiver = receiver(FixedResultToaster(ToastResult.Clicked), clicked)
        try {
            receiver.handleAppended(
                ErrorItem(id = "err-1", timestampEpochMillis = 1000L, message = "boom", kind = ErrorKind.OTHER),
            )
            awaitClicked(clicked)
            assertEquals(listOf("err-1"), clicked.toList())
        } finally {
            runBlocking { receiver.close() }
        }
    }

    /** 「消す」ボタン押下（ButtonDismiss）はメッセージ・ファイルトーストでは何もしない（§3.3）。 */
    @Test
    fun receivedFileToastButtonDismissDoesNothing() {
        val clicked = ConcurrentLinkedQueue<String>()
        val toaster = FixedResultToaster(ToastResult.ButtonDismiss)
        val receiver = receiver(toaster, clicked)
        try {
            receiver.handleAppended(ReceivedFile(id = "file-2", timestampEpochMillis = 1000L, payload = filePayload))
            awaitShownThenSettle(toaster)
            assertTrue(clicked.isEmpty())
        } finally {
            runBlocking { receiver.close() }
        }
    }
}
