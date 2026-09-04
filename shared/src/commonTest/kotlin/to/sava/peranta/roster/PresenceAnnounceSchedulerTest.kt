package to.sava.peranta.roster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceAnnounceSchedulerTest {

    /**
     * スケジューラ内部の collectLatest は無限に購読を続けるため、テストごとに独立して
     * cancel できる子スコープを渡す（テストのディスパッチャは共有し、後始末だけ分離する）。
     */
    private suspend fun childScope(): Pair<CoroutineScope, Job> {
        val job = Job(coroutineContext[Job])
        return CoroutineScope(coroutineContext + job) to job
    }

    /** デバウンス時間内の連続 request は最後の値で 1 回だけ announce される。 */
    @Test
    fun debouncesConsecutiveRequestsToLastValueOnly() = runTest {
        val announced = mutableListOf<Int>()
        val (scope, job) = childScope()
        val scheduler = PresenceAnnounceScheduler<Int>(scope, debounceMillis = 100) { value ->
            announced.add(value)
        }
        runCurrent() // 内部の collectLatest を購読させてから request する。

        scheduler.request(1)
        advanceTimeBy(50)
        scheduler.request(2)
        advanceTimeBy(50)
        scheduler.request(3)
        advanceUntilIdle()

        assertEquals(listOf(3), announced)
        job.cancel()
    }

    /** デバウンス時間を空けた request はそれぞれ announce される。 */
    @Test
    fun requestsSpacedBeyondDebounceEachAnnounceSeparately() = runTest {
        val announced = mutableListOf<Int>()
        val (scope, job) = childScope()
        val scheduler = PresenceAnnounceScheduler<Int>(scope, debounceMillis = 100) { value ->
            announced.add(value)
        }
        runCurrent() // 内部の collectLatest を購読させてから request する。

        scheduler.request(1)
        advanceUntilIdle()
        scheduler.request(2)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), announced)
        job.cancel()
    }

    /** 実行中の announce が新しい request でキャンセルされ、最新値で再実行される。 */
    @Test
    fun runningAnnounceIsCancelledAndRerunWithLatestValue() = runTest {
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val (scope, job) = childScope()
        val scheduler = PresenceAnnounceScheduler<Int>(scope, debounceMillis = 100) { value ->
            started.add(value)
            delay(200)
            completed.add(value)
        }
        runCurrent() // 内部の collectLatest を購読させてから request する。

        scheduler.request(1)
        advanceTimeBy(150) // デバウンス経過、announce(1) が delay(200) の途中まで進む。
        scheduler.request(2)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), started)
        assertEquals(listOf(2), completed)
        job.cancel()
    }
}
