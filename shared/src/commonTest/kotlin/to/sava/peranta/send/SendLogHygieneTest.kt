package to.sava.peranta.send

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyConnectionState
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.net.NtfyPublishException
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

/** 通知タイトル。ログに現れてはならない。 */
private const val TITLE_MARKER = "title-never-logged"

/** 通知本文。ログに現れてはならない。 */
private const val TEXT_MARKER = "text-never-logged"

/** SMS 本文。ログに現れてはならない。 */
private const val SMS_TEXT_MARKER = "sms-never-logged"

/** メッセージ本文。ログに現れてはならない。 */
private const val MESSAGE_TEXT_MARKER = "message-never-logged"

/** ファイル転送のキャプション。ログに現れてはならない。 */
private const val CAPTION_MARKER = "caption-never-logged"

/** 添付ダウンロード先のホスト。ログに現れてはならない。 */
private const val HOST_MARKER = "blob-host-never-logged.example.com"

/** リトライで回復し得ない HTTP ステータス。 */
private const val HTTP_BAD_REQUEST = 400

/** リトライで回復し得る HTTP ステータス。 */
private const val HTTP_SERVICE_UNAVAILABLE = 503

/**
 * 送信経路のログ衛生。通知・SMS・メッセージ・キャプションと共有鍵が、送信の成否によらず
 * どの severity のログ行にも（例外メッセージ経由でも）現れないことを固定する。
 */
class SendLogHygieneTest {

    private val now = 5_000L
    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")
    private val writer = RecordingLogWriter()

    /** ログ行に現れてはならない文字列。 */
    private val markers = listOf(
        TITLE_MARKER,
        TEXT_MARKER,
        SMS_TEXT_MARKER,
        MESSAGE_TEXT_MARKER,
        CAPTION_MARKER,
        HOST_MARKER,
        Base64.encode(keyBytes),
    )

    private fun pipeline(
        ntfy: NtfyClient = FakeNtfyClient(),
        store: TimelineStore = FakeTimelineStore(),
    ) = SendPipeline(cipher, ntfy, store, log = recordingLogger(writer, "Send"), now = { now })

    private fun notification(): NotificationPayload = NotificationPayload(
        id = "n1",
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = now,
        packageName = "com.example.bank",
        appName = "Bank",
        title = TITLE_MARKER,
        text = TEXT_MARKER,
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = now,
    )

    private fun sms(): SmsPayload = SmsPayload(
        id = "s1",
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = now,
        senderNumber = "090-1111-2222",
        text = SMS_TEXT_MARKER,
        postedAtEpochMillis = now,
    )

    private fun message(): MessagePayload = MessagePayload(
        id = "msg1",
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = now,
        text = MESSAGE_TEXT_MARKER,
    )

    private fun file(): FilePayload = FilePayload(
        id = "f1",
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = now,
        caption = CAPTION_MARKER,
        attachments = listOf(
            AttachmentRef(
                blobId = "blob-1",
                url = "https://$HOST_MARKER/file/abc",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 2048,
                kind = AttachmentKind.IMAGE,
                enc = BlobEnc(
                    keyId = "k1",
                    saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
                    chunkSize = 1_048_576,
                    totalChunks = 1,
                ),
            ),
        ),
        postedAtEpochMillis = now,
    )

    /** publish が [status] で失敗するクライアント。例外メッセージには目印を含めない。 */
    private class RejectingNtfyClient(private val status: Int) : NtfyClient {
        override val connectionState: StateFlow<NtfyConnectionState> =
            MutableStateFlow(NtfyConnectionState.DISCONNECTED).asStateFlow()

        override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
            throw NtfyPublishException(status, "publish rejected")
        }

        override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()
    }

    /** append が必ず失敗する [TimelineStore]。記録経路の失敗ログを起こす。 */
    private class FailingTimelineStore : TimelineStore {
        override suspend fun append(item: TimelineItem): Unit = throw IllegalStateException("store unavailable")

        override suspend fun loadAll(): List<TimelineItem> = emptyList()

        override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {}
    }

    private suspend fun dispatch(payload: Payload, ntfy: NtfyClient, topics: List<String> = listOf("topic")) {
        pipeline(ntfy).dispatch(payload, topics, persistSensitive = false) { _, _, _, _ -> }
    }

    /** ログが出ていること、かつどの行にも目印が無いことを確かめる。 */
    private fun assertLoggedWithoutMarkers() {
        assertTrue(writer.recorded.isNotEmpty(), "no log line was recorded")
        val leaked = writer.recorded.filter { line -> markers.any { line.contains(it) } }
        assertTrue(leaked.isEmpty(), "secret appeared in log: $leaked")
    }

    /** 通知の送信でタイトル・本文と共有鍵はログに出ない。 */
    @Test
    fun sentNotificationKeepsBodyOutOfLog() = runTest {
        pipeline().send(notification(), listOf("topic"))
        assertLoggedWithoutMarkers()
    }

    /** SMS の送信で本文はログに出ない。 */
    @Test
    fun sentSmsKeepsBodyOutOfLog() = runTest {
        pipeline().send(sms(), listOf("topic"))
        assertLoggedWithoutMarkers()
    }

    /** メッセージの送信で本文はログに出ない。 */
    @Test
    fun sentMessageKeepsBodyOutOfLog() = runTest {
        pipeline().send(message(), listOf("topic"))
        assertLoggedWithoutMarkers()
    }

    /** ファイル転送の送信でキャプションと添付の取得先ホストはログに出ない。 */
    @Test
    fun sentFileKeepsCaptionAndHostOutOfLog() = runTest {
        pipeline().send(file(), listOf("topic"))
        assertLoggedWithoutMarkers()
    }

    /** 再送へ回す失敗を報せるログに本文は出ない。 */
    @Test
    fun retriedDispatchKeepsBodyOutOfLog() = runTest {
        dispatch(notification(), RejectingNtfyClient(HTTP_SERVICE_UNAVAILABLE))
        assertLoggedWithoutMarkers()
    }

    /** 配送先が解決できず再送へ回すときのログにも本文は出ない。 */
    @Test
    fun dispatchWithoutTopicsKeepsBodyOutOfLog() = runTest {
        dispatch(notification(), FakeNtfyClient(), topics = emptyList())
        assertLoggedWithoutMarkers()
    }

    /** 送信が拒否されたことを報せるログに本文は出ない。 */
    @Test
    fun rejectedDispatchKeepsBodyOutOfLog() = runTest {
        dispatch(sms(), RejectingNtfyClient(HTTP_BAD_REQUEST))
        assertLoggedWithoutMarkers()
    }

    /** 履歴への記録そのものが失敗したときのログにも本文は出ない。 */
    @Test
    fun failedRecordingKeepsBodyOutOfLog() = runTest {
        pipeline(store = FailingTimelineStore())
            .dispatch(notification(), listOf("topic"), persistSensitive = false) { _, _, _, _ -> }
        assertLoggedWithoutMarkers()
    }
}
