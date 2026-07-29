package to.sava.peranta.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * アプリデータの置き場の解決（[currentAppDir]）と、旧い置き場からの移設（[migrateAppData]）を表明する（§11）。
 * 実際のデータ領域は触らず、一時ディレクトリだけを渡して確かめる。
 * 一貫して見ているのは「**移設の成否に関わらず履歴が読めること**」。
 */
class AppDirMigrationTest {

    private val root: File = Files.createTempDirectory("peranta-migration-test").toFile()
    private val target: File = File(root, "to.sava.peranta")
    private val legacy: File = File(root, "Peranta")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun legacyFile(path: String, content: String = "x"): File =
        File(legacy, path).also {
            it.parentFile.mkdirs()
            it.writeText(content)
        }

    /** 移設していない状態では旧い置き場を指し、履歴を読めないままにしない。 */
    @Test
    fun pointsAtTheOldLocationUntilTheDataIsMoved() {
        legacyFile("timeline.jsonl")

        assertEquals(legacy, currentAppDir(target, legacy))
    }

    /** 移設が済んでいれば新しい置き場を指す。 */
    @Test
    fun pointsAtTheNewLocationOnceTheDataIsThere() {
        target.mkdirs()
        legacyFile("timeline.jsonl")

        assertEquals(target, currentAppDir(target, legacy))
    }

    /** どちらの置き場も無ければ新しい置き場を指す（インストール直後・旧い置き場の概念が無い環境）。 */
    @Test
    fun pointsAtTheNewLocationWhenNeitherExists() {
        assertEquals(target, currentAppDir(target, legacy))
        assertEquals(target, currentAppDir(target, null))
    }

    /** 旧い置き場の履歴・添付・ログは新しい置き場へ中身ごと移り、旧い置き場は残らない。 */
    @Test
    fun movesEverythingFromTheOldLocation() {
        legacyFile("timeline.jsonl", "{\"id\":\"a\"}")
        legacyFile("attachments/deadbeef/photo.png")
        legacyFile("logs/peranta.log")

        migrateAppData(target, legacy)

        assertEquals("{\"id\":\"a\"}", File(target, "timeline.jsonl").readText())
        assertTrue(File(target, "attachments/deadbeef/photo.png").exists())
        assertTrue(File(target, "logs/peranta.log").exists())
        assertFalse(legacy.exists(), "旧い置き場が残っている")
        assertEquals(target, currentAppDir(target, legacy))
    }

    /** 移すものが無ければ何もしない（新しい置き場を勝手に作りもしない）。 */
    @Test
    fun doesNothingWhenThereIsNothingToMove() {
        migrateAppData(target, legacy)
        migrateAppData(target, null)

        assertFalse(target.exists())
    }

    /** 新しい置き場が既にあれば移設済みとみなし、旧い置き場の中身で上書きしない。 */
    @Test
    fun keepsAlreadyMigratedDataWhenBothExist() {
        target.mkdirs()
        File(target, "timeline.jsonl").writeText("new")
        legacyFile("timeline.jsonl", "old")

        migrateAppData(target, legacy)

        assertEquals("new", File(target, "timeline.jsonl").readText())
        assertEquals("old", File(legacy, "timeline.jsonl").readText())
    }

    /**
     * 移設できなかったときは旧い置き場がそのまま残り、履歴を読めないままにしない。
     * 移せない状況として、新しい置き場の名前を通常のファイルが占めている場合を使う。
     */
    @Test
    fun leavesTheOldLocationReadableWhenTheMoveFails() {
        legacyFile("timeline.jsonl", "{\"id\":\"a\"}")
        val blocker = File(root, "to.sava.peranta").also { it.writeText("not a directory") }

        migrateAppData(target, legacy)

        assertEquals("{\"id\":\"a\"}", File(legacy, "timeline.jsonl").readText())
        assertEquals(legacy, currentAppDir(target, legacy))
        assertTrue(blocker.isFile, "新しい置き場の名前を占めていたものが入れ替わっている")
    }

    /** 移設に失敗しても、途中まで移した中身が新しい置き場に残ることはない。 */
    @Test
    fun leavesNoHalfMovedDataBehindWhenTheMoveFails() {
        legacyFile("timeline.jsonl")
        File(root, "to.sava.peranta").writeText("not a directory")

        migrateAppData(target, legacy)

        assertFalse(File(root, "to.sava.peranta.migrating").exists(), "移設の途中経過が残っている")
    }
}
