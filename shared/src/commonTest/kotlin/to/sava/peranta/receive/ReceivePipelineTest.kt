package to.sava.peranta.receive

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.Envelope
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineStore
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceivePipelineTest {

    private val now = 10_000L
    private val deviceName = "desk"
    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")

    private fun store(): TimelineStore = JsonlTimelineStore(FakeTimelineFile())

    private fun pipeline(store: TimelineStore = store()) =
        ReceivePipeline(FakeNtfyClient(), cipher, store, deviceName, now = { now })

    private fun notification(
        to: String = "*",
        expiresAt: Long? = null,
    ): NotificationPayload = NotificationPayload(
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

    private suspend fun eventFor(payload: Payload, sealCipher: MessageCipher = cipher): NtfyEvent {
        val envelope = sealCipher.seal(payload)
        return NtfyEvent(id = "e", time = now, topic = "t", message = encodeEnvelope(envelope))
    }

    /** 正常系: 自分宛で未失効の通知は ReceivedNotification として追加される。 */
    @Test
    fun validNotificationIsAppended() = runTest {
        val store = store()
        val p = pipeline(store)
        p.handleEvent(eventFor(notification()))
        val items = p.items.value
        assertEquals(1, items.size)
        val received = items.single() as ReceivedNotification
        assertEquals("n1", received.id)
        assertEquals(now, received.timestampEpochMillis)
        assertEquals(listOf("n1"), store.loadAll().map { it.id })
    }

    /** keyId 不一致は再ペアリング促しの ErrorItem になり、通知は追加されない。 */
    @Test
    fun keyIdMismatchProducesError() = runTest {
        val p = pipeline()
        val sealer = MessageCipher(keyBytes, "k2")
        p.handleEvent(eventFor(notification(), sealCipher = sealer))
        val error = p.items.value.single() as ErrorItem
        assertEquals(ErrorKind.KEY_ID_MISMATCH, error.kind)
        assertTrue(error.message.contains("ペアリング"))
    }

    /** 暗号文の改竄は DECRYPTION 種別の ErrorItem になる。 */
    @Test
    fun tamperedCiphertextProducesDecryptionError() = runTest {
        val p = pipeline()
        val envelope = cipher.seal(notification())
        val bytes = Base64.decode(envelope.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = envelope.copy(ciphertext = Base64.encode(bytes))
        p.handleEvent(NtfyEvent("e", now, "t", encodeEnvelope(tampered)))
        val error = p.items.value.single() as ErrorItem
        assertEquals(ErrorKind.DECRYPTION, error.kind)
    }

    /** エンベロープ JSON として解析できない本文は ENVELOPE_DECODE の ErrorItem になる。 */
    @Test
    fun malformedEnvelopeProducesDecodeError() = runTest {
        val p = pipeline()
        p.handleEvent(NtfyEvent("e", now, "t", "not-json-at-all"))
        val error = p.items.value.single() as ErrorItem
        assertEquals(ErrorKind.ENVELOPE_DECODE, error.kind)
    }

    /** 宛先が自端末でも全端末でもない通知は破棄され、何も追加されない。 */
    @Test
    fun wrongRecipientIsDropped() = runTest {
        val p = pipeline()
        p.handleEvent(eventFor(notification(to = "someone-else")))
        assertTrue(p.items.value.isEmpty())
    }

    /** 明示宛先が自端末名なら受理される。 */
    @Test
    fun addressedToThisDeviceIsAccepted() = runTest {
        val p = pipeline()
        p.handleEvent(eventFor(notification(to = deviceName)))
        assertTrue(p.items.value.single() is ReceivedNotification)
    }

    /** 失効済み（expiresAt < now）の通知は破棄され、何も追加されない。 */
    @Test
    fun expiredNotificationIsDropped() = runTest {
        val p = pipeline()
        p.handleEvent(eventFor(notification(expiresAt = now - 1)))
        assertTrue(p.items.value.isEmpty())
    }

    /** 同一 payload.id を 2 回受信しても 1 件だけ取り込む（重複排除）。 */
    @Test
    fun duplicatePayloadIdIsDeduped() = runTest {
        val store = store()
        val p = pipeline(store)
        p.handleEvent(eventFor(notification()))
        p.handleEvent(eventFor(notification()))
        assertEquals(1, p.items.value.size)
        assertEquals(listOf("n1"), store.loadAll().map { it.id })
    }

    /** 履歴に既にある id と同じ通知は購読開始後に重複として破棄される。 */
    @Test
    fun idFromLoadedHistoryIsDeduped() = runTest {
        val store = store()
        store.append(
            ReceivedNotification(
                id = "n1",
                timestampEpochMillis = now,
                payload = notification(),
                expiresAtEpochMillis = null,
            ),
        )
        val ntfy = FakeNtfyClient(flowOf(eventFor(notification())))
        val p = ReceivePipeline(ntfy, cipher, store, deviceName, now = { now })
        p.start("my-topic")
        assertEquals(1, p.items.value.size)
    }

    /** 自分宛の CommandPayload は M3 では表示対象外なのでタイムラインに追加されない。 */
    @Test
    fun commandPayloadIsNotAppended() = runTest {
        val p = pipeline()
        val command = CommandPayload(
            id = "cmd1",
            from = "phone",
            to = deviceName,
            sentAtEpochMillis = now - 100,
            command = CommandType.DISMISS,
            targetNotificationKey = "0|com.example|1|null|10",
        )
        p.handleEvent(eventFor(command))
        assertTrue(p.items.value.isEmpty())
    }

    /** ブロードキャストの PresencePayload は M3 では表示対象外なのでタイムラインに追加されない。 */
    @Test
    fun presencePayloadIsNotAppended() = runTest {
        val p = pipeline()
        val presence = PresencePayload(
            id = "pre1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = now - 100,
            deviceName = "Pixel",
            endpoint = "e",
        )
        p.handleEvent(eventFor(presence))
        assertTrue(p.items.value.isEmpty())
    }

    /** start() は subscribe の Flow を購読し、流れたイベントを取り込む。 */
    @Test
    fun startConsumesSubscribedFlow() = runTest {
        val store = store()
        val event = eventFor(notification())
        val ntfy = FakeNtfyClient(flowOf(event))
        val p = ReceivePipeline(ntfy, cipher, store, deviceName, now = { now })
        p.start("my-topic")
        assertEquals(listOf("n1"), p.items.value.map { it.id })
    }
}
