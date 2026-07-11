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

    /**
     * 復号済み添付のキャッシュディレクトリ（%LOCALAPPDATA%\Peranta\attachments、§4.3）。
     * ローミングを避けるため設定・ログ（%APPDATA%）とは別に LOCALAPPDATA 配下へ置く。
     */
    val attachmentsDir: File by lazy {
        val base = System.getenv("LOCALAPPDATA")
            ?: System.getenv("APPDATA")
            ?: System.getProperty("user.home")
        File(File(base, "Peranta"), "attachments").also { it.mkdirs() }
    }

    /** タイムライン JSONL ファイルのパス。 */
    val timelineFile: File
        get() = File(appDir, "timeline.jsonl")
}
