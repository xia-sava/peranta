package to.sava.peranta

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.TimelineStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 受信機に渡すディスパッチャのスレッドの名前。呼び出し元のスレッドと見分けるために付ける。 */
private const val RECEIVER_THREAD_NAME: String = "receiver-dispatcher-test"

/** 呼び出し元に見立てたスレッドの名前。 */
private const val CALLER_THREAD_NAME: String = "receiver-caller-test"

/** 受信機の処理が進むのを待つ上限。進まないまま止まり続けるのを防ぐための値で、通常はすぐ終わる。 */
private const val AWAIT_TIMEOUT_MILLIS: Long = 5_000L

/**
 * Desktop の受信（[DesktopReceiver.run]）が、受信機に渡したディスパッチャの上で動くことを確かめる（§3.3）。
 * 呼び出し元（Compose の UI スレッド）が止まっても、受信・他端末からのコマンドの実行と応答・履歴の保存は止まらない。
 * キャンセルは受信の本体まで届き、本体が止まり切ってから run() が戻る（受信機の作り直しで新旧の受信機が並ばない）。
 *
 * タイムラインの永続側はフェイクにし、起動時の剪定と履歴の読み込みが動いたスレッドを記録する。履歴の読み込みは
 * キャンセルまで戻さず、購読（ネットワーク）へは進ませない。添付の置き場は一時ディレクトリにする。
 */
class DesktopReceiverRunDispatcherTest {

    private val attachmentsDir: File = Files.createTempDirectory("peranta-receiver-run-test").toFile()
    private val receiverExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, RECEIVER_THREAD_NAME).apply { isDaemon = true }
    }
    private val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, CALLER_THREAD_NAME).apply { isDaemon = true }
    }
    private val config = PerantaConfig(
        deviceId = "recv-device",
        sharedKeyBase64 = Base64.encode(generateKey()),
        keyId = "k1",
        receiveTopic = "recv-topic",
        controlTopic = null,
    )
    private val receivers = mutableListOf<DesktopReceiver>()

    @AfterTest
    fun cleanup() {
        runBlocking { receivers.forEach { it.close() } }
        receiverExecutor.shutdownNow()
        callerExecutor.shutdownNow()
        attachmentsDir.deleteRecursively()
    }

    /**
     * 起動時の剪定と履歴の読み込みが動いたスレッドを記録する永続側。[holdPrune] が真なら、剪定は [releasePrune] まで戻らない。
     * 履歴の読み込みはキャンセルまで戻らず、止まったら [loadStopped] を完了する。
     */
    private class ThreadRecordingStore(private val holdPrune: Boolean = false) : TimelineStore {
        val pruneThread = CompletableDeferred<Thread>()
        val releasePrune = CompletableDeferred<Unit>()
        val loadThread = CompletableDeferred<Thread>()
        val loadStopped = CompletableDeferred<Unit>()

        override suspend fun append(item: TimelineItem) = Unit

        override suspend fun loadAll(): List<TimelineItem> {
            loadThread.complete(Thread.currentThread())
            try {
                awaitCancellation()
            } finally {
                loadStopped.complete(Unit)
            }
        }

        override suspend fun prune(maxItems: Int, now: Long, maxAgeMillis: Long?) {
            pruneThread.complete(Thread.currentThread())
            if (holdPrune) releasePrune.await()
        }
    }

    private fun receiver(store: TimelineStore): DesktopReceiver = testDesktopReceiver(
        attachmentsDir = attachmentsDir,
        config = config,
        feed = TimelineFeed(store),
        dispatcher = receiverExecutor.asCoroutineDispatcher(),
    ).also { receivers.add(it) }

    /** 起動時の剪定も履歴の読み込みも、呼び出し元のスレッドではなく受信機に渡したディスパッチャのスレッドで動く。 */
    @Test
    fun runExecutesOnTheReceiverDispatcher() = runBlocking {
        val store = ThreadRecordingStore()
        val receiver = receiver(store)
        val callerThread = Thread.currentThread()

        val running = launch { receiver.run() }
        val loadThread = withTimeout(AWAIT_TIMEOUT_MILLIS) { store.loadThread.await() }
        val pruneThread = store.pruneThread.await()
        running.cancelAndJoin()

        assertNotEquals(callerThread, pruneThread)
        assertEquals(RECEIVER_THREAD_NAME, pruneThread.name)
        assertEquals(RECEIVER_THREAD_NAME, loadThread.name)
    }

    /** 呼び出し元のスレッドが塞がっていても、受信の本体は先へ進む（UI スレッドが止まっても受信は止まらない）。 */
    @Test
    fun runProceedsWhileTheCallerThreadIsBlocked() {
        val store = ThreadRecordingStore(holdPrune = true)
        val receiver = receiver(store)
        val callerBlocked = CountDownLatch(1)
        val releaseCaller = CountDownLatch(1)
        val running = CoroutineScope(callerExecutor.asCoroutineDispatcher()).launch { receiver.run() }
        try {
            runBlocking { withTimeout(AWAIT_TIMEOUT_MILLIS) { store.pruneThread.await() } }
            callerExecutor.execute {
                callerBlocked.countDown()
                releaseCaller.await()
            }
            assertTrue(callerBlocked.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            store.releasePrune.complete(Unit)

            val loadThread = runBlocking { withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { store.loadThread.await() } }
            assertNotNull(loadThread, "receiving did not proceed while the caller thread was blocked")
        } finally {
            releaseCaller.countDown()
            runBlocking { running.cancelAndJoin() }
        }
    }

    /** run() をキャンセルすると受信の本体まで止まり、本体が止まり切ってから run() が戻る。 */
    @Test
    fun cancellingRunStopsTheBodyBeforeReturning() = runBlocking {
        val store = ThreadRecordingStore()
        val receiver = receiver(store)
        var bodyStoppedWhenRunReturned = false
        val running = launch {
            try {
                receiver.run()
            } finally {
                bodyStoppedWhenRunReturned = store.loadStopped.isCompleted
            }
        }
        withTimeout(AWAIT_TIMEOUT_MILLIS) { store.loadThread.await() }

        withTimeout(AWAIT_TIMEOUT_MILLIS) { running.cancelAndJoin() }

        assertTrue(running.isCancelled)
        assertTrue(bodyStoppedWhenRunReturned)
    }
}
