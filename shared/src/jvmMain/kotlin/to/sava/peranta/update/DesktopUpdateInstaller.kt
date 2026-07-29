package to.sava.peranta.update

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import to.sava.peranta.platform.AppPath
import to.sava.peranta.platform.JvmPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** 配布物と適用スクリプトを置く一時ディレクトリの接頭辞。実際の名前は取得のたびに変わる。 */
private const val DOWNLOAD_DIR_PREFIX = "peranta-update"

/** ダウンロードした配布物のファイル名。 */
private const val MSI_FILE_NAME = "peranta-update.msi"

/** 適用スクリプトのファイル名。 */
private const val APPLY_SCRIPT_NAME = "peranta-apply-update.ps1"

/** 適用の結果を書き残すファイル名。 */
private const val APPLY_LOG_NAME = "update-apply.log"

/** 適用スクリプトが自プロセスの終了を待つ上限（秒）。 */
private const val EXIT_WAIT_SECONDS = 60

/** PowerShell 本体の %SystemRoot% からの位置。 */
private const val POWERSHELL_RELATIVE_PATH = """System32\WindowsPowerShell\v1.0\powershell.exe"""

/** Windows Installer の %SystemRoot% からの位置。 */
private const val MSIEXEC_RELATIVE_PATH = """System32\msiexec.exe"""

/** PowerShell のリテラル文字列にする。引用符を含む値でも壊れないようエスケープする。 */
private fun powerShellLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

/**
 * 適用スクリプトの中身を組み立てる（純粋関数、§12）。
 *
 * トレイ常駐アプリはウィンドウを閉じても終了しないため、Windows Installer の Restart Manager では
 * 止められず「使用中のファイルがある」の確認待ちで止まってしまう。そこで適用はアプリの外へ出し、
 * [waitPids] の終了を待ってから msiexec を無人モードで走らせ、完了後に [launcherPath] を起動する。
 *
 * 起動する実行ファイルは %SystemRoot% から組んだ絶対パスで指す。msiexec の終了コードは
 * [resultLogPath] へ書き残し、成功したときだけランチャーを起動する。適用中はアプリが終了しているため、
 * 成否はこのファイルだけが知る。
 */
fun applyUpdateScript(
    msiPath: String,
    launcherPath: String,
    resultLogPath: String,
    waitPids: List<Long>,
    waitSeconds: Int = EXIT_WAIT_SECONDS,
): String {
    val pids = waitPids.joinToString(",")
    return """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}log = ${powerShellLiteral(resultLogPath)}
        foreach (${'$'}id in @($pids)) { Wait-Process -Id ${'$'}id -Timeout $waitSeconds -ErrorAction SilentlyContinue }
        ${'$'}msiexec = Join-Path ${'$'}env:SystemRoot ${powerShellLiteral(MSIEXEC_RELATIVE_PATH)}
        try {
            ${'$'}installer = Start-Process ${'$'}msiexec -ArgumentList '/i', ${powerShellLiteral(msiPath)}, '/qn' -Wait -PassThru
            ${'$'}code = ${'$'}installer.ExitCode
        } catch {
            Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) msiexec could not be started: ${'$'}_"
            exit 1
        }
        if (${'$'}code -ne 0) {
            Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) msiexec failed with exit code ${'$'}code"
            exit ${'$'}code
        }
        Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) msiexec succeeded"
        Start-Process ${powerShellLiteral(launcherPath)}
    """.trimIndent() + "\n"
}

/**
 * ダウンロードした配布物と、それを置いた一時ディレクトリを捨てる。
 * 適用しなかった配布物と適用スクリプトを残さない。
 */
fun discardDownload(msi: File) {
    msi.delete()
    msi.parentFile
        ?.takeIf { it.name.startsWith(DOWNLOAD_DIR_PREFIX) }
        ?.deleteRecursively()
}

/**
 * Desktop の自己更新（§12）。配布物を落として一時領域に置き、アプリの外で走る適用スクリプトへ引き渡す。
 * [appPath] は配布物の実行ファイル（[AppPath.verified]）で、開発実行や検証に落ちた値では null になるため
 * 適用できない。
 */
class DesktopUpdateInstaller(
    private val httpClient: HttpClient,
    private val appPath: String? = AppPath.verified,
    private val log: Logger = Logger.withTag("UpdateInstaller"),
) {

    /** 配布物として適用できる実行形態か（実行ファイルのパスが判っているか）。 */
    val isSupported: Boolean
        get() = !appPath.isNullOrBlank()

    /**
     * 配布物をダウンロードして一時領域へ置く。数十 MB あるため、受信量を [onProgress] へ
     * 逐次知らせる（全体長が判らなければ 0 を渡す）。
     * 置き場は取得のたびに作り直し、照合の済んだ配布物を別のプロセスが差し替える隙を狭める。
     */
    suspend fun download(url: String, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }): File {
        val dir = Files.createTempDirectory(DOWNLOAD_DIR_PREFIX).toFile()
        val file = File(dir, MSI_FILE_NAME)
        try {
            httpClient.downloadToFile(url, file, onProgress = onProgress)
        } catch (error: Exception) {
            discardDownload(file)
            throw error
        }
        log.i { "update downloaded (${file.length()} bytes)" }
        return file
    }

    /** 適用スクリプトを配布物と同じ場所へ書き出す。実行ファイルのパスが判らなければ書けない。 */
    internal fun writeApplyScript(msi: File): File {
        val launcherPath = appPath
        if (launcherPath.isNullOrBlank()) {
            throw IOException("cannot apply update: launcher path is unknown")
        }
        val script = File(msi.parentFile, APPLY_SCRIPT_NAME)
        val resultLogPath = File(JvmPaths.logDir, APPLY_LOG_NAME).absolutePath
        script.writeText(
            applyUpdateScript(msi.absolutePath, launcherPath, resultLogPath, ownProcessIds()),
            Charsets.UTF_8,
        )
        return script
    }

    /**
     * 適用スクリプトを起動する。スクリプトは自プロセスの終了を待つので、呼び出し側は
     * この後にアプリを終了させる。終了しない限りインストールは始まらない。
     */
    fun launchInstaller(msi: File) {
        val script = writeApplyScript(msi)
        ProcessBuilder(
            powerShellPath(),
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-File", script.absolutePath,
        ).start()
        log.i { "update installer handed off" }
    }

    /** 適用スクリプトを走らせる PowerShell 本体。PATH の解決に委ねず %SystemRoot% から組む。 */
    private fun powerShellPath(): String =
        System.getenv("SystemRoot")
            ?.let { File(it, POWERSHELL_RELATIVE_PATH) }
            ?.takeIf { it.isFile }
            ?.absolutePath
            ?: throw IOException("cannot apply update: powershell not found under %SystemRoot%")

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
