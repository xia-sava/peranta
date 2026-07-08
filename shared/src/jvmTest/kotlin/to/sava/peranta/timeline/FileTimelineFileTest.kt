package to.sava.peranta.timeline

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTimelineFileTest {

    private val dir: File = Files.createTempDirectory("peranta-tl").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** 存在しないファイルの readLines は空リストを返す。 */
    @Test
    fun readLinesOnMissingFileIsEmpty() {
        val tf = FileTimelineFile(File(dir, "missing.jsonl"))
        assertEquals(emptyList(), tf.readLines())
    }

    /** appendLine は親ディレクトリを作りつつ 1 行ずつ追記し、readLines で順序どおり読み戻せる。 */
    @Test
    fun appendCreatesParentDirsAndPreservesOrder() {
        val tf = FileTimelineFile(File(dir, "nested/sub/timeline.jsonl"))
        tf.appendLine("a")
        tf.appendLine("b")
        assertEquals(listOf("a", "b"), tf.readLines())
    }

    /** overwrite は全内容を差し替え、末尾は改行終端で書かれる。 */
    @Test
    fun overwriteReplacesAllContentAtomically() {
        val file = File(dir, "timeline.jsonl")
        val tf = FileTimelineFile(file)
        tf.appendLine("old-1")
        tf.appendLine("old-2")
        tf.overwrite(listOf("new-1", "new-2", "new-3"))
        assertEquals(listOf("new-1", "new-2", "new-3"), tf.readLines())
        assertTrue(file.readText().endsWith("\n"))
    }

    /** overwrite に空リストを渡すと内容が空になる。 */
    @Test
    fun overwriteWithEmptyClearsContent() {
        val file = File(dir, "timeline.jsonl")
        val tf = FileTimelineFile(file)
        tf.appendLine("x")
        tf.overwrite(emptyList())
        assertEquals(emptyList(), tf.readLines())
        assertEquals("", file.readText())
    }

    /** defaultTimelineFile は既定保存先を指す TimelineFile を返し、読み書きできる。 */
    @Test
    fun defaultTimelineFileIsUsable() {
        val tf = defaultTimelineFile()
        assertTrue(tf is FileTimelineFile)
        tf.readLines()
    }
}
