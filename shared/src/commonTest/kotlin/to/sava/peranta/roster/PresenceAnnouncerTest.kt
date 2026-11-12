package to.sava.peranta.roster

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PresenceAnnouncer] の告知（§3.5）を、control topic への publish まで通して検証する。
 * 告知に載せる自端末の情報（[PresenceSource]）の publish は [publishPresence] で [RecordingControlNtfy] へ送り、
 * 送った presence は封筒を復号して確かめる。
 * [PresenceSource.publish] が呼ばれた回数（[publishCalls]）は、抑止されたときに通信の支度もしないことの確認に使う。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceAnnouncerTest {

    /** publish の失敗を差し込める [NtfyClient]。[failure] があれば、本文を記録せずにそれを投げる。 */
    private class FailableNtfy(private val delegate: RecordingControlNtfy) : NtfyClient by delegate {
        var failure: Throwable? = null

        override suspend fun publish(topic: String, body: String, cache: Boolean) {
            failure?.let { throw it }
            delegate.publish(topic, body, cache)
        }
    }

    private val cipher = MessageCipher(generateKey(), "k1")
    private val recorded = RecordingControlNtfy()
    private val ntfy = FailableNtfy(recorded)

    private var now = 0L
    private var configured = true
    private var deviceName = "phone"
    private var canCommand = false
    private var destination = "$CONTROL_TOPIC|k1"
    private var publishDelayMillis = 0L
    private var publishCalls = 0
    private var sourceFailure: Throwable? = null
    private var sourceCalls = 0

    /**
     * 告知に載せる自端末の情報。[configured] が偽なら設定が足りない端末として null を返す。
     * [sourceFailure] があれば、情報を組む段でそれを投げる。
     */
    private fun currentSource(): PresenceSource? {
        sourceCalls++
        sourceFailure?.let { throw it }
        return PresenceSource(
            deviceId = "dev-1",
            deviceName = deviceName,
            endpoint = "https://h/e",
            canDisplay = true,
            canCommand = canCommand,
            sender = true,
            destination = destination,
            publish = { presence ->
                publishCalls++
                if (publishDelayMillis > 0) delay(publishDelayMillis)
                publishPresence(cipher, ntfy, CONTROL_TOPIC, presence)
            },
        ).takeIf { configured }
    }

    /** デバウンスの待ちをこのテストの仮想時間で進める [PresenceAnnouncer] を組む。 */
    private fun TestScope.announcer(
        minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
        log: Logger = Logger.withTag("Presence"),
    ): PresenceAnnouncer = PresenceAnnouncer(
        scope = backgroundScope,
        source = { currentSource() },
        gate = PresenceAnnounceGate(minIntervalMillis),
        now = { now },
        debounceMillis = DEBOUNCE_MILLIS,
        log = log,
    )

    private suspend fun publishedPresences(): List<PresencePayload> =
        recorded.published.map { cipher.open(decodeEnvelope(it.body)) as PresencePayload }

    /** 告知は自端末の情報から presence を組み、control topic へサーバに保持させる形で送る。 */
    @Test
    fun announcePublishesPresenceBuiltFromSource() = runTest {
        now = 5_000
        canCommand = true

        announcer().announce()

        val published = recorded.published.single()
        assertEquals(CONTROL_TOPIC, published.topic)
        assertTrue(published.cache)
        val presence = publishedPresences().single()
        assertEquals("dev-1", presence.from)
        assertEquals(BROADCAST_TARGET, presence.to)
        assertEquals("phone", presence.deviceName)
        assertEquals("https://h/e", presence.endpoint)
        assertEquals(presenceCapabilities(canDisplay = true, canCommand = true), presence.capabilities)
        assertTrue(presence.sender)
        assertEquals(5_000, presence.sentAtEpochMillis)
    }

    /** 自端末の情報が組めない（設定が足りない）ときは publish しない。 */
    @Test
    fun announceWithoutSourceDoesNotPublish() = runTest {
        configured = false

        announcer().announce()

        assertEquals(0, publishCalls)
        assertTrue(recorded.published.isEmpty())
    }

    /** 同じ内容の告知は、最小間隔に満たないあいだ抑止する。 */
    @Test
    fun sameContentIsSuppressedWithinMinInterval() = runTest {
        val announcer = announcer()

        announcer.announce()
        now = MIN_INTERVAL_MILLIS - 1
        announcer.announce()

        assertEquals(1, recorded.published.size)
    }

    /** 同じ内容でも、最小間隔が過ぎれば告知する。 */
    @Test
    fun sameContentPassesAfterMinInterval() = runTest {
        val announcer = announcer()

        announcer.announce()
        now = MIN_INTERVAL_MILLIS
        announcer.announce()

        assertEquals(2, recorded.published.size)
    }

    /** 内容が変われば、最小間隔の内でも告知する。 */
    @Test
    fun changedContentPassesWithinMinInterval() = runTest {
        val announcer = announcer()

        announcer.announce()
        now = 1
        deviceName = "phone-renamed"
        announcer.announce()

        assertEquals(listOf("phone", "phone-renamed"), publishedPresences().map { it.deviceName })
    }

    /** 届け先（control topic や keyId）が変われば、同じ内容でも最小間隔の内に告知する。 */
    @Test
    fun changedDestinationPassesWithinMinInterval() = runTest {
        val announcer = announcer()

        announcer.announce()
        now = 1
        destination = "$CONTROL_TOPIC|k2"
        announcer.announce()
        now = 2
        announcer.announce()

        assertEquals(2, recorded.published.size)
    }

    /** 抑止されるときは [PresenceSource.publish] を呼ばない（通信の支度もしない）。 */
    @Test
    fun suppressedAnnounceDoesNotEnterPublish() = runTest {
        val announcer = announcer()

        announcer.announce()
        now = 1
        announcer.announce()

        assertEquals(1, publishCalls)
    }

    /** publish に失敗した内容は送ったことにせず、次の契機では同じ内容でも告知する。 */
    @Test
    fun failedPublishIsNotRecordedAndRetriedOnNextTrigger() = runTest {
        val announcer = announcer()
        ntfy.failure = IllegalStateException("network down")

        announcer.announce()
        ntfy.failure = null
        now = 1
        announcer.announce()

        assertEquals(2, publishCalls)
        assertEquals(1, recorded.published.size)
    }

    /** publish の失敗は呼び出し元へ漏らさずログに残し、例外のメッセージ（接続先 URL）はログに出さない（§11）。 */
    @Test
    fun publishFailureIsLoggedWithoutEscaping() = runTest {
        val writer = RecordingLogWriter()
        val announcer = announcer(log = recordingLogger(writer, "Presence"))
        ntfy.failure = IllegalStateException("Request timeout has expired [url=https://h/$CONTROL_TOPIC]")

        announcer.announce()

        assertTrue(writer.recorded.any { it.contains("presence announce failed") }, "${writer.recorded}")
        assertTrue(writer.recorded.none { it.contains(CONTROL_TOPIC) }, "topic appeared in log: ${writer.recorded}")
    }

    /** publish の中で起きたキャンセルは握らず、呼び出し元へ伝える。 */
    @Test
    fun cancellationPropagates() = runTest {
        val announcer = announcer()
        ntfy.failure = CancellationException("cancelled")

        assertFailsWith<CancellationException> { announcer.announce() }
    }

    /** デバウンスの待ちの内に続いた要求は、1 回の告知にまとめる。 */
    @Test
    fun consecutiveRequestsAreCoalescedIntoOneAnnounce() = runTest {
        // 同じ内容を抑止しない間隔にして、告知の回数をそのまま publish の回数で数える。
        val announcer = announcer(minIntervalMillis = 0)

        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS / 2)
        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS / 2)
        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS * 3)
        runCurrent()

        assertEquals(1, publishCalls)
        assertEquals(1, recorded.published.size)
    }

    /** [PresenceAnnouncer] を作った直後、要求を待ち受け始める前に届いた要求も告知する。 */
    @Test
    fun requestRightAfterConstructionIsAnnounced() = runTest {
        val announcer = announcer()

        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertEquals(1, recorded.published.size)
    }

    /** 同時に呼んだ告知は直列に走り、同じ内容が二重に publish されない。 */
    @Test
    fun concurrentAnnouncesAreSerialized() = runTest {
        val announcer = announcer()
        publishDelayMillis = 100

        launch { announcer.announce() }
        launch { announcer.announce() }
        advanceUntilIdle()

        assertEquals(1, publishCalls)
        assertEquals(1, recorded.published.size)
    }

    /** デバウンスを経た告知が publish に失敗しても要求の受け手は止まらず、次の要求で告知する。 */
    @Test
    fun requestKeepsWorkingAfterPublishFailure() = runTest {
        val announcer = announcer()
        ntfy.failure = IllegalStateException("network down")

        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        ntfy.failure = null
        now = 1
        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertEquals(2, publishCalls)
        assertEquals(1, recorded.published.size)
    }

    /** 自端末の情報を組む段で例外が出ても、呼び出し元へ漏らさずログに残す。 */
    @Test
    fun sourceFailureIsLoggedWithoutEscaping() = runTest {
        val writer = RecordingLogWriter()
        val announcer = announcer(log = recordingLogger(writer, "Presence"))
        sourceFailure = IllegalStateException("broken settings")

        announcer.announce()

        assertTrue(writer.recorded.any { it.contains("presence announce failed") }, "${writer.recorded}")
        assertTrue(recorded.published.isEmpty())
    }

    /** 自端末の情報を組む段で例外が出ても要求の受け手は止まらず、直ったあとの要求で告知する。 */
    @Test
    fun requestKeepsWorkingAfterSourceFailure() = runTest {
        val announcer = announcer()
        sourceFailure = IllegalStateException("broken settings")

        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()
        sourceFailure = null
        announcer.request()
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertEquals(2, sourceCalls)
        assertEquals(1, recorded.published.size)
    }

    /**
     * 告知に載せる自端末の情報は、前の告知が終わって直列化の順番が回ってから読む。
     * 待っているあいだに能力が変われば、変わった後の能力で告知する。
     */
    @Test
    fun sourceIsReadAfterPreviousAnnounceCompletes() = runTest {
        val announcer = announcer()
        publishDelayMillis = 100

        launch { announcer.announce() }
        runCurrent()
        launch { announcer.announce() }
        runCurrent()
        canCommand = true
        advanceUntilIdle()

        assertEquals(
            listOf(
                presenceCapabilities(canDisplay = true, canCommand = false),
                presenceCapabilities(canDisplay = true, canCommand = true),
            ),
            publishedPresences().map { it.capabilities },
        )
    }

    /** publish の途中でキャンセルされた告知は送ったことにせず、直列化も解いて次の告知を通す。 */
    @Test
    fun cancelledAnnounceIsNotRecordedAndReleasesSerialization() = runTest {
        val announcer = announcer()
        publishDelayMillis = 100

        val inFlight = launch { announcer.announce() }
        runCurrent()
        inFlight.cancel()
        runCurrent()
        // 直列化が解けていなければ待ち続けるので、仮想時間で打ち切って失敗させる。
        withTimeout(publishDelayMillis * 10) { announcer.announce() }

        assertEquals(2, publishCalls)
        assertEquals(1, recorded.published.size)
    }

    private companion object {
        const val CONTROL_TOPIC = "peranta-control-xyz"
        const val MIN_INTERVAL_MILLIS = 1_000L
        const val DEBOUNCE_MILLIS = 100L
    }
}
