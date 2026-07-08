package to.sava.peranta.timeline

import to.sava.peranta.platform.JvmPaths
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 単一テキストファイルを 1 行 1 レコードとして読み書きする [TimelineFile]。 */
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
        val target = file.toPath()
        val tmp = Files.createTempFile(target.parent, "timeline", ".tmp")
        Files.write(tmp, content.encodeToByteArray())
        try {
            Files.move(
                tmp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

actual fun defaultTimelineFile(): TimelineFile = FileTimelineFile(JvmPaths.timelineFile)
