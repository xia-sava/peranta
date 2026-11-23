package to.sava.peranta.window

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiStallReportTest {

    private val dir: File = Files.createTempDirectory("peranta-ui-stall-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun frame(className: String, methodName: String, line: Int) =
        StackTraceElement(className, methodName, "Source.java", line)

    private fun reportNames(): List<String> = dir.list().orEmpty().sorted()

    /** 記録ファイルの名前は `ui-stall-<UTC 時刻>.txt`。時刻はファイル名に使える形になる。 */
    @Test
    fun `names the file after the UTC time`() {
        val file = writeUiStallReport(dir, Instant.parse("2026-11-21T08:06:30Z"), emptyMap())

        assertEquals("ui-stall-20261121T080630Z.txt", file.name)
        assertTrue(file.isFile)
    }

    /** 中身はスレッドごとに、名前・状態・デーモンかどうかと、スタックの各フレームのクラス名・メソッド名・行番号。 */
    @Test
    fun `lists the name state daemon flag and frames of every thread`() {
        val worker = Thread({}, "worker-1").apply { isDaemon = true }
        val main = Thread({}, "main-ui")
        val stacks = mapOf(
            worker to arrayOf(frame("a.b.Worker", "poll", 12), frame("a.b.Loop", "run", 34)),
            main to arrayOf(frame("a.b.Ui", "paint", 56)),
        )

        val text = formatUiStallReport(stacks)

        assertEquals(
            """
            "main-ui" state=NEW daemon=false
            ${'\t'}at a.b.Ui.paint(Source.java:56)

            "worker-1" state=NEW daemon=true
            ${'\t'}at a.b.Worker.poll(Source.java:12)
            ${'\t'}at a.b.Loop.run(Source.java:34)

            """.trimIndent(),
            text,
        )
    }

    /** 新しい順に [UI_STALL_REPORT_KEEP] 個だけ残し、古いものは書き出しの後に消す。無関係なファイルには触れない。 */
    @Test
    fun `keeps only the newest reports`() {
        val unrelated = File(dir, "app.log").also { it.writeText("keep") }
        (1..7).forEach { day ->
            writeUiStallReport(dir, Instant.parse("2026-11-0${day}T00:00:00Z"), emptyMap())
        }

        assertEquals(
            listOf(
                "app.log",
                "ui-stall-20261103T000000Z.txt",
                "ui-stall-20261104T000000Z.txt",
                "ui-stall-20261105T000000Z.txt",
                "ui-stall-20261106T000000Z.txt",
                "ui-stall-20261107T000000Z.txt",
            ),
            reportNames(),
        )
        assertTrue(unrelated.exists())
    }

    /** 残す数に満たないうちは何も消さない。 */
    @Test
    fun `removes nothing below the limit`() {
        (1..UI_STALL_REPORT_KEEP).forEach { day ->
            writeUiStallReport(dir, Instant.parse("2026-11-0${day}T00:00:00Z"), emptyMap())
        }

        assertEquals(UI_STALL_REPORT_KEEP, reportNames().size)
    }

    /** 書き出したばかりの記録は、古い記録を消す側に入らない。 */
    @Test
    fun `never prunes the report just written`() {
        (1..6).forEach { day ->
            writeUiStallReport(dir, Instant.parse("2026-11-1${day}T00:00:00Z"), emptyMap())
        }
        val newest = writeUiStallReport(dir, Instant.parse("2026-11-20T00:00:00Z"), emptyMap())

        assertTrue(newest.exists())
        assertFalse(File(dir, "ui-stall-20261111T000000Z.txt").exists())
        assertFalse(File(dir, "ui-stall-20261112T000000Z.txt").exists())
    }
}
