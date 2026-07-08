package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.SENSITIVE_HISTORY_PLACEHOLDER
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineStore
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiveRedactionTest {

    private val now = 10_000L
    private val deviceName = "desk"
    private val cipher = MessageCipher(generateKey(), "k1")

    private fun store(): TimelineStore = JsonlTimelineStore(FakeTimelineFile())

    private fun pipeline(store: TimelineStore, persistSensitive: Boolean) = ReceivePipeline(
        ntfy = FakeNtfyClient(),
        cipher = cipher,
        store = store,
        deviceName = deviceName,
        persistSensitiveHistory = persistSensitive,
        now = { now },
    )

    private fun otp(id: String = "n1", expiresAt: Long? = null): NotificationPayload = NotificationPayload(
        id = id,
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        packageName = "com.example.bank",
        appName = "Bank",
        title = "認証コード",
        text = "コードは 123456 です",
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = now - 100,
        expiresAtEpochMillis = expiresAt,
    )

    private suspend fun eventFor(payload: Payload): NtfyEvent =
        NtfyEvent(id = "e", time = now, topic = "t", message = encodeEnvelope(cipher.seal(payload)))

    private fun textOf(item: Any?): String =
        ((item as ReceivedNotification).payload as NotificationPayload).text

    /** 既定（非永続）では、永続履歴の OTP 本文は伏せるが、表示用の StateFlow には本文を残す。 */
    @Test
    fun otpBodyIsRedactedInStoreButKeptInDisplay() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = false)

        pipeline.handleEvent(eventFor(otp()))

        assertEquals("コードは 123456 です", textOf(pipeline.items.value.single()))
        assertEquals(SENSITIVE_HISTORY_PLACEHOLDER, textOf(store.loadAll().single()))
    }

    /** persistSensitiveHistory を有効にすると、永続履歴にも本文がそのまま残る。 */
    @Test
    fun sensitiveHistoryOptInKeepsBodyInStore() = runTest {
        val store = store()
        val pipeline = pipeline(store, persistSensitive = true)

        pipeline.handleEvent(eventFor(otp()))

        assertEquals("コードは 123456 です", textOf(store.loadAll().single()))
    }

    /** 表示フック（OS 通知・トーストへ回す一時表示）には伏せ字前の本文が渡る。 */
    @Test
    fun onItemAppendedReceivesUnredactedBody() = runTest {
        var seenText: String? = null
        val pipeline = ReceivePipeline(
            ntfy = FakeNtfyClient(),
            cipher = cipher,
            store = store(),
            deviceName = deviceName,
            persistSensitiveHistory = false,
            now = { now },
            onItemAppended = { item ->
                seenText = ((item as? ReceivedNotification)?.payload as? NotificationPayload)?.text
            },
        )

        pipeline.handleEvent(eventFor(otp()))

        assertEquals("コードは 123456 です", seenText)
    }

    /** loadHistory は失効済みエントリを表示から除外するが、剪定するまでストアには残す。 */
    @Test
    fun loadHistoryHidesExpiredButKeepsStored() = runTest {
        val store = store()
        store.append(
            ReceivedNotification("expired", now - 200, otp(id = "expired"), expiresAtEpochMillis = now - 1),
        )
        store.append(
            ReceivedNotification("live", now - 100, otp(id = "live"), expiresAtEpochMillis = now + 10_000),
        )
        val pipeline = pipeline(store, persistSensitive = true)

        pipeline.loadHistory()

        assertEquals(listOf("live"), pipeline.items.value.map { it.id })
        assertEquals(listOf("expired", "live"), store.loadAll().map { it.id })
    }
}
