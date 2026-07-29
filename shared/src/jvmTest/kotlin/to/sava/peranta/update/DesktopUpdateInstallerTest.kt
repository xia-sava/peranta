package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopUpdateInstallerTest {

    /** 取得に成功した配布物。一時領域へ残さないよう、テストのあとで捨てる。 */
    private val downloads = mutableListOf<File>()

    @AfterTest
    fun discardDownloads() {
        downloads.forEach { discardDownload(it) }
    }

    private suspend fun DesktopUpdateInstaller.downloadTracked(
        url: String = "https://example.com/peranta.msi",
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): File = download(url, onProgress).also { downloads += it }

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

    /** https でホストを持つ URL だけを取得してよい。平文 http は拒否する。 */
    @Test
    fun acceptsHttpsAndRejectsCleartextHttp() {
        assertTrue(isDownloadableHttpsUrl("https://example.com/app.msi"))
        assertTrue(isDownloadableHttpsUrl("HTTPS://Example.com/app.msi"))
        assertFalse(isDownloadableHttpsUrl("http://example.com/app.msi"))
    }

    /** https 以外のスキームは拒否する（file・javascript 等）。 */
    @Test
    fun rejectsNonHttpsSchemes() {
        assertFalse(isDownloadableHttpsUrl("file:///etc/passwd"))
        assertFalse(isDownloadableHttpsUrl("javascript:alert(1)"))
        assertFalse(isDownloadableHttpsUrl("ftp://example.com/app.msi"))
    }

    /** スキーム・ホストを欠く、または不正な形式の URL は拒否する。 */
    @Test
    fun rejectsMalformedOrHostlessUrls() {
        assertFalse(isDownloadableHttpsUrl("example.com/app.msi"))
        assertFalse(isDownloadableHttpsUrl("https://"))
        assertFalse(isDownloadableHttpsUrl("https:// space"))
        assertFalse(isDownloadableHttpsUrl(""))
    }

    /** 配布物を一時領域へ落とし、中身をそのまま保存する。 */
    @Test
    fun downloadsBodyToFile() = runTest {
        val body = ByteArray(200_000) { (it % 253).toByte() }

        val file = installerFor(body).downloadTracked()

        assertEquals(body.size.toLong(), file.length())
        assertContentEquals(body, file.readBytes())
    }

    /** ダウンロード中は受信量を知らせ、最後に全体長と一致した値を渡す。 */
    @Test
    fun reportsDownloadProgress() = runTest {
        val body = ByteArray(200_000)
        val reports = mutableListOf<Pair<Long, Long>>()

        installerFor(body).downloadTracked { received, total ->
            reports += received to total
        }

        assertEquals(body.size.toLong() to body.size.toLong(), reports.last())
    }

    /** https でない URL は取得しない（平文 http も含めて拒否する）。 */
    @Test
    fun downloadRejectsNonHttpsUrl() = runTest {
        assertFailsWith<IOException> {
            installerFor(ByteArray(0)).download("file:///etc/passwd")
        }
        assertFailsWith<IOException> {
            installerFor(ByteArray(0)).download("http://example.com/peranta.msi")
        }
    }

    /** 2xx 以外の応答は失敗として扱う。 */
    @Test
    fun downloadFailsOnErrorStatus() = runTest {
        assertFailsWith<IOException> {
            installerFor(ByteArray(0), status = HttpStatusCode.NotFound)
                .download("https://example.com/peranta.msi")
        }
    }

    /** 配布物は取得のたびに別のディレクトリへ置く（照合済みの実体を差し替える隙を狭める）。 */
    @Test
    fun downloadsIntoFreshDirectoryEachTime() = runTest {
        val installer = installerFor(ByteArray(16))

        val first = installer.downloadTracked()
        val second = installer.downloadTracked()

        assertNotEquals(first.parentFile, second.parentFile)
    }

    /** 捨てた配布物は、置き場のディレクトリごと残さない。 */
    @Test
    fun discardRemovesDownloadDirectory() = runTest {
        val installer = installerFor(ByteArray(16))
        val msi = installer.downloadTracked()
        val dir = msi.parentFile

        discardDownload(msi)

        assertFalse(dir.exists())
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
        val file = installer.downloadTracked()

        assertFailsWith<IOException> { installer.launchInstaller(file) }
    }

    /**
     * 適用スクリプトを配布物の隣へ書き出し、自プロセスの終了待ち・配布物の適用・
     * ランチャーの再起動を並べる。
     */
    @Test
    fun writesApplyScriptBesideDownload() = runTest {
        val installer = installerFor(ByteArray(16))
        val msi = installer.downloadTracked()

        val script = installer.writeApplyScript(msi)

        assertEquals(msi.parentFile, script.parentFile)
        val body = script.readText()
        assertTrue(body.contains(ProcessHandle.current().pid().toString()), body)
        assertTrue(body.contains(msi.absolutePath), body)
        assertTrue(body.contains("""Start-Process 'C:\Apps\Peranta.exe'"""), body)
    }
}
