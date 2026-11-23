package to.sava.peranta.window

import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 時刻と、EDT のキューへ入れた確認の実行を手元で進める、[UiStallWatchdog] の注入先。 */
private class Harness(private val writeFailure: (() -> Exception)? = null) {
    var clock: Duration = Duration.ZERO
    val posted = mutableListOf<Runnable>()
    val writtenReports = mutableListOf<File>()
    val logWriter = RecordingLogWriter()

    val watchdog = UiStallWatchdog(
        postToEdt = { posted += it },
        captureStacks = { mapOf(Thread.currentThread() to arrayOf<StackTraceElement>()) },
        writeReport = {
            writeFailure?.let { throw it() }
            File("ui-stall-${writtenReports.size}.txt").also { writtenReports += it }
        },
        now = { clock },
        log = recordingLogger(logWriter, "UiStall"),
    )

    /** 時刻を [step] 進めてから確認する。 */
    fun tick(step: Duration = UI_STALL_POLL_INTERVAL) {
        clock += step
        watchdog.check()
    }

    /** EDT が、キューに入っている確認を今の時刻で実行する。 */
    fun edtResponds() {
        posted.toList().also { posted.clear() }.forEach(Runnable::run)
    }
}

class UiStallWatchdogTest {

    /** EDT が確認のたびに応答していれば、何時間経っても書き出さない。 */
    @Test
    fun `writes nothing while the UI thread keeps responding`() {
        val harness = Harness()

        repeat(100) {
            harness.tick()
            harness.edtResponds()
        }

        assertEquals(emptyList(), harness.writtenReports)
        assertEquals(emptyList(), harness.logWriter.recorded)
    }

    /** 最後の応答から [UI_STALL_THRESHOLD] ちょうどでは停止とみなさず、超えた確認で書き出す。 */
    @Test
    fun `treats only more than the threshold as a stall`() {
        val harness = Harness()
        harness.tick(Duration.ZERO)

        repeat((UI_STALL_THRESHOLD / UI_STALL_POLL_INTERVAL).toInt()) { harness.tick() }
        assertEquals(0, harness.writtenReports.size)

        harness.tick(1.seconds)
        assertEquals(1, harness.writtenReports.size)
    }

    /** 応答が無いまま続いても、書き出すのは 1 回だけ。ログも検知の 1 行だけで、書き出したファイル名を含む。 */
    @Test
    fun `writes once for one stall and names the file in the log`() {
        val harness = Harness()
        harness.tick(Duration.ZERO)

        repeat(20) { harness.tick() }

        assertEquals(1, harness.writtenReports.size)
        val lines = harness.logWriter.recorded
        assertEquals(1, lines.size, "$lines")
        assertTrue(lines.single().contains("ui-stall-0.txt"), "$lines")
    }

    /** 停止中は新しい確認を EDT のキューへ入れず、入れてある確認が積み上がらない。 */
    @Test
    fun `does not pile up heartbeats while the UI thread is stalled`() {
        val harness = Harness()

        repeat(20) { harness.tick() }

        assertEquals(1, harness.posted.size)
    }

    /** 応答が戻ったら、止まっていた秒数をログに残す。次の停止ではまた書き出す。 */
    @Test
    fun `logs the stalled seconds on recovery and writes again for the next stall`() {
        val harness = Harness()
        harness.tick(Duration.ZERO)
        repeat(8) { harness.tick() }
        assertEquals(1, harness.writtenReports.size)

        harness.clock += 2.seconds
        harness.edtResponds()
        harness.tick(3.seconds)

        assertTrue(harness.logWriter.recorded.last().contains("responded again after 42s"), "${harness.logWriter.recorded}")

        repeat(8) { harness.tick() }
        assertEquals(2, harness.writtenReports.size)
    }

    /** 応答が戻った後に再び確認をキューへ入れる。戻らないうちに停止を書き出した状態は残らない。 */
    @Test
    fun `resumes heartbeats after recovery`() {
        val harness = Harness()
        harness.tick(Duration.ZERO)
        repeat(8) { harness.tick() }
        harness.edtResponds()

        harness.tick()

        assertEquals(1, harness.posted.size)
    }

    /** 書き出しに失敗しても例外を投げず、警告を残す。同じ停止では再試行せず、次の停止ではまた書き出しを試みる。 */
    @Test
    fun `keeps watching after a failed write`() {
        val harness = Harness(writeFailure = { IOException("disk full") })
        harness.tick(Duration.ZERO)

        repeat(8) { harness.tick() }

        val warnings = harness.logWriter.recorded
        assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().startsWith("Warn") && warnings.single().contains("could not write"), "$warnings")

        harness.edtResponds()
        harness.tick()
        assertTrue(harness.logWriter.recorded.last().contains("responded again"), "${harness.logWriter.recorded}")
        repeat(8) { harness.tick() }
        assertEquals(3, harness.logWriter.recorded.size, "${harness.logWriter.recorded}")
    }

    /** 入出力以外の例外で書き出しに失敗しても、例外を投げずに警告を残し、見張りを続ける。 */
    @Test
    fun `keeps watching after an unexpected failure while writing`() {
        val harness = Harness(writeFailure = { IllegalStateException("unexpected") })
        harness.tick(Duration.ZERO)

        repeat(8) { harness.tick() }

        val warnings = harness.logWriter.recorded
        assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().contains("could not write the report (IllegalStateException)"), "$warnings")
    }

    /**
     * 見張り自身の確認の間が空いたとき（PC のスリープからの復帰）は、それより前の無応答を停止に数えない。
     * 復帰の直後に EDT が応答すれば、何も書き出さない。
     */
    @Test
    fun `does not treat a sleep of the whole process as a stall`() {
        val harness = Harness()
        harness.tick(Duration.ZERO)
        harness.edtResponds()

        harness.tick(UI_STALL_THRESHOLD * 960)
        harness.edtResponds()
        repeat(10) {
            harness.tick()
            harness.edtResponds()
        }

        assertEquals(emptyList(), harness.writtenReports)
        assertTrue(harness.logWriter.recorded.none { it.startsWith("Warn") }, "${harness.logWriter.recorded}")
    }

    /** 見張り自身の確認の間が空いたあとも応答が無ければ、空いたあとの時刻から閾値を超えたところで書き出す。 */
    @Test
    fun `counts a stall from the time the watchdog itself resumed`() {
        val harness = Harness()
        harness.tick(Duration.ZERO)
        harness.edtResponds()

        harness.tick(UI_STALL_THRESHOLD * 960)
        repeat((UI_STALL_THRESHOLD / UI_STALL_POLL_INTERVAL).toInt()) { harness.tick() }
        assertEquals(0, harness.writtenReports.size)

        harness.tick(1.seconds)
        assertEquals(1, harness.writtenReports.size)
    }

    /** 見張りは確認のあいだに [UI_STALL_POLL_INTERVAL] 待つ。 */
    @Test
    fun `sleeps the poll interval between checks`() {
        val slept = mutableListOf<Duration>()
        val watchdog = UiStallWatchdog(
            postToEdt = {},
            captureStacks = { emptyMap() },
            writeReport = { File("unused") },
            sleep = {
                slept += it
                if (slept.size == 3) throw StopRunning()
            },
        )

        try {
            watchdog.run()
        } catch (_: StopRunning) {
        }

        assertEquals(List(3) { UI_STALL_POLL_INTERVAL }, slept)
    }

    private class StopRunning : RuntimeException()
}
