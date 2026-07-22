package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UnifiedPush 経由の受信で駆動されるメッセージ処理（onMessage → 復号 → TimelineItem/表示）の純粋部分。
 * Android のコールバックは NtfyClient を持たない（ntfy=null）ため、[ReceivePipeline.loadHistory] +
 * [ReceivePipeline.handleEvent] を購読なしで駆動する経路を検証する。message は暗号文 Envelope 文字列。
 */
class UnifiedPushMessageTest {

    private val now = 10_000L
    private val deviceId = "tablet"
    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")

    private fun store(): TimelineStore = JsonlTimelineStore(FakeTimelineFile())

    private fun presented(store: TimelineStore = store()): Pair<ReceivePipeline, MutableList<TimelineItem>> {
        val shown = mutableListOf<TimelineItem>()
        val pipeline = ReceivePipeline(
            ntfy = null,
            cipher = cipher,
            feed = TimelineFeed(store),
            deviceId = deviceId,
            now = { now },
            onItemAppended = { shown.add(it) },
        )
        return pipeline to shown
    }

    private fun notification(
        to: String = "*",
        expiresAt: Long? = null,
    ) = NotificationPayload(
        id = "n1",
        from = "phone",
        to = to,
        sentAtEpochMillis = now - 100,
        packageName = "com.example.bank",
        appName = "Bank",
        title = "Code",
        text = "123456",
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = now - 100,
        expiresAtEpochMillis = expiresAt,
    )

    /** UnifiedPush が渡す Envelope 文字列は NtfyEvent.message に載せて処理する。 */
    private suspend fun messageEvent(payload: Payload, sealCipher: MessageCipher = cipher): NtfyEvent {
        val envelopeJson = encodeEnvelope(sealCipher.seal(payload))
        return NtfyEvent(id = "", time = now, topic = "endpoint", message = envelopeJson)
    }

    private suspend fun drive(pipeline: ReceivePipeline, event: NtfyEvent) {
        pipeline.loadHistory()
        pipeline.handleEvent(event)
    }

    /** 正常系: 自分宛で未失効の暗号文 Envelope は復号され通知として表示・記録される。 */
    @Test
    fun validEnvelopeIsDisplayedAndStored() = runTest {
        val store = store()
        val (pipeline, shown) = presented(store)
        drive(pipeline, messageEvent(notification()))
        assertEquals(1, shown.size)
        assertTrue(shown.single() is ReceivedNotification)
        assertEquals(listOf("n1"), store.loadAll().map { it.id })
    }

    /** 改竄された暗号文は DECRYPTION エラーとして表示され、通知は出ない。 */
    @Test
    fun tamperedCiphertextIsReportedAsError() = runTest {
        val (pipeline, shown) = presented()
        val envelope = cipher.seal(notification())
        val bytes = Base64.decode(envelope.ciphertext).also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val tampered = encodeEnvelope(envelope.copy(ciphertext = Base64.encode(bytes)))
        drive(pipeline, NtfyEvent("", now, "endpoint", tampered))
        assertEquals(ErrorKind.DECRYPTION, (shown.single() as ErrorItem).kind)
    }

    /** keyId 不一致は再ペアリングを促す KEY_ID_MISMATCH エラーになる。 */
    @Test
    fun keyIdMismatchIsReportedAsError() = runTest {
        val (pipeline, shown) = presented()
        val otherKeyId = MessageCipher(keyBytes, "k2")
        drive(pipeline, messageEvent(notification(), sealCipher = otherKeyId))
        assertEquals(ErrorKind.KEY_ID_MISMATCH, (shown.single() as ErrorItem).kind)
    }

    /** 宛先が自端末でも全端末でもない通知は破棄され、表示も記録もされない。 */
    @Test
    fun messageForAnotherDeviceIsDropped() = runTest {
        val store = store()
        val (pipeline, shown) = presented(store)
        drive(pipeline, messageEvent(notification(to = "someone-else")))
        assertTrue(shown.isEmpty())
        assertTrue(store.loadAll().isEmpty())
    }

    /** 失効済みの通知は破棄され、表示も記録もされない。 */
    @Test
    fun expiredMessageIsDropped() = runTest {
        val store = store()
        val (pipeline, shown) = presented(store)
        drive(pipeline, messageEvent(notification(expiresAt = now - 1)))
        assertTrue(shown.isEmpty())
        assertTrue(store.loadAll().isEmpty())
    }

    /** 履歴に既にある id と同じ通知は loadHistory 後の重複排除で破棄される。 */
    @Test
    fun idAlreadyInHistoryIsDeduped() = runTest {
        val store = store()
        store.append(
            ReceivedNotification(
                id = "n1",
                timestampEpochMillis = now,
                payload = notification(),
                expiresAtEpochMillis = null,
            ),
        )
        val (pipeline, shown) = presented(store)
        drive(pipeline, messageEvent(notification()))
        assertTrue(shown.isEmpty())
        assertEquals(1, store.loadAll().size)
    }
}
