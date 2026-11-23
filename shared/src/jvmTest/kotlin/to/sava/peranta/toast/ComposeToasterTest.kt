package to.sava.peranta.toast

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 表示要求の積み上げ・結果の受け渡し・取り下げ・同時表示上限を検証する（§3.3）。 */
class ComposeToasterTest {

    private val timeoutMillis = 2_000L

    private fun toastItem(id: String) =
        ReceivedNotificationToast(id = id, title = "件名", body = "本文")

    /** 表示中のトーストが積まれるまで待つ。 */
    private suspend fun ComposeToaster.awaitActive(count: Int) =
        withTimeout(timeoutMillis) {
            while (active.size < count) delay(1)
            active.toList()
        }

    /** 表示要求は active に積まれ、finish した結果が show の戻り値になる。 */
    @Test
    fun showAwaitsUserOutcome() = runBlocking {
        val toaster = ComposeToaster()
        val shown = async { toaster.show(toastItem("a")) }

        toaster.awaitActive(1).first().finish(ToastResult.ButtonOpen)

        assertEquals(ToastResult.ButtonOpen, withTimeout(timeoutMillis) { shown.await() })
        assertTrue(toaster.active.isEmpty())
    }

    /** 表示のたびに通知音を 1 回鳴らす。 */
    @Test
    fun showPlaysTheSoundOnce() = runBlocking {
        var played = 0
        val toaster = ComposeToaster(sound = ToastSound { played++ })
        val shown = async { toaster.show(toastItem("a")) }

        toaster.awaitActive(1).first().finish(ToastResult.Clicked)
        withTimeout(timeoutMillis) { shown.await() }

        assertEquals(1, played)
    }

    /** close は同一 id の表示を全件取り下げ、show へ Closed を返す。 */
    @Test
    fun closeFinishesEveryToastWithTheSameId() = runBlocking {
        val toaster = ComposeToaster()
        val first = async { toaster.show(toastItem("dup")) }
        val second = async { toaster.show(toastItem("dup")) }
        toaster.awaitActive(2)

        toaster.close("dup")

        assertEquals(ToastResult.Closed, withTimeout(timeoutMillis) { first.await() })
        assertEquals(ToastResult.Closed, withTimeout(timeoutMillis) { second.await() })
    }

    /** 表示していない id の close は何もせず、記録も残さない。 */
    @Test
    fun closeForMissingToastLeavesNoRecord() = runBlocking {
        val writer = RecordingLogWriter()
        val toaster = ComposeToaster(log = recordingLogger(writer, "Toaster"))

        toaster.close("gone")

        assertTrue(writer.recorded.none { it.contains("toast close") }, "${writer.recorded}")
    }

    /** close で表示を取り下げたときは、取り下げた件数を記録に残す。 */
    @Test
    fun closeRecordsHowManyToastsItFinished() = runBlocking {
        val writer = RecordingLogWriter()
        val toaster = ComposeToaster(log = recordingLogger(writer, "Toaster"))
        val first = async { toaster.show(toastItem("dup")) }
        val second = async { toaster.show(toastItem("dup")) }
        toaster.awaitActive(2)

        toaster.close("dup")
        withTimeout(timeoutMillis) { first.await() }
        withTimeout(timeoutMillis) { second.await() }

        assertTrue(writer.recorded.any { it.contains("toast close id=dup count=2") }, "${writer.recorded}")
    }

    /** update は表示中のトーストの内容を差し替える（後から届いた画像の差し込み、§4.3.1）。 */
    @Test
    fun updateReplacesContentOfActiveToast() = runBlocking {
        val toaster = ComposeToaster()
        val shown = async { toaster.show(toastItem("a")) }
        toaster.awaitActive(1)

        toaster.update(toastItem("a").copy(body = "画像付き本文"))
        val updatedBody = toaster.active.single().item.body
        toaster.active.single().finish(ToastResult.Dismissed)
        withTimeout(timeoutMillis) { shown.await() }

        assertEquals("画像付き本文", updatedBody)
    }

    /** 表示していないトーストへの update は何もしない（既に消えたトーストへの差し込み）。 */
    @Test
    fun updateForMissingToastIsIgnored() = runBlocking {
        val toaster = ComposeToaster()

        toaster.update(toastItem("gone"))

        assertTrue(toaster.active.isEmpty())
    }

    /**
     * 表示の積み上げ・取り下げと並行して close・update が走っても、表示中の一覧の走査が例外で止まらない。
     * 取り下げ（他端末からの dismiss の受信）と表示（トーストのコルーチン）は別々のスレッドで動くため（§3.3）。
     */
    @Test
    fun closeAndUpdateTolerateConcurrentShows() = runBlocking {
        val toaster = ComposeToaster(log = quietLogger(), maxConcurrent = Int.MAX_VALUE)
        val resident = (0 until RESIDENT_TOASTS).map { index ->
            async(Dispatchers.Default) { toaster.show(toastItem("resident-$index")) }
        }
        toaster.awaitActive(RESIDENT_TOASTS)

        withContext(Dispatchers.Default) {
            launch {
                repeat(CONCURRENT_ROUNDS) { round ->
                    val shown = async(start = CoroutineStart.UNDISPATCHED) { toaster.show(toastItem("churn-$round")) }
                    toaster.close("churn-$round")
                    shown.await()
                }
            }
            launch {
                repeat(CONCURRENT_ROUNDS) {
                    toaster.update(toastItem("absent"))
                    toaster.close("absent")
                }
            }
        }
        toaster.active.toList().forEach { it.finish(ToastResult.Dismissed) }
        resident.awaitAll()

        assertTrue(toaster.active.isEmpty())
    }

    /** 同時表示の上限を超えた表示要求は待たずに Failed を返す。 */
    @Test
    fun showRejectsWhenLimitReached() = runBlocking {
        val toaster = ComposeToaster(maxConcurrent = 1)
        val first = async { toaster.show(toastItem("a")) }
        toaster.awaitActive(1)

        assertEquals(ToastResult.Failed, toaster.show(toastItem("b")))

        toaster.active.first().finish(ToastResult.Dismissed)
        assertEquals(ToastResult.Dismissed, withTimeout(timeoutMillis) { first.await() })
    }

    private companion object {
        /** 並行する操作の間ずっと表示しておくトーストの数。走査に時間をかけ、並行する変更と重なりやすくする。 */
        const val RESIDENT_TOASTS: Int = 100

        /** 表示と取り下げ、走査をそれぞれ繰り返す回数。 */
        const val CONCURRENT_ROUNDS: Int = 2_000

        /** 何も出さないロガー。繰り返しの表示で記録が膨らまないようにする。 */
        fun quietLogger(): Logger = Logger(loggerConfigInit(minSeverity = Severity.Assert), "Toaster")
    }
}
