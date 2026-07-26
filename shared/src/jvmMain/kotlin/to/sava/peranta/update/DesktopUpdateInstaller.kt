package to.sava.peranta.update

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** 取得を許す URL スキーム。 */
private val ALLOWED_SCHEMES = setOf("http", "https")

/** 配布物と適用スクリプトを置く一時ディレクトリ名。 */
private const val DOWNLOAD_DIR = "peranta-update"

/** ダウンロードした配布物のファイル名。 */
private const val MSI_FILE_NAME = "peranta-update.msi"

/** 適用スクリプトのファイル名。 */
private const val APPLY_SCRIPT_NAME = "peranta-apply-update.ps1"

/** 適用スクリプトが自プロセスの終了を待つ上限（秒）。 */
private const val EXIT_WAIT_SECONDS = 60

/**
 * [url] が http/https かつホストを持つ、取得してよい形式かを判定する（純粋関数）。
 * latest.json 由来の外部入力を扱う前の検証に使う。
 */
fun isBrowsableHttpUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return false
    }
    return uri.scheme?.lowercase() in ALLOWED_SCHEMES && !uri.host.isNullOrEmpty()
}

/** PowerShell のリテラル文字列にする。引用符を含む値でも壊れないようエスケープする。 */
private fun powerShellLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

/**
 * 適用スクリプトの中身を組み立てる（純粋関数、§12）。
 *
 * トレイ常駐アプリはウィンドウを閉じても終了しないため、Windows Installer の Restart Manager では
 * 止められず「使用中のファイルがある」の確認待ちで止まってしまう。そこで適用はアプリの外へ出し、
 * [waitPids] の終了を待ってから msiexec を無人モードで走らせ、完了後に [launcherPath] を起動する。
 */
fun applyUpdateScript(
    msiPath: String,
    launcherPath: String,
    waitPids: List<Long>,
    waitSeconds: Int = EXIT_WAIT_SECONDS,
): String {
    val pids = waitPids.joinToString(",")
    return """
        ${'$'}ErrorActionPreference = 'SilentlyContinue'
        foreach (${'$'}id in @($pids)) { Wait-Process -Id ${'$'}id -Timeout $waitSeconds }
        Start-Process msiexec -ArgumentList '/i', ${powerShellLiteral(msiPath)}, '/qn' -Wait
        Start-Process ${powerShellLiteral(launcherPath)}
    """.trimIndent() + "\n"
}

/**
 * Desktop の自己更新（§12）。配布物を落として一時領域に置き、アプリの外で走る適用スクリプトへ引き渡す。
 * [appPath] は配布物の実行ファイル（jpackage の `jpackage.app-path`）で、開発実行では null になるため
 * 適用できない。
 */
class DesktopUpdateInstaller(
    private val httpClient: HttpClient,
    private val appPath: String? = System.getProperty("jpackage.app-path"),
    private val log: Logger = Logger.withTag("UpdateInstaller"),
) {

    /** 配布物として適用できる実行形態か（実行ファイルのパスが判っているか）。 */
    val isSupported: Boolean
        get() = !appPath.isNullOrBlank()

    /** 配布物をダウンロードして一時領域へ置く。 */
    suspend fun download(url: String): File {
        if (!isBrowsableHttpUrl(url)) {
            throw IOException("update url rejected: not a http(s) url")
        }
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw IOException("msi download failed: HTTP ${response.status.value}")
        }
        val dir = File(System.getProperty("java.io.tmpdir"), DOWNLOAD_DIR).apply { mkdirs() }
        val file = File(dir, MSI_FILE_NAME)
        response.bodyAsChannel().toInputStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        log.i { "update downloaded (${file.length()} bytes)" }
        return file
    }

    /**
     * 適用スクリプトを起動する。スクリプトは自プロセスの終了を待つので、呼び出し側は
     * この後にアプリを終了させる。終了しない限りインストールは始まらない。
     */
    fun launchInstaller(msi: File) {
        val launcherPath = appPath
        if (launcherPath.isNullOrBlank()) {
            throw IOException("cannot apply update: launcher path is unknown")
        }
        val script = File(msi.parentFile, APPLY_SCRIPT_NAME)
        script.writeText(applyUpdateScript(msi.absolutePath, launcherPath, ownProcessIds()), Charsets.UTF_8)
        ProcessBuilder(
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-File", script.absolutePath,
        ).start()
        log.i { "update installer handed off" }
    }

    /**
     * 適用スクリプトが終了を待つプロセス。JVM 自身に加え、配布物では JVM を起こしたランチャー
     * （`Peranta.exe`）も実行ファイルを掴んでいるため一緒に待つ。
     */
    private fun ownProcessIds(): List<Long> {
        val current = ProcessHandle.current()
        val parent = current.parent().orElse(null)?.pid()
        return listOfNotNull(current.pid(), parent)
    }
}
