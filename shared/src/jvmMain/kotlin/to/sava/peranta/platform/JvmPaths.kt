package to.sava.peranta.platform

import java.io.File

/** Peranta のアプリ専用ディレクトリ群を解決する（Windows は %APPDATA%\Peranta）。 */
object JvmPaths {

    /** アプリデータのルート。無ければ作成する。 */
    val appDir: File by lazy {
        val base = System.getenv("APPDATA")
            ?: System.getProperty("user.home")
        File(base, "Peranta").also { it.mkdirs() }
    }

    /** ログ出力ディレクトリ。無ければ作成する。 */
    val logDir: File by lazy {
        File(appDir, "logs").also { it.mkdirs() }
    }

    /** タイムライン JSONL ファイルのパス。 */
    val timelineFile: File
        get() = File(appDir, "timeline.jsonl")
}
