package to.sava.peranta.timeline

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * 置き換えに失敗した（行き先が中身のあるディレクトリで置き換えられない）とき、例外を投げ、
     * 一時ファイルが残らず、行き先の中身も変わらない。
     */
    @Test
    fun overwriteFailureThrowsAndLeavesNoTempFile() {
        val blocker = File(dir, "timeline.jsonl")
        val child = File(blocker, "child.txt").also {
            it.parentFile.mkdirs()
            it.writeText("keep")
        }
        val tf = FileTimelineFile(blocker)

        assertFailsWith<IOException> { tf.overwrite(listOf("new")) }

        assertEquals(emptyList(), dir.listFiles { f -> f.isFile && isTimelineTempFile(f.name) }.orEmpty().toList())
        assertEquals("keep", child.readText())
    }

    /** 書き直す前に、書き直し中に止められて残った一時ファイル（乱数名・固定名とも）が消える。 */
    @Test
    fun overwriteDeletesLeftoverTempFilesBeforeWriting() {
        val leftovers = listOf(File(dir, "timeline123456.tmp"), File(dir, "timeline.jsonl.tmp"))
            .onEach { it.writeText("stale") }
        val tf = FileTimelineFile(File(dir, "timeline.jsonl"))

        tf.overwrite(listOf("a"))

        leftovers.forEach { assertFalse(it.exists(), "残っている: ${it.name}") }
        assertEquals(listOf("a"), tf.readLines())
        assertEquals(listOf("timeline.jsonl"), dir.list().orEmpty().toList())
    }

    /** 書き直す前の一時ファイルの削除は、タイムラインの一時ファイルの名前に当たらないファイルとディレクトリには触れない。 */
    @Test
    fun overwriteLeavesUnrelatedFilesAlone() {
        val untouched = listOf(
            File(dir, "other.tmp"),
            File(dir, "timeline.jsonl.bak"),
            File(dir, "memo.txt"),
            File(dir, "sub/timeline9.tmp"),
        ).onEach {
            it.parentFile.mkdirs()
            it.writeText("x")
        }
        val tempNamedDirectory = File(dir, "timeline-dir.tmp").also { it.mkdirs() }
        val tf = FileTimelineFile(File(dir, "timeline.jsonl"))

        tf.overwrite(listOf("a"))

        untouched.forEach { assertTrue(it.exists(), "消えている: ${it.relativeTo(dir)}") }
        assertTrue(tempNamedDirectory.isDirectory)
    }

    /** 一時ファイルの名前の判定は、乱数名と固定名の `timeline.jsonl.tmp` に当たり、履歴本体や他の `.tmp` には当たらない。 */
    @Test
    fun timelineTempFileNamesAreRecognized() {
        assertTrue(isTimelineTempFile("timeline.jsonl.tmp"))
        assertTrue(isTimelineTempFile("timeline8123.tmp"))
        assertFalse(isTimelineTempFile("timeline.jsonl"))
        assertFalse(isTimelineTempFile("other.tmp"))
    }

    /**
     * 同じファイルを指す 2 つのインスタンスから並行に overwrite しても、どちらも失敗せず、
     * 一時ファイルが残らず、内容はどちらかの書き込みがそのまま残る（互いの一時ファイルを消し合わない）。
     */
    @Test
    fun concurrentOverwritesFromTwoInstancesSucceed() {
        val file = File(dir, "timeline.jsonl")
        val contents = listOf(listOf("a-1", "a-2"), listOf("b-1", "b-2", "b-3"))
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = contents.map { lines ->
            val tf = FileTimelineFile(file)
            thread {
                start.await()
                repeat(OVERWRITES_PER_THREAD) {
                    try {
                        tf.overwrite(lines)
                    } catch (error: Throwable) {
                        failures.add(error)
                    }
                }
            }
        }

        start.countDown()
        threads.forEach { it.join() }

        assertEquals(emptyList(), failures.toList())
        assertEquals(listOf("timeline.jsonl"), dir.list().orEmpty().toList())
        assertTrue(FileTimelineFile(file).readLines() in contents)
    }

    private companion object {
        const val OVERWRITES_PER_THREAD = 30
    }
}
