package to.sava.peranta.send

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.RecordingControlNtfy
import to.sava.peranta.roster.RosterStore
import to.sava.peranta.roster.resolveDeliveryTopics
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 送信先の動的化（§8）を、ロスター構築 → 宛先解決 → SendPipeline 配送まで通して検証する。
 * deliveryTopics は「ロスターから宛先を解決できないときのフォールバック」という位置づけで残す判断を、
 * 空ロスター時のケースで振る舞いとして示す。
 */
class RosterFanoutTest {

    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")
    private val controlTopic = "peranta-control-xyz"
    private val selfDeviceId = "self-device"

    private fun presence(deviceId: String, endpoint: String) = PresencePayload(
        id = "p-$deviceId",
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 100,
        deviceName = deviceId,
        endpoint = endpoint,
        capabilities = listOf(CAPABILITY_DISPLAY),
    )

    private suspend fun event(payload: Payload): NtfyEvent =
        NtfyEvent("e", 100, controlTopic, encodeEnvelope(cipher.seal(payload)))

    private fun notification() = NotificationPayload(
        id = "n1", from = selfDeviceId, to = BROADCAST_TARGET, sentAtEpochMillis = 1000,
        packageName = "com.example.bank", appName = "Bank", title = "t", text = "b",
        notificationKey = "0|com.example.bank|1|null|10", postedAtEpochMillis = 1000,
    )

    private class RecordingStore : TimelineStore {
        val appended = mutableListOf<TimelineItem>()
        override suspend fun append(item: TimelineItem) { appended.add(item) }
        override suspend fun loadAll(): List<TimelineItem> = appended.toList()
        override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {}
    }

    /** fan-out はロスター由来のエンドポイント topic（自分を除く）へ配送する。 */
    @Test
    fun dispatchUsesRosterEndpointsExcludingSelf() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence(selfDeviceId, "https://h/self-topic")),
                event(presence("tablet", "https://h/tablet-topic")),
                event(presence("desktop", "https://h/desktop-topic")),
            ),
        )
        val result = RosterStore(ntfy, cipher, controlTopic).fetch()
        val topics = resolveDeliveryTopics(result, selfDeviceId, fallback = listOf("static"))

        SendPipeline(cipher, ntfy, RecordingStore()).dispatch(
            payload = notification(),
            topics = topics,
            persistSensitive = true,
        ) { _, _, _, _ -> }

        assertEquals(listOf("desktop-topic", "tablet-topic"), ntfy.published.map { it.topic })
    }

    /** ロスターが（取得できた上で）空なら静的な deliveryTopics へフォールバックして配送する。 */
    @Test
    fun dispatchFallsBackToStaticDeliveryTopicsWhenRosterEmpty() = runTest {
        val ntfy = RecordingControlNtfy(history = emptyList())
        val result = RosterStore(ntfy, cipher, controlTopic).fetch()
        val topics = resolveDeliveryTopics(result, selfDeviceId, fallback = listOf("static-a", "static-b"))

        SendPipeline(cipher, ntfy, RecordingStore()).dispatch(
            payload = notification(),
            topics = topics,
            persistSensitive = true,
        ) { _, _, _, _ -> }

        assertEquals(listOf("static-a", "static-b"), ntfy.published.map { it.topic })
    }

    /**
     * ロスターが空でフォールバック（deliveryTopics）も無いとき、配送先 0 件を「送信済み」として
     * 記録してはいけない。再送へ回り、送信済み記録もされない（OTP がサイレントに失われる回帰防止）。
     */
    @Test
    fun dispatchDoesNotRecordSentWhenNoTopicsResolveAndNoFallback() = runTest {
        val ntfy = RecordingControlNtfy(history = emptyList())
        val result = RosterStore(ntfy, cipher, controlTopic).fetch()
        val topics = resolveDeliveryTopics(result, selfDeviceId, fallback = emptyList())
        val store = RecordingStore()
        var enqueueCalled = false

        val delivered = SendPipeline(cipher, ntfy, store).dispatch(
            payload = notification(),
            topics = topics,
            persistSensitive = true,
        ) { _, _, _, _ -> enqueueCalled = true }

        assertFalse(delivered)
        assertTrue(enqueueCalled)
        assertTrue(store.appended.none { it is SentNotification })
    }

    /**
     * control topic の履歴取得自体が失敗したときは「解決不能」として扱い、deliveryTopics
     * フォールバックが設定されていても使わせない（フォールバックへ流すと、実際は配送先が
     * 分からない状態を「静的宛先へ届いた」と取り違える）。
     */
    @Test
    fun dispatchDoesNotFallBackToStaticTopicsWhenHistoryFetchFails() = runTest {
        val ntfy = RecordingControlNtfy(historyError = RuntimeException("network down"))
        val result = RosterStore(ntfy, cipher, controlTopic).fetch()
        val topics = resolveDeliveryTopics(result, selfDeviceId, fallback = listOf("static-a"))
        val store = RecordingStore()

        val delivered = SendPipeline(cipher, ntfy, store).dispatch(
            payload = notification(),
            topics = topics,
            persistSensitive = true,
        ) { _, _, _, _ -> }

        assertFalse(delivered)
        assertTrue(ntfy.published.isEmpty())
        assertTrue(store.appended.none { it is SentNotification })
    }
}
