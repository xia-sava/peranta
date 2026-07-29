package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.ERROR_SUPPRESSION_WINDOW_MILLIS
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 外部から任意回数誘発できる受信エラーが、タイムライン・永続化・OS 通知へ際限なく積まれないこと（§10.5）。
 * 抑止しても各時間枠の 1 件目は必ず出る（黙って捨てない、という受信側の約束を壊さない）。
 */
class ReceiveErrorSuppressionTest {

    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")
    private var clock = 10_000L

    private val appended = mutableListOf<TimelineItem>()

    private fun pipeline(store: TimelineStore = JsonlTimelineStore(FakeTimelineFile())) = ReceivePipeline(
        FakeNtfyClient(),
        cipher,
        TimelineFeed(store),
        "desk",
        now = { clock },
        onItemAppended = { appended += it },
    )

    private fun notification(): NotificationPayload = NotificationPayload(
        id = "n1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = clock,
        packageName = "com.example.bank",
        appName = "Bank",
        title = "Code",
        text = "123456",
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = clock,
    )

    /** 解析できない本文（＝鍵を持たない第三者でも作れる入力）のイベント。 */
    private fun malformed(index: Int): NtfyEvent = NtfyEvent("e$index", clock, "t", "not-json-at-all-$index")

    /** keyId が受信側と食い違う暗号文のイベント。 */
    private suspend fun keyIdMismatch(index: Int): NtfyEvent = NtfyEvent(
        id = "e$index",
        time = clock,
        topic = "t",
        message = encodeEnvelope(MessageCipher(keyBytes, "k2").seal(notification())),
    )

    /** 同じ種別のエラーを短時間に大量に流しても、タイムライン・永続化・通知は 1 件で止まる。 */
    @Test
    fun floodOfUntrustedInputErrorsAppendsOnce() = runTest {
        val store = JsonlTimelineStore(FakeTimelineFile())
        val p = pipeline(store)

        repeat(100) { p.handleEvent(malformed(it)) }

        assertEquals(1, p.items.value.size)
        assertEquals(1, store.loadAll().size)
        assertEquals(1, appended.size)
    }

    /** 抑止窓を跨げば再び 1 件出る（流量を当て続けられても可視化が永久に止まることはない）。 */
    @Test
    fun errorAppearsAgainAfterSuppressionWindow() = runTest {
        val p = pipeline()

        repeat(10) { p.handleEvent(malformed(it)) }
        clock += ERROR_SUPPRESSION_WINDOW_MILLIS + 1
        p.handleEvent(malformed(10))

        assertEquals(2, p.items.value.size)
    }

    /** 抑止は種別ごとに独立で、別種別のエラーは同じ窓でも埋もれない。 */
    @Test
    fun differentErrorKindsAreSuppressedIndependently() = runTest {
        val p = pipeline()

        repeat(10) { p.handleEvent(malformed(it)) }
        repeat(10) { p.handleEvent(keyIdMismatch(it)) }

        val kinds = p.items.value.map { (it as ErrorItem).kind }
        assertEquals(listOf(ErrorKind.ENVELOPE_DECODE, ErrorKind.KEY_ID_MISMATCH), kinds)
    }

    /** 鍵の読み直し忘れに気づけるよう、keyId 不一致は流量に関わらず最初の 1 件が必ず出る。 */
    @Test
    fun keyIdMismatchIsAlwaysVisibleAtLeastOnce() = runTest {
        val p = pipeline()

        repeat(50) { p.handleEvent(keyIdMismatch(it)) }

        val error = p.items.value.single() as ErrorItem
        assertEquals(ErrorKind.KEY_ID_MISMATCH, error.kind)
        assertTrue(error.message.contains("ペアリング"))
    }
}
