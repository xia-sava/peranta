package to.sava.peranta.android

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「すべての情報の消去」（§11）で Android のキャッシュ領域から何が消え、何が残るかを表明する。
 * 実際の `cacheDir` は触らず、一時ディレクトリを [eraseCachedAppData] へ渡して確かめる。
 */
class AndroidAppDataTest {

    private val cacheDir: File = Files.createTempDirectory("peranta-cache-test").toFile()

    @AfterTest
    fun cleanup() {
        cacheDir.deleteRecursively()
    }

    private fun file(path: String): File =
        File(cacheDir, path).also {
            it.parentFile.mkdirs()
            it.writeText("x")
        }

    /** 復号済み添付と送信待ちのスプールコピーは消える。 */
    @Test
    fun erasesDecryptedAttachmentsAndOutgoingSpool() {
        val erased = listOf(file("attachments/deadbeef/photo.png"), file("outgoing/upload1.bin"))

        eraseCachedAppData(cacheDir)

        erased.forEach { assertFalse(it.exists(), "残っている: ${it.relativeTo(cacheDir)}") }
    }

    /** 更新の配布物と、Peranta が作ったのでないものには触れない（消しすぎない）。 */
    @Test
    fun leavesUpdateDownloadsAndOtherFiles() {
        val untouched = listOf(file("updates/peranta.apk"), file("someone-else/data.bin"))

        eraseCachedAppData(cacheDir)

        untouched.forEach { assertTrue(it.exists(), "消えている: ${it.relativeTo(cacheDir)}") }
    }
}
