package to.sava.peranta.send

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.MAX_MESSAGE_TEXT_BYTES
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyConnectionState
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * メッセージ送信ヘルパ（[buildMessagePayload] / [sendMessage]）を検証する（§4.1）。
 */
class MessageForwardingTest {

    private fun cipher() = MessageCipher(generateKey(), "k1")

    private fun config(
        deviceId: String = "phone",
        deviceName: String? = "スマホ",
        deliveryTopics: List<String> = listOf("topic-a"),
        controlTopic: String? = null,
    ) = PerantaConfig(
        deviceId = deviceId,
        deviceName = deviceName,
        deliveryTopics = deliveryTopics,
        controlTopic = controlTopic,
    )

    /** 予算以下の本文はそのまま、超過分はバイト予算内へ切り詰められる。 */
    @Test
    fun buildMessagePayloadTruncatesToByteBudget() {
        val short = buildMessagePayload("phone", "こんにちは", now = 1000)
        assertEquals("こんにちは", short.text)

        val long = "あ".repeat(MAX_MESSAGE_TEXT_BYTES)
        val truncated = buildMessagePayload("phone", long, now = 1000)
        assertTrue(truncated.text.encodeToByteArray().size <= MAX_MESSAGE_TEXT_BYTES)
        assertTrue(truncated.text.endsWith("…"))
    }

    /** 切り詰めはコードポイント境界で行われ、サロゲートペア（絵文字）を分断しない。 */
    @Test
    fun buildMessagePayloadKeepsSurrogatePairsIntact() {
        val emoji = "😀"
        val payload = buildMessagePayload("phone", emoji.repeat(1000), now = 1000)
        val withoutEllipsis = payload.text.removeSuffix("…")
        assertEquals(0, withoutEllipsis.length % emoji.length)
        assertTrue(withoutEllipsis.chunked(emoji.length).all { it == emoji })
    }

    /** 宛先・送信元・fromName が正しく組まれる。 */
    @Test
    fun buildMessagePayloadSetsEnvelopeFields() {
        val payload = buildMessagePayload("phone", "やあ", now = 4000, deviceName = "スマホ", idGen = { "id-1" })
        assertEquals("id-1", payload.id)
        assertEquals("phone", payload.from)
        assertEquals(BROADCAST_TARGET, payload.to)
        assertEquals(4000, payload.sentAtEpochMillis)
        assertEquals("スマホ", payload.fromName)
    }

    /** 送信成功時は SentNotification が feed へ即時反映される。 */
    @Test
    fun sendMessageRecordsSentNotificationOnSuccess() = runTest {
        val c = cipher()
        val ntfy = FakeNtfyClient()
        val feed = TimelineFeed(FakeTimelineStore())
        val pipeline = SendPipeline(c, ntfy, feed, now = { 5000 })

        val delivered = sendMessage(config(), c, ntfy, pipeline, "テストメッセージ")

        assertTrue(delivered)
        assertEquals(listOf("topic-a"), ntfy.published.map { it.topic })
        val recorded = feed.items.value.single() as SentNotification
        assertEquals(5000, recorded.timestampEpochMillis)
    }

    /** 配送先が解決できない（topics 空）ときは ErrorItem を記録して false を返す。 */
    @Test
    fun sendMessageRecordsErrorWhenTopicsUnresolved() = runTest {
        val c = cipher()
        val ntfy = FakeNtfyClient()
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(c, ntfy, store)

        val delivered = sendMessage(config(deliveryTopics = emptyList()), c, ntfy, pipeline, "本文")

        assertFalse(delivered)
        assertTrue(ntfy.published.isEmpty())
        assertEquals(MESSAGE_SEND_FAILED_MESSAGE, (store.appended.single() as ErrorItem).message)
    }

    /** publish が例外を送出したときも ErrorItem を記録して false を返す（自動再送はしない）。 */
    @Test
    fun sendMessageRecordsErrorWhenPublishFails() = runTest {
        val c = cipher()
        val ntfy = FailingNtfyClient()
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(c, ntfy, store)

        val delivered = sendMessage(config(), c, ntfy, pipeline, "本文")

        assertFalse(delivered)
        assertEquals(MESSAGE_SEND_FAILED_MESSAGE, (store.appended.single() as ErrorItem).message)
    }

    /** publish の例外メッセージに接続先 URL（完全な topic）があっても、完全な topic も本文もログに出ない。 */
    @Test
    fun sendMessageKeepsTopicInExceptionOutOfLog() = runTest {
        val leakedTopic = "topic-never-logged"
        val c = cipher()
        val ntfy = FailingNtfyClient("Request timeout has expired [url=https://h/$leakedTopic]")
        val pipeline = SendPipeline(c, ntfy, FakeTimelineStore())
        val writer = RecordingLogWriter()

        val delivered = sendMessage(
            config(deliveryTopics = listOf(leakedTopic)), c, ntfy, pipeline, "text-never-logged",
            log = recordingLogger(writer, "MessageSend"),
        )

        assertFalse(delivered)
        assertTrue(writer.recorded.isNotEmpty(), "no log line was recorded")
        val leaked = writer.recorded.filter { it.contains(leakedTopic) || it.contains("text-never-logged") }
        assertTrue(leaked.isEmpty(), "secret appeared in log: $leaked")
    }

    private class FailingNtfyClient(private val failureMessage: String = "publish failed") : NtfyClient {
        override val connectionState: StateFlow<NtfyConnectionState> =
            MutableStateFlow(NtfyConnectionState.DISCONNECTED).asStateFlow()

        override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
            throw IllegalStateException(failureMessage)
        }

        override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()
    }
}
