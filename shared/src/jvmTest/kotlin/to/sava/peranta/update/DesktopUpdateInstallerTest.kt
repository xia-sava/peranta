package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateInstallerTest {

    private fun installerFor(
        body: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
        appPath: String? = """C:\Apps\Peranta.exe""",
    ): DesktopUpdateInstaller {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentLength, body.size.toString()),
            )
        }
        return DesktopUpdateInstaller(HttpClient(engine), appPath = appPath)
    }

    /** http/https でホストを持つ URL は開いてよい。 */
    @Test
    fun acceptsHttpAndHttps() {
        assertTrue(isBrowsableHttpUrl("http://example.com/app.msi"))
        assertTrue(isBrowsableHttpUrl("https://example.com/app.msi"))
        assertTrue(isBrowsableHttpUrl("HTTPS://Example.com/app.msi"))
    }

    /** http/https 以外のスキームは拒否する（file・javascript 等）。 */
    @Test
    fun rejectsNonHttpSchemes() {
        assertFalse(isBrowsableHttpUrl("file:///etc/passwd"))
        assertFalse(isBrowsableHttpUrl("javascript:alert(1)"))
        assertFalse(isBrowsableHttpUrl("ftp://example.com/app.msi"))
    }

    /** スキーム・ホストを欠く、または不正な形式の URL は拒否する。 */
    @Test
    fun rejectsMalformedOrHostlessUrls() {
        assertFalse(isBrowsableHttpUrl("example.com/app.msi"))
        assertFalse(isBrowsableHttpUrl("http://"))
        assertFalse(isBrowsableHttpUrl("http:// space"))
        assertFalse(isBrowsableHttpUrl(""))
    }

    /** 配布物を一時領域へ落とし、中身をそのまま保存する。 */
    @Test
    fun downloadsBodyToFile() = runTest {
        val body = ByteArray(200_000) { (it % 253).toByte() }

        val file = installerFor(body).download("https://example.test/peranta.msi")

        assertEquals(body.size.toLong(), file.length())
        assertContentEquals(body, file.readBytes())
    }

    /** ダウンロード中は受信量を知らせ、最後に全体長と一致した値を渡す。 */
    @Test
    fun reportsDownloadProgress() = runTest {
        val body = ByteArray(200_000)
        val reports = mutableListOf<Pair<Long, Long>>()

        installerFor(body).download("https://example.test/peranta.msi") { received, total ->
            reports += received to total
        }

        assertEquals(body.size.toLong() to body.size.toLong(), reports.last())
    }

    /** http/https でない URL は取得しない（latest.json 由来の外部入力を信用しない）。 */
    @Test
    fun downloadRejectsNonHttpUrl() = runTest {
        assertFailsWith<IOException> {
            installerFor(ByteArray(0)).download("file:///etc/passwd")
        }
    }

    /** 2xx 以外の応答は失敗として扱う。 */
    @Test
    fun downloadFailsOnErrorStatus() = runTest {
        assertFailsWith<IOException> {
            installerFor(ByteArray(0), status = HttpStatusCode.NotFound)
                .download("https://example.test/peranta.msi")
        }
    }

    /** 実行ファイルのパスが判らない開発実行では適用できない。 */
    @Test
    fun isSupportedReflectsAppPath() {
        assertFalse(installerFor(ByteArray(0), appPath = null).isSupported)
        assertTrue(installerFor(ByteArray(0)).isSupported)
    }

    /** 実行ファイルのパスが無いまま引き渡そうとしたら、黙って諦めず失敗させる。 */
    @Test
    fun launchInstallerFailsWithoutAppPath() = runTest {
        val installer = installerFor(ByteArray(16), appPath = null)
        val file = installer.download("https://example.test/peranta.msi")

        assertFailsWith<IOException> { installer.launchInstaller(file) }
    }

    /**
     * 適用スクリプトを配布物の隣へ書き出し、自プロセスの終了待ち・配布物の適用・
     * ランチャーの再起動を並べる。
     */
    @Test
    fun writesApplyScriptBesideDownload() = runTest {
        val installer = installerFor(ByteArray(16))
        val msi = installer.download("https://example.test/peranta.msi")

        val script = installer.writeApplyScript(msi)

        assertEquals(msi.parentFile, script.parentFile)
        val body = script.readText()
        assertTrue(body.contains(ProcessHandle.current().pid().toString()), body)
        assertTrue(body.contains(msi.absolutePath), body)
        assertTrue(body.contains("""Start-Process 'C:\Apps\Peranta.exe'"""), body)
    }
}
