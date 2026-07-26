package to.sava.peranta.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseDownloadTest {

    private fun copy(source: ByteArray, total: Long): Pair<ByteArray, List<Pair<Long, Long>>> {
        val output = ByteArrayOutputStream()
        val reports = mutableListOf<Pair<Long, Long>>()
        copyReportingProgress(ByteArrayInputStream(source), output, total) { received, reported ->
            reports += received to reported
        }
        return output.toByteArray() to reports
    }

    /** 中身を余さず書き写す。 */
    @Test
    fun copiesAllBytes() {
        val source = ByteArray(300_000) { (it % 251).toByte() }

        val (copied, _) = copy(source, source.size.toLong())

        assertContentEquals(source, copied)
    }

    /** 完了時には必ず受信量と全体長を知らせる。 */
    @Test
    fun reportsFinalProgress() {
        val source = ByteArray(300_000)

        val (_, reports) = copy(source, source.size.toLong())

        assertEquals(source.size.toLong() to source.size.toLong(), reports.last())
    }

    /** 小さい入力では通知を間引き、完了の 1 回だけにする。 */
    @Test
    fun throttlesReportsForSmallInput() {
        val source = ByteArray(1_000)

        val (_, reports) = copy(source, source.size.toLong())

        assertEquals(1, reports.size)
    }

    /** 通知の間隔を超える入力では途中経過も知らせる。 */
    @Test
    fun reportsIntermediateProgressForLargeInput() {
        val source = ByteArray(3 * 1024 * 1024)

        val (_, reports) = copy(source, source.size.toLong())

        assertTrue(reports.size > 1, "expected intermediate reports but got ${reports.size}")
    }

    /** 全体長が判らないときは 0 をそのまま渡す（受信量だけを表示させる）。 */
    @Test
    fun passesUnknownTotalThrough() {
        val source = ByteArray(1_000)

        val (_, reports) = copy(source, 0)

        assertEquals(1_000L to 0L, reports.last())
    }
}
