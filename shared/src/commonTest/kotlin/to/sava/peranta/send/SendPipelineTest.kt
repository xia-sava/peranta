package to.sava.peranta.send

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
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyConnectionState
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SendPipelineTest {

    private fun cipher() = MessageCipher(generateKey(), "k1")

    private fun payload(priority: Priority) = NotificationPayload(
        id = newPayloadId(),
        from = "phone",
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 1000,
        packageName = "com.example.bank",
        appName = "Bank",
        title = "Verification",
        text = "code 123456",
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = 1000,
        priority = priority,
    )

    /** send は全配送先へ publish し、送信済みをタイムラインへ追記する。 */
    @Test
    fun sendPublishesToAllTopicsAndRecords() = runTest {
        val ntfy = FakeNtfyClient()
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(cipher(), ntfy, store, now = { 5000 })
        val p = payload(Priority.NORMAL)

        pipeline.send(p, listOf("topic-a", "topic-b"), persistSensitive = true)

        assertEquals(listOf("topic-a", "topic-b"), ntfy.published.map { it.topic })
        val recorded = store.appended.single() as SentNotification
        assertEquals(p.id, recorded.id)
        assertEquals(5000, recorded.timestampEpochMillis)
    }

    /** recordSent（send 経由）で記録した送信済みアイテムは feed.items へ即時反映される。 */
    @Test
    fun recordSentReflectsImmediatelyInFeedItems() = runTest {
        val ntfy = FakeNtfyClient()
        val feed = TimelineFeed(FakeTimelineStore())
        val pipeline = SendPipeline(cipher(), ntfy, feed, now = { 5000 })
        val p = payload(Priority.NORMAL)

        pipeline.send(p, listOf("topic-a"), persistSensitive = true)

        val reflected = feed.items.value.single() as SentNotification
        assertEquals(p.id, reflected.id)
    }

    /** high 優先は短キャッシュ（60s）で publish し、通常はキャッシュ指定なし。 */
    @Test
    fun highPriorityUsesShortCache() = runTest {
        val ntfy = FakeNtfyClient()
        val pipeline = SendPipeline(cipher(), ntfy, FakeTimelineStore())

        pipeline.send(payload(Priority.HIGH), listOf("topic"), persistSensitive = true)
        pipeline.send(payload(Priority.NORMAL), listOf("topic"), persistSensitive = true)

        assertEquals(HIGH_PRIORITY_CACHE_SECONDS, ntfy.published[0].cacheSeconds)
        assertEquals(null, ntfy.published[1].cacheSeconds)
    }

    /** publish が失敗すると例外を送出し、送信済みは記録しない。 */
    @Test
    fun publishFailurePropagatesWithoutRecording() = runTest {
        val store = FakeTimelineStore()
        val pipeline = SendPipeline(cipher(), FailingNtfyClient(), store)

        assertFailsWith<IllegalStateException> {
            pipeline.send(payload(Priority.NORMAL), listOf("topic"), persistSensitive = true)
        }
        assertTrue(store.appended.isEmpty())
    }

    /** 配送先が複数でも封筒は 1 度だけ封緘し、同じ本文を各 topic に流す。 */
    @Test
    fun sameEnvelopeBodyGoesToEachTopic() = runTest {
        val ntfy = FakeNtfyClient()
        val pipeline = SendPipeline(cipher(), ntfy, FakeTimelineStore())

        pipeline.send(payload(Priority.NORMAL), listOf("a", "b", "c"), persistSensitive = true)

        val bodies = ntfy.published.map { it.body }.toSet()
        assertEquals(1, bodies.size)
    }

    private class FailingNtfyClient : NtfyClient {
        override val connectionState: StateFlow<NtfyConnectionState> =
            MutableStateFlow(NtfyConnectionState.DISCONNECTED).asStateFlow()

        override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
            throw IllegalStateException("publish failed")
        }

        override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()
    }
}
