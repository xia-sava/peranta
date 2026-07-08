package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivePipelinePersistenceTest {

    private val now = 10_000L
    private val deviceName = "desk"
    private val cipher = MessageCipher(generateKey(), "k1")

    /** append 時に必ず IOException を投げる永続化層。読み込みは空を返す。 */
    private class FailingAppendStore : TimelineStore {
        override suspend fun append(item: TimelineItem): Unit = throw IOException("disk full")
        override suspend fun loadAll(): List<TimelineItem> = emptyList()
        override suspend fun prune(maxItems: Int, now: Long) {}
    }

    private fun notification(id: String): NotificationPayload = NotificationPayload(
        id = id,
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        packageName = "com.example.bank",
        appName = "Bank",
        title = "Code",
        text = "123456",
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = now - 100,
    )

    private suspend fun eventFor(id: String): NtfyEvent =
        NtfyEvent(id = "e-$id", time = now, topic = "t", message = encodeEnvelope(cipher.seal(notification(id))))

    /** store.append が I/O 例外を投げても、後続メッセージの処理は継続しメモリ上には反映される。 */
    @Test
    fun appendFailureDoesNotStopPipeline() = runTest {
        val pipeline = ReceivePipeline(FakeNtfyClient(), cipher, FailingAppendStore(), deviceName, now = { now })
        pipeline.handleEvent(eventFor("n1"))
        pipeline.handleEvent(eventFor("n2"))
        assertEquals(listOf("n1", "n2"), pipeline.items.value.map { it.id })
    }
}
