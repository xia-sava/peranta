package to.sava.peranta.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** publish は成功するが購読側でマーカーを注入して到達を検証する共通フェイク。 */
private class SelfTestFakeNtfy : NtfyClient {
    override val connectionState =
        MutableStateFlow(NtfyConnectionState.SUBSCRIBED).asStateFlow()

    data class Published(val topic: String, val body: String, val cacheSeconds: Int?)

    val published = mutableListOf<Published>()

    override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
        published.add(Published(topic, body, cacheSeconds))
    }

    override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()
}

/** publish が指定ステータスで拒否されるフェイク。 */
private class RejectingNtfy(private val status: Int) : NtfyClient {
    override val connectionState =
        MutableStateFlow(NtfyConnectionState.DISCONNECTED).asStateFlow()

    override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
        throw NtfyPublishException(status, "rejected")
    }

    override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()
}

/** publish が一般例外で失敗するフェイク。 */
private class FailingNtfy : NtfyClient {
    override val connectionState =
        MutableStateFlow(NtfyConnectionState.DISCONNECTED).asStateFlow()

    override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
        throw IllegalStateException("boom")
    }

    override fun subscribe(topic: String): Flow<NtfyEvent> = emptyFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SelfTestProbeTest {

    /** publish される topic・マーカー本文（固定 nonce）・cacheSeconds が仕様どおりであること。 */
    @Test
    fun publishesMarkerWithFixedNonceAndCache() = runTest {
        val fake = SelfTestFakeNtfy()
        val probe = SelfTestProbe(nonceGen = { "NONCE" })
        val job = async { probe.run(fake, "my-topic") }
        runCurrent()

        val published = fake.published.single()
        assertEquals("my-topic", published.topic)
        assertEquals("peranta-selftest:NONCE", published.body)
        assertEquals(60, published.cacheSeconds)

        probe.consumeMarker(published.body)
        job.await()
    }

    /** nonce 一致のマーカーは横取りされ、run は Delivered を返して Done に至ること。 */
    @Test
    fun matchingMarkerYieldsDelivered() = runTest {
        val fake = SelfTestFakeNtfy()
        val probe = SelfTestProbe(nonceGen = { "N" }, now = { 1234L })
        val job = async { probe.run(fake, "t") }
        runCurrent()

        assertTrue(probe.consumeMarker("peranta-selftest:N"))
        assertEquals(SelfTestResult.Delivered, job.await())
        assertEquals(SelfTestStatus.Done(SelfTestResult.Delivered, 1234L), probe.status.value)
    }

    /** 別 nonce のマーカーは破棄（true）されるが完了させず、時間経過で Timeout になること。 */
    @Test
    fun differentNonceIsDiscardedThenTimesOut() = runTest {
        val fake = SelfTestFakeNtfy()
        val probe = SelfTestProbe(nonceGen = { "N" })
        val job = async { probe.run(fake, "t") }
        runCurrent()

        assertTrue(probe.consumeMarker("peranta-selftest:OTHER"))
        advanceUntilIdle()
        assertEquals(SelfTestResult.Timeout, job.await())
    }

    /** マーカー接頭辞で始まらない文字列は横取り対象外（false）であること。 */
    @Test
    fun nonMarkerReturnsFalse() {
        assertFalse(SelfTestProbe().consumeMarker("hello world"))
    }

    /** publish が 403 で拒否されると PublishRejected(403) に分類されること。 */
    @Test
    fun publishRejectedMapsStatus() = runTest {
        val probe = SelfTestProbe()
        assertEquals(SelfTestResult.PublishRejected(403), probe.run(RejectingNtfy(403), "t"))
    }

    /** publish の一般例外は PublishFailed に分類されること。 */
    @Test
    fun generalExceptionMapsToPublishFailed() = runTest {
        val probe = SelfTestProbe()
        assertEquals(SelfTestResult.PublishFailed, probe.run(FailingNtfy(), "t"))
    }

    /** マーカーが到達しないまま timeout 経過で Timeout になること。 */
    @Test
    fun timesOutWhenNoMarker() = runTest {
        val probe = SelfTestProbe()
        assertEquals(SelfTestResult.Timeout, probe.run(SelfTestFakeNtfy(), "t"))
    }

    /** 実行中の再入は publish を二重発行せず、進行中の状態も壊さないこと。 */
    @Test
    fun rerunWhileRunningDoesNotPublishTwice() = runTest {
        val fake = SelfTestFakeNtfy()
        val probe = SelfTestProbe(nonceGen = { "N" })
        val job = async { probe.run(fake, "t") }
        runCurrent()
        assertEquals(SelfTestStatus.Running, probe.status.value)

        assertEquals(SelfTestResult.Timeout, probe.run(fake, "t"))
        assertEquals(1, fake.published.size)
        assertEquals(SelfTestStatus.Running, probe.status.value)

        probe.consumeMarker("peranta-selftest:N")
        assertEquals(SelfTestResult.Delivered, job.await())
    }

    /** 実行中に外部からキャンセルされても状態が Running に固着せず、元の状態へ戻ること。 */
    @Test
    fun cancellationRestoresPreviousStatus() = runTest {
        val fake = SelfTestFakeNtfy()
        val probe = SelfTestProbe(nonceGen = { "N" })
        val job = async { probe.run(fake, "t") }
        runCurrent()
        assertEquals(SelfTestStatus.Running, probe.status.value)

        job.cancel()
        runCurrent()
        assertEquals(SelfTestStatus.NotRun, probe.status.value)
    }

    /** 状態が NotRun → Running（実行中）→ Done と遷移すること。 */
    @Test
    fun statusTransitionsThroughRunningToDone() = runTest {
        val fake = SelfTestFakeNtfy()
        val probe = SelfTestProbe(nonceGen = { "N" }, now = { 7L })
        assertEquals(SelfTestStatus.NotRun, probe.status.value)

        val job = async { probe.run(fake, "t") }
        runCurrent()
        assertEquals(SelfTestStatus.Running, probe.status.value)

        probe.consumeMarker("peranta-selftest:N")
        job.await()
        assertEquals(SelfTestStatus.Done(SelfTestResult.Delivered, 7L), probe.status.value)
    }
}
