package to.sava.peranta.toast

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
