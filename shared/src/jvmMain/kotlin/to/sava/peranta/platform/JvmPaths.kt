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

/**
 * アプリデータのディレクトリ名。`%LOCALAPPDATA%\Peranta` は配布版のインストール先なので、
 * それとは別の名前を使う。
 */
private const val APP_DIR_NAME = "to.sava.peranta"

/** 移設前のアプリデータのディレクトリ名（`%APPDATA%` 直下）。 */
private const val LEGACY_APP_DIR_NAME = "Peranta"

/** 移設の途中経過を置くディレクトリの後置き。移し終えるまで本来の名前にしない。 */
private const val MIGRATION_STAGING_SUFFIX = ".migrating"

/**
 * Peranta のアプリ専用ディレクトリ群を解決する（Windows は `%LOCALAPPDATA%\to.sava.peranta`）。
 * 移動プロファイルやプロファイル単位のバックアップで端末外へ出る `%APPDATA%`（Roaming）は使わない（§11）。
 *
 * ここが行うのは置き場を引くことだけで、旧い置き場からの移設は [migrateAppDataIfNeeded] が行う。
 * 移設が済むまでは旧い置き場をそのまま指すため、移設しない経路（テスト・移設に失敗した端末）でも
 * 履歴は読める。
 */
object JvmPaths {

    /** アプリデータのルート。無ければ作成する。 */
    val appDir: File by lazy {
        currentAppDir(defaultAppDir(), legacyAppDir).also { it.mkdirs() }
    }

    /**
     * 移設前のアプリデータのルート。この環境に旧い置き場の概念が無ければ null。
     * 実在するとは限らない（移設済み・最初から新しい置き場で始めた場合は存在しない）。
     */
    val legacyAppDir: File? by lazy {
        System.getenv("APPDATA")?.let { File(it, LEGACY_APP_DIR_NAME) }
    }

    /**
     * 消去（§11）の対象になるアプリデータのルート一覧。通常は [appDir] だけだが、
     * 移設できずに旧い置き場が残っている場合はそちらも含める。
     */
    val dataDirs: List<File>
        get() = listOfNotNull(appDir, legacyAppDir?.takeIf { it.isDirectory && it != appDir })

    /** ログ出力ディレクトリ。無ければ作成する。 */
    val logDir: File by lazy {
        File(appDir, LOG_DIR_NAME).also { it.mkdirs() }
    }

    /** 復号済み添付のキャッシュディレクトリ（§4.3）。 */
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

/** この環境でのアプリデータの置き場。`%LOCALAPPDATA%` が無ければホームディレクトリ配下に置く。 */
private fun defaultAppDir(): File =
    System.getenv("LOCALAPPDATA")
        ?.let { File(it, APP_DIR_NAME) }
        ?: File(System.getProperty("user.home"), LEGACY_APP_DIR_NAME)

/**
 * 今どちらの置き場を使っているかを引く（§11）。[target] があればそれを、まだ無く [legacy] が残っていれば
 * [legacy] を指す。移設していない状態でも履歴を読めるようにするための判断で、ここでは何も移動しない。
 */
internal fun currentAppDir(target: File, legacy: File?): File = when {
    target.isDirectory -> target
    legacy?.isDirectory == true -> legacy
    else -> target
}

/**
 * 旧い置き場（`%APPDATA%\Peranta`）のアプリデータを新しい置き場へ移す（§11）。
 * **アプリの起動時に、データ領域を使い始める前に 1 度だけ呼ぶ。**
 */
fun migrateAppDataIfNeeded() {
    migrateAppData(defaultAppDir(), JvmPaths.legacyAppDir)
}

/**
 * [legacy] のアプリデータを [target] へ移す。同じボリュームなら付け替えで済み、そうでなければ
 * 複製してから置き換える。複製の途中経過は中断された形のまま [target] に残さない。
 * 移すものが無い・[target] が既にあるときは何もしない。
 * 移しきれなかったときも [legacy] はそのまま残り、[currentAppDir] がそちらを指し続ける
 * （**移設できないことより履歴が読めることを優先する**）。
 */
internal fun migrateAppData(target: File, legacy: File?) {
    if (legacy == null || legacy == target || !legacy.isDirectory || target.isDirectory) return
    val log = Logger.withTag("Paths")
    val staging = File(target.parentFile, "${target.name}$MIGRATION_STAGING_SUFFIX")
    staging.deleteRecursively()
    runCatching {
        if (!legacy.renameTo(target)) {
            legacy.copyRecursively(staging, overwrite = true)
            check(staging.renameTo(target)) { "could not place the migrated data directory" }
            if (!legacy.deleteRecursively()) log.w { "moved the data directory but could not remove the old one" }
        }
    }.onFailure {
        staging.deleteRecursively()
        log.w { "could not move the data directory; keeping the previous one (${it::class.simpleName})" }
    }
}

/**
 * 「すべての情報の消去」（§11）でアプリのデータ領域から消すもの。タイムライン（書き直し中の
 * 一時ファイルを含む）・復号済み添付・貼り付け画像・ログが対象で、設定と秘密は
 * [to.sava.peranta.config.ConfigRepository] 側が消す。移設できずに旧い置き場が残っている場合は
 * そちらにも同じ消去をかける。
 *
 * 消すのは Peranta が作る名前のファイルとディレクトリだけに限り、[appDirs] 配下でもそれ以外には触れない。
 * 更新の配布物を置く一時領域は通知・設定・鍵に由来しないため対象に含めず、取得と破棄の経路が自ら片づける。
 */
fun eraseAppData(appDirs: List<File> = JvmPaths.dataDirs) {
    val log = Logger.withTag("Reset")
    appDirs.flatMap { appDir ->
        buildList {
            add(File(appDir, TIMELINE_FILE_NAME))
            addAll(appDir.listFiles { file -> file.isFile && isTimelineTempFile(file.name) }.orEmpty())
            addAll(listOf(ATTACHMENTS_DIR_NAME, CLIPBOARD_DIR_NAME, LOG_DIR_NAME).map { File(appDir, it) })
        }
    }
        .filterNot { it.deleteRecursively() }
        .forEach { log.w { "failed to erase ${it.name}" } }
}

/** [name] がタイムラインの書き直しに使う一時ファイルの名前か。 */
internal fun isTimelineTempFile(name: String): Boolean =
    name.startsWith(TIMELINE_TEMP_PREFIX) && name.endsWith(TIMELINE_TEMP_SUFFIX)
