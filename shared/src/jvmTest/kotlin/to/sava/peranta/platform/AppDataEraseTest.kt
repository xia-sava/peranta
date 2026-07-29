package to.sava.peranta.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「すべての情報の消去」（§11）で Desktop のデータ領域から何が消え、何が残るかを表明する。
 * 実際のデータ領域は触らず、一時ディレクトリを [eraseAppData] へ渡して確かめる。
 */
class AppDataEraseTest {

    private val appDir: File = Files.createTempDirectory("peranta-erase-test").toFile()

    @AfterTest
    fun cleanup() {
        appDir.deleteRecursively()
    }

    private fun file(path: String): File =
        File(appDir, path).also {
            it.parentFile.mkdirs()
            it.writeText("x")
        }

    /** 通知・設定・鍵に由来するものは、履歴・その書き直し中の残骸・添付・貼り付け画像・ログとも消える。 */
    @Test
    fun erasesEverythingWrittenFromNotificationsAndKeys() {
        val erased = listOf(
            file("timeline.jsonl"),
            file("timeline12345.tmp"),
            file("attachments/deadbeef/photo.png"),
            file("clipboard/session1/clipboard-1.png"),
            file("logs/peranta.log"),
            file("logs/update-apply.log"),
        )

        eraseAppData(appDir)

        erased.forEach { assertFalse(it.exists(), "残っている: ${it.relativeTo(appDir)}") }
        listOf("attachments", "clipboard", "logs").forEach {
            assertFalse(File(appDir, it).exists(), "ディレクトリが残っている: $it")
        }
    }

    /** 同じ領域にあっても Peranta が作る名前でないものには触れない（消しすぎない）。 */
    @Test
    fun leavesFilesPerantaDidNotWrite() {
        val untouched = listOf(file("memo.txt"), file("timeline.jsonl.bak"), file("someone-else/data.bin"))

        eraseAppData(appDir)

        untouched.forEach { assertTrue(it.exists(), "消えている: ${it.relativeTo(appDir)}") }
        assertTrue(appDir.exists())
    }

    /** 何も書かれていない領域に対しても失敗しない。 */
    @Test
    fun succeedsOnEmptyAppDir() {
        eraseAppData(appDir)

        assertTrue(appDir.exists())
    }
}
