package to.sava.peranta.window

import co.touchlab.kermit.Logger
import to.sava.peranta.platform.JvmPaths
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** UI スレッドへ応答の確認を投げる間隔。 */
internal val UI_STALL_POLL_INTERVAL = 5.seconds

/** UI スレッドの最後の応答からこれを超えて経つと、停止とみなす。 */
internal val UI_STALL_THRESHOLD = 30.seconds

/**
 * 見張り自身の確認の間がこれを超えて空いたら、見張りのスレッドも動いていなかった（PC のスリープなど）とみなす。
 */
internal val UI_STALL_WATCHDOG_GAP = UI_STALL_POLL_INTERVAL * 2

/**
 * AWT の EDT（UI スレッド）の停止を見張り、止まったときに全スレッドのスタックを記録する（§3.3）。
 *
 * [UI_STALL_POLL_INTERVAL] ごとに EDT のキューへ入れた確認が「EDT が応答した時刻」を更新する。最後の応答から
 * [UI_STALL_THRESHOLD] を超えたら停止とみなし、[captureStacks] で採った全スレッドのスタックを
 * [writeReport] で 1 回だけ書き出す。同じ停止の間は書き出しを繰り返さない。EDT が再び応答したら、
 * 止まっていた秒数をログに残して次の停止に備える。検知しても記録するだけで、再起動などはしない。
 *
 * EDT が止まっている間は新しい確認をキューへ入れない。入れてある確認が EDT で実行されたときに応答として扱う。
 *
 * 見張り自身の確認の間が [UI_STALL_WATCHDOG_GAP] を超えて空いたときは、それより前の無応答を停止に数えない。
 * PC のスリープ中は EDT も見張りも止まっており、復帰した時点で無応答の時間が閾値を超えて見えるため。
 *
 * [writeReport] は書き出したファイルを返す。書き出しの失敗は警告に残し、見張りは続ける。
 */
internal class UiStallWatchdog(
    private val postToEdt: (Runnable) -> Unit,
    private val captureStacks: () -> Map<Thread, Array<StackTraceElement>>,
    private val writeReport: (Map<Thread, Array<StackTraceElement>>) -> File,
    private val now: () -> Duration = { System.nanoTime().nanoseconds },
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
    private val log: Logger = Logger.withTag("UiStall"),
) {
    /** EDT が最後に応答した時刻。EDT が書き、見張りのスレッドが読む。 */
    @Volatile
    private var lastResponse: Duration = now()

    /** キューへ入れた確認がまだ EDT で実行されていなければ true。 */
    private val heartbeatPending = AtomicBoolean(false)

    /** 書き出し済みの停止が始まった時点の [lastResponse]。停止を書き出していなければ null。 */
    private var reportedStallStart: Duration? = null

    /** 見張りが前回確かめた時刻。見張りのスレッドだけが読み書きする。 */
    private var lastCheck: Duration = lastResponse

    /** 無応答を数え始める時刻。見張り自身が動いていなかった間を数えないために、復帰した時刻へ進める。 */
    private var countFrom: Duration = lastResponse

    /** 状態を 1 回確かめ、EDT のキューへ次の確認を入れる。 */
    fun check() {
        val current = now()
        if (current - lastCheck > UI_STALL_WATCHDOG_GAP) {
            log.i { "the stall watchdog itself did not run for ${(current - lastCheck).inWholeSeconds}s" }
            countFrom = current
        }
        lastCheck = current
        val responded = lastResponse
        val reportedFrom = reportedStallStart
        when {
            reportedFrom == null ->
                if (current - maxOf(responded, countFrom) > UI_STALL_THRESHOLD) reportStall(responded, current)
            responded > reportedFrom -> {
                log.i { "UI thread responded again after ${(responded - reportedFrom).inWholeSeconds}s" }
                reportedStallStart = null
            }
        }
        postHeartbeat()
    }

    /** 確認を [UI_STALL_POLL_INTERVAL] ごとに繰り返す。 */
    fun run(): Nothing {
        while (true) {
            check()
            sleep(UI_STALL_POLL_INTERVAL)
        }
    }

    private fun postHeartbeat() {
        if (!heartbeatPending.compareAndSet(false, true)) return
        postToEdt {
            lastResponse = now()
            heartbeatPending.set(false)
        }
    }

    private fun reportStall(responded: Duration, current: Duration) {
        reportedStallStart = responded
        val stalledSeconds = (current - maxOf(responded, countFrom)).inWholeSeconds
        try {
            val file = writeReport(captureStacks())
            log.w { "UI thread has not responded for ${stalledSeconds}s; wrote ${file.name}" }
        } catch (failed: IOException) {
            log.w { "UI thread has not responded for ${stalledSeconds}s; could not write the report (${failed.message})" }
        } catch (failed: Exception) {
            log.w { "UI thread has not responded for ${stalledSeconds}s; could not write the report (${failed::class.simpleName})" }
        }
    }
}

/**
 * UI スレッドの停止を見張るデーモンスレッドを始める（§3.3）。記録は [JvmPaths.logDir] の
 * `ui-stall-<UTC 時刻>.txt` へ書く。
 */
fun installUiStallWatchdog() {
    val log = Logger.withTag("UiStall")
    val watchdog = UiStallWatchdog(
        postToEdt = EventQueue::invokeLater,
        captureStacks = Thread::getAllStackTraces,
        writeReport = { stacks -> writeUiStallReport(JvmPaths.logDir, Instant.now(), stacks) },
        log = log,
    )
    thread(isDaemon = true, name = "ui-stall-watchdog") {
        try {
            watchdog.run()
        } catch (failed: Throwable) {
            log.e(failed) { "stopped watching the UI thread for a stall" }
            throw failed
        }
    }
}
