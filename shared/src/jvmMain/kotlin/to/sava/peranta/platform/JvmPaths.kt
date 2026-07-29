package to.sava.peranta.platform

import co.touchlab.kermit.Logger
import java.io.File

/** タイムライン JSONL のファイル名。 */
private const val TIMELINE_FILE_NAME = "timeline.jsonl"

/** タイムラインを書き直すときの一時ファイルの名前（[java.nio.file.Files.createTempFile] へ渡す前置きと後置き）。 */
internal const val TIMELINE_TEMP_PREFIX = "timeline"
internal const val TIMELINE_TEMP_SUFFIX = ".tmp"

private const val LOG_DIR_NAME = "logs"
private const val ATTACHMENTS_DIR_NAME = "attachments"
private const val CLIPBOARD_DIR_NAME = "clipboard"

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
        File(appDir, LOG_DIR_NAME).also { it.mkdirs() }
    }

    /**
     * 復号済み添付のキャッシュディレクトリ（%APPDATA%\Peranta\attachments、§4.3）。
     * %LOCALAPPDATA%\Peranta は配布版のインストール先なので、アプリのファイルはそこへ置かない。
     */
    val attachmentsDir: File by lazy {
        File(appDir, ATTACHMENTS_DIR_NAME).also { it.mkdirs() }
    }

    /**
     * 貼り付けた画像を送信までのあいだ置くディレクトリ（§10.1）。
     * 送る中身の平文コピーなので、消去の届かない %TEMP% ではなくアプリのデータ領域に置く（§11）。
     */
    val clipboardImagesDir: File by lazy {
        File(appDir, CLIPBOARD_DIR_NAME).also { it.mkdirs() }
    }

    /** タイムライン JSONL ファイルのパス。 */
    val timelineFile: File
        get() = File(appDir, TIMELINE_FILE_NAME)
}

/**
 * 「すべての情報の消去」（§11）でアプリのデータ領域から消すもの。タイムライン（書き直し中の
 * 一時ファイルを含む）・復号済み添付・貼り付け画像・ログが対象で、設定と秘密は
 * [to.sava.peranta.config.ConfigRepository] 側が消す。
 *
 * 消すのは Peranta が作る名前のファイルとディレクトリだけに限り、[appDir] 配下でもそれ以外には触れない。
 * 更新の配布物を置く一時領域は通知・設定・鍵に由来しないため対象に含めず、取得と破棄の経路が自ら片づける。
 */
fun eraseAppData(appDir: File = JvmPaths.appDir) {
    val log = Logger.withTag("Reset")
    buildList {
        add(File(appDir, TIMELINE_FILE_NAME))
        addAll(appDir.listFiles { file -> file.isFile && isTimelineTempFile(file.name) }.orEmpty())
        addAll(listOf(ATTACHMENTS_DIR_NAME, CLIPBOARD_DIR_NAME, LOG_DIR_NAME).map { File(appDir, it) })
    }
        .filterNot { it.deleteRecursively() }
        .forEach { log.w { "failed to erase ${it.name}" } }
}

/** [name] がタイムラインの書き直しに使う一時ファイルの名前か。 */
internal fun isTimelineTempFile(name: String): Boolean =
    name.startsWith(TIMELINE_TEMP_PREFIX) && name.endsWith(TIMELINE_TEMP_SUFFIX)
