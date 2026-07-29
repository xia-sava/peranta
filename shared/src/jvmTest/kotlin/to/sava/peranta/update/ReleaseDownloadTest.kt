package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 上限の検証に使う小さな受け入れ量。 */
private const val SMALL_LIMIT_BYTES = 1_000L

class ReleaseDownloadTest {

    private fun copy(source: ByteArray, total: Long): Pair<ByteArray, List<Pair<Long, Long>>> {
        val output = ByteArrayOutputStream()
        val reports = mutableListOf<Pair<Long, Long>>()
        copyReportingProgress(ByteArrayInputStream(source), output, total) { received, reported ->
            reports += received to reported
        }
        return output.toByteArray() to reports
    }

    private fun clientResponding(body: ByteArray, announceLength: Boolean): HttpClient {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = if (announceLength) {
                    headersOf(HttpHeaders.ContentLength, body.size.toString())
                } else {
                    headersOf()
                },
            )
        }
        return HttpClient(engine)
    }

    private fun downloadTarget(): File =
        File.createTempFile("peranta-download", ".bin").apply { deleteOnExit() }

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

    /** 上限を超えたら書き写しをやめる（際限なく書き続ける応答からディスクを守る）。 */
    @Test
    fun stopsWritingBeyondLimit() {
        val source = ByteArray(300_000)
        val output = ByteArrayOutputStream()

        assertFailsWith<IOException> {
            copyReportingProgress(ByteArrayInputStream(source), output, 0, SMALL_LIMIT_BYTES) { _, _ -> }
        }

        assertTrue(output.size() <= SMALL_LIMIT_BYTES, "wrote ${output.size()} bytes")
    }

    /** 全体長が上限を超えると宣言する応答は、受け取る前に断る。 */
    @Test
    fun rejectsAnnouncedOversizeResponse() = runTest {
        val file = downloadTarget()

        assertFailsWith<IOException> {
            clientResponding(ByteArray(300_000), announceLength = true)
                .downloadToFile("https://example.com/peranta.msi", file, maxBytes = SMALL_LIMIT_BYTES)
        }
    }

    /** 途中で失敗したダウンロードは書きかけを残さない。 */
    @Test
    fun removesPartialFileOnFailure() = runTest {
        val file = downloadTarget()

        assertFailsWith<IOException> {
            clientResponding(ByteArray(300_000), announceLength = false)
                .downloadToFile("https://example.com/peranta.msi", file, maxBytes = SMALL_LIMIT_BYTES)
        }

        assertFalse(file.exists())
    }
}
