package to.sava.peranta.timeline

import to.sava.peranta.platform.AndroidApp
import java.io.File

/** アプリ内部ストレージ（filesDir）を 1 行 1 レコードとして読み書きする [TimelineFile]。 */
class FileTimelineFile(private val file: File) : TimelineFile {

    override fun readLines(): List<String> =
        if (file.exists()) file.readLines() else emptyList()

    override fun appendLine(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    override fun overwrite(lines: List<String>) {
        file.parentFile?.mkdirs()
        val content = lines.joinToString(separator = "") { "$it\n" }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(content)
            tmp.delete()
        }
    }
}

actual fun defaultTimelineFile(): TimelineFile =
    FileTimelineFile(File(AndroidApp.context.filesDir, "timeline.jsonl"))
