package to.sava.peranta.timeline

import co.touchlab.kermit.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** タイムラインのファイル名。 */
private const val TIMELINE_FILE_NAME = "timeline.jsonl"

/** タイムラインを書き直すときの一時ファイルの名前（[Files.createTempFile] へ渡す前置きと後置き）。 */
private const val TIMELINE_TEMP_PREFIX = "timeline"
private const val TIMELINE_TEMP_SUFFIX = ".tmp"

/** [dir] に置くタイムラインのファイル。 */
internal fun timelineFileIn(dir: File): File = File(dir, TIMELINE_FILE_NAME)

/**
 * [name] がタイムラインの書き直しに使う一時ファイルの名前か。
 *
 * 乱数名の一時ファイルのほか、固定名 `timeline.jsonl.tmp` の一時ファイルにも当たる。
 * 後者は、一時ファイルを固定名で作っていた版が書き直しの途中で止められたときに残る。
 */
internal fun isTimelineTempFile(name: String): Boolean =
    name.startsWith(TIMELINE_TEMP_PREFIX) && name.endsWith(TIMELINE_TEMP_SUFFIX)

/** [dir] 直下にあるタイムラインの一時ファイル（[isTimelineTempFile] に当たる通常のファイル）。 */
internal fun timelineTempFiles(dir: File): List<File> =
    dir.listFiles { file -> file.isFile && isTimelineTempFile(file.name) }.orEmpty().toList()

/**
 * [dir] 直下のタイムラインの一時ファイルを消し、消せなかったものを返す。
 * [dir] 内のそれ以外のファイルには触れない。
 */
internal fun deleteTimelineTempFiles(dir: File): List<File> =
    timelineTempFiles(dir).filterNot { it.delete() || !it.exists() }

/**
 * [dir] のタイムライン（履歴の本体と書き直し中の一時ファイル）を消し、消せなかったものを返す。
 * 一時ファイルを経ないので、書き直しの失敗に左右されない。[dir] 内のそれ以外のファイルには触れない。
 */
internal fun deleteTimelineFiles(dir: File): List<File> =
    (listOf(timelineFileIn(dir)) + timelineTempFiles(dir)).filterNot { it.delete() || !it.exists() }

/**
 * 単一テキストファイルを 1 行 1 レコード（UTF-8、改行は `\n`）として読み書きする [TimelineFile]。
 *
 * [overwrite] は同じディレクトリの一時ファイルへ全部書いてから原子的に置き換えるため、
 * 途中で止められても履歴が半端な内容で置き換わることはない。
 */
class FileTimelineFile(private val file: File) : TimelineFile {

    private val log = Logger.withTag("TimelineFile")

    override fun readLines(): List<String> =
        if (file.exists()) file.readLines() else emptyList()

    override fun appendLine(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    /**
     * 全内容を [lines] で置き換える。
     *
     * 先に、書き直しの途中で止められて残った一時ファイルを消す。そのうえで新しい一時ファイルへ書き、
     * 原子的に置き換える。原子的な置き換えができないときも、書き込みか置き換えに失敗したときも、
     * 一時ファイルを消してから例外を投げ、元のファイルには触れない。
     */
    override fun overwrite(lines: List<String>): Unit = overwriteLock.withLock {
        val target = file.absoluteFile.toPath()
        val dir = target.parent
        Files.createDirectories(dir)
        deleteTimelineTempFiles(dir.toFile()).forEach { log.w { "failed to delete a leftover temp file ${it.name}" } }

        val content = lines.joinToString(separator = "") { "$it\n" }
        val tmp = Files.createTempFile(dir, TIMELINE_TEMP_PREFIX, TIMELINE_TEMP_SUFFIX)
        try {
            Files.write(tmp, content.encodeToByteArray())
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            try {
                Files.deleteIfExists(tmp)
            } catch (cleanup: IOException) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private companion object {
        /**
         * 書き直しの排他。別の [FileTimelineFile] が書き直している最中の一時ファイルを、
         * 止められて残ったものと取り違えて消さないようにする。
         */
        val overwriteLock = ReentrantLock()
    }
}
