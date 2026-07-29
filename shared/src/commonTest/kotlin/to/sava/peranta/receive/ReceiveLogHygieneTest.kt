package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.MessagePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.TimelineFeed
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

/** コマンドの返信本文。ログに現れてはならない。 */
private const val REPLY_TEXT_MARKER = "reply-never-logged"

/** 添付ダウンロード先のホスト。ログに現れてはならない。 */
private const val HOST_MARKER = "blob-host-never-logged.example.com"

/** 購読する topic。推測されないことが前提の値なので、完全な形はログに現れてはならない。 */
private const val TOPIC_MARKER = "peranta-dev-desk-topicnevrlogged"

/**
 * 受信経路のログ衛生。通知・SMS・メッセージ・キャプション・返信本文と共有鍵と topic が、
 * どの severity のログ行にも（例外メッセージ経由でも）現れないことを固定する。
 */
class ReceiveLogHygieneTest {

    private val now = 10_000L
    private val deviceName = "desk"
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
        REPLY_TEXT_MARKER,
        HOST_MARKER,
        TOPIC_MARKER,
        Base64.encode(keyBytes),
    )

    private fun pipeline(commandExecutor: CommandExecutor? = null) = ReceivePipeline(
        FakeNtfyClient(),
        cipher,
        TimelineFeed(JsonlTimelineStore(FakeTimelineFile())),
        deviceName,
        commandExecutor = commandExecutor,
        log = recordingLogger(writer, "Receive"),
        now = { now },
    )

    /** 実行だけを見る no-op executor。実行の成否をログ衛生から切り離す。 */
    private class NoOpCommandExecutor : CommandExecutor {
        override suspend fun dismiss(notificationKey: String) {}
        override suspend fun invokeAction(notificationKey: String, actionIndex: Int) {}
        override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) {}
        override suspend fun muteApp(packageName: String) {}
        override suspend fun unmuteApp(packageName: String) {}
    }

    private fun notification(): NotificationPayload = NotificationPayload(
        id = "n1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        packageName = "com.example.bank",
        appName = "Bank",
        title = TITLE_MARKER,
        text = TEXT_MARKER,
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = now - 100,
    )

    private fun sms(): SmsPayload = SmsPayload(
        id = "s1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        senderNumber = "090-1111-2222",
        text = SMS_TEXT_MARKER,
        postedAtEpochMillis = now - 100,
    )

    private fun message(): MessagePayload = MessagePayload(
        id = "msg1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        text = MESSAGE_TEXT_MARKER,
    )

    private fun file(): FilePayload = FilePayload(
        id = "f1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
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
        postedAtEpochMillis = now - 100,
    )

    private fun replyCommand(actionIndex: Int? = 0): CommandPayload = CommandPayload(
        id = "cmd1",
        from = "phone",
        to = deviceName,
        sentAtEpochMillis = now,
        command = CommandType.REPLY,
        targetNotificationKey = "0|com.example.bank|1|null|10",
        actionIndex = actionIndex,
        replyText = REPLY_TEXT_MARKER,
    )

    private suspend fun eventFor(payload: Payload, sealCipher: MessageCipher = cipher): NtfyEvent =
        NtfyEvent(
            id = "e",
            time = now,
            topic = TOPIC_MARKER,
            message = encodeEnvelope(sealCipher.seal(payload)),
        )

    /** ログが出ていること、かつどの行にも目印が無いことを確かめる。 */
    private fun assertLoggedWithoutMarkers() {
        assertTrue(writer.recorded.isNotEmpty(), "no log line was recorded")
        val leaked = writer.recorded.filter { line -> markers.any { line.contains(it) } }
        assertTrue(leaked.isEmpty(), "secret appeared in log: $leaked")
    }

    /** 購読の開始を報せるログに完全な topic 名は出ない。 */
    @Test
    fun startedSubscriptionKeepsTopicOutOfLog() = runTest {
        pipeline().start(TOPIC_MARKER)
        assertLoggedWithoutMarkers()
    }

    /** 通知の受信でタイトル・本文はログに出ない。 */
    @Test
    fun receivedNotificationKeepsBodyOutOfLog() = runTest {
        pipeline().handleEvent(eventFor(notification()))
        assertLoggedWithoutMarkers()
    }

    /** SMS の受信で本文はログに出ない。 */
    @Test
    fun receivedSmsKeepsBodyOutOfLog() = runTest {
        pipeline().handleEvent(eventFor(sms()))
        assertLoggedWithoutMarkers()
    }

    /** メッセージの受信で本文はログに出ない。 */
    @Test
    fun receivedMessageKeepsBodyOutOfLog() = runTest {
        pipeline().handleEvent(eventFor(message()))
        assertLoggedWithoutMarkers()
    }

    /** ファイル転送の受信でキャプションと添付の取得先ホストはログに出ない。 */
    @Test
    fun receivedFileKeepsCaptionAndHostOutOfLog() = runTest {
        pipeline().handleEvent(eventFor(file()))
        assertLoggedWithoutMarkers()
    }

    /** 復号に失敗しても、失敗を報せるログに共有鍵は出ない。 */
    @Test
    fun decryptionFailureKeepsKeyOutOfLog() = runTest {
        val envelope = cipher.seal(notification())
        val bytes = Base64.decode(envelope.ciphertext).also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val tampered = envelope.copy(ciphertext = Base64.encode(bytes))

        pipeline().handleEvent(NtfyEvent("e", now, TOPIC_MARKER, encodeEnvelope(tampered)))

        assertLoggedWithoutMarkers()
    }

    /** keyId 不一致を報せるログに共有鍵は出ない。 */
    @Test
    fun keyIdMismatchKeepsKeyOutOfLog() = runTest {
        pipeline().handleEvent(eventFor(notification(), sealCipher = MessageCipher(keyBytes, "k2")))
        assertLoggedWithoutMarkers()
    }

    /** コマンドの実行で返信本文はログに出ない。 */
    @Test
    fun executedCommandKeepsReplyTextOutOfLog() = runTest {
        pipeline(commandExecutor = NoOpCommandExecutor()).handleEvent(eventFor(replyCommand()))
        assertLoggedWithoutMarkers()
    }

    /** 必須フィールドを欠いたコマンドの実行失敗を報せるログにも返信本文は出ない。 */
    @Test
    fun failedCommandKeepsReplyTextOutOfLog() = runTest {
        pipeline(commandExecutor = NoOpCommandExecutor())
            .handleEvent(eventFor(replyCommand(actionIndex = null)))
        assertLoggedWithoutMarkers()
    }
}
