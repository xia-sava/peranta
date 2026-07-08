package to.sava.peranta.send

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyConnectionState
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.net.NtfyPublishException
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SendDispatchTest {

    private fun cipher() = MessageCipher(generateKey(), "k1")

    private fun payload() = NotificationPayload(
        id = newPayloadId(),
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 1000,
        packageName = "com.example.chat",
        appName = "Chat",
        title = "こんにちは",
        text = "ふつうのメッセージ",
        notificationKey = "0|com.example.chat|1|null|10",
        postedAtEpochMillis = 1000,
        priority = Priority.NORMAL,
    )

    /** 送信成功なら送信済みを記録し、再送は投入しない。 */
    @Test
    fun deliversAndRecordsSent() = runTest {
        val ntfy = ControlledNtfyClient()
        val store = RecordingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store, now = { 5000 })
        val enqueued = mutableListOf<String>()

        val delivered = pipeline.dispatch(payload(), listOf("a", "b"), persistSensitive = true) { body, _, _, _ ->
            enqueued.add(body)
        }

        assertTrue(delivered)
        assertEquals(listOf("a", "b"), ntfy.published)
        assertTrue(enqueued.isEmpty())
        assertTrue(store.appended.single() is SentNotification)
    }

    /** 5xx は再送に回し、送信済みも ErrorItem も記録しない。 */
    @Test
    fun serverErrorEnqueuesRetry() = runTest {
        val ntfy = ControlledNtfyClient { throw NtfyPublishException(503, "unavailable") }
        val store = RecordingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store)
        val enqueued = mutableListOf<Triple<String, List<String>, Int?>>()

        val delivered = pipeline.dispatch(payload(), listOf("a"), persistSensitive = true) { body, topics, cache, _ ->
            enqueued.add(Triple(body, topics, cache))
        }

        assertFalse(delivered)
        assertEquals(1, enqueued.size)
        assertEquals(listOf("a"), enqueued.single().second)
        assertTrue(store.appended.isEmpty())
    }

    /** 再送に回すとき、封筒と一緒に本文を含まない表示メタを渡す。 */
    @Test
    fun retryCarriesDisplayMetaWithoutBody() = runTest {
        val ntfy = ControlledNtfyClient { throw NtfyPublishException(503, "unavailable") }
        val store = RecordingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store)
        var captured: RetryDisplayMeta? = null
        val sent = payload()

        pipeline.dispatch(sent, listOf("a"), persistSensitive = true) { _, _, _, meta ->
            captured = meta
        }

        val meta = assertNotNull(captured)
        assertEquals(sent.id, meta.id)
        assertEquals(RetryDisplayKind.NOTIFICATION, meta.kind)
        assertFalse(encodeRetryDisplayMeta(meta).contains(sent.text))
    }

    /** 4xx は即諦めて ErrorItem を記録し、再送に回さない。 */
    @Test
    fun clientErrorRecordsErrorAndDoesNotRetry() = runTest {
        val ntfy = ControlledNtfyClient { throw NtfyPublishException(413, "too large") }
        val store = RecordingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store)
        var enqueueCalled = false

        val delivered = pipeline.dispatch(payload(), listOf("a"), persistSensitive = true) { _, _, _, _ ->
            enqueueCalled = true
        }

        assertFalse(delivered)
        assertFalse(enqueueCalled)
        assertEquals(SEND_REJECTED_MESSAGE, (store.appended.single() as ErrorItem).message)
    }

    /** ネットワーク例外は再送に回す。 */
    @Test
    fun networkErrorEnqueuesRetry() = runTest {
        val ntfy = ControlledNtfyClient { throw RuntimeException("connection reset") }
        val store = RecordingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store)
        var enqueueCalled = false

        val delivered = pipeline.dispatch(payload(), listOf("a"), persistSensitive = true) { _, _, _, _ ->
            enqueueCalled = true
        }

        assertFalse(delivered)
        assertTrue(enqueueCalled)
        assertTrue(store.appended.isEmpty())
    }

    /** publish が時間枠を超えたら即時送信を諦めて再送に回す。 */
    @Test
    fun timeoutEnqueuesRetry() = runTest {
        val ntfy = ControlledNtfyClient { delay(20_000) }
        val store = RecordingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store)
        var enqueueCalled = false

        val delivered = pipeline.dispatch(
            payload(),
            listOf("a"),
            persistSensitive = true,
            publishTimeoutMillis = 8_000,
        ) { _, _, _, _ ->
            enqueueCalled = true
        }

        assertFalse(delivered)
        assertTrue(enqueueCalled)
    }

    /** enqueue が失敗しても例外を外へ漏らさず、ErrorItem を残す。 */
    @Test
    fun enqueueFailureIsContained() = runTest {
        val ntfy = ControlledNtfyClient { throw NtfyPublishException(503, "unavailable") }
        val store = RecordingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store)

        val delivered = pipeline.dispatch(payload(), listOf("a"), persistSensitive = true) { _, _, _, _ ->
            throw RuntimeException("enqueue failed")
        }

        assertFalse(delivered)
        assertEquals(SEND_FAILED_MESSAGE, (store.appended.single() as ErrorItem).message)
    }

    /** store が失敗しても例外を外へ漏らさない。 */
    @Test
    fun storeFailureIsContained() = runTest {
        val ntfy = ControlledNtfyClient()
        val store = FailingStore()
        val pipeline = SendPipeline(cipher(), ntfy, store)

        val delivered = pipeline.dispatch(payload(), listOf("a"), persistSensitive = true) { _, _, _, _ -> }

        assertFalse(delivered)
    }

    private class ControlledNtfyClient(
        private val onPublish: suspend (topic: String) -> Unit = {},
    ) : NtfyClient {
        override val connectionState: StateFlow<NtfyConnectionState> =
            MutableStateFlow(NtfyConnectionState.SUBSCRIBED).asStateFlow()

        val published = mutableListOf<String>()

        override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
            onPublish(topic)
            published.add(topic)
        }

        override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()
    }

    private class RecordingStore : TimelineStore {
        val appended = mutableListOf<TimelineItem>()
        override suspend fun append(item: TimelineItem) {
            appended.add(item)
        }

        override suspend fun loadAll(): List<TimelineItem> = appended.toList()
        override suspend fun prune(maxItems: Int, now: Long) {}
    }

    private class FailingStore : TimelineStore {
        override suspend fun append(item: TimelineItem) = throw RuntimeException("disk full")
        override suspend fun loadAll(): List<TimelineItem> = emptyList()
        override suspend fun prune(maxItems: Int, now: Long) {}
    }
}
