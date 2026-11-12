package to.sava.peranta.platform

import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Desktop のファイルログ（[initLogging]）。書き込みに失敗してもログを呼んだ側の処理を落とさないことと、
 * 書き込める状態では行が追記されることを固定する。
 */
class PerantaLoggingTest {

    /** テストが作った一時ディレクトリ。後始末で中身ごと消す。 */
    private val tempDirs = mutableListOf<File>()

    private fun createTempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    /** グローバルの Logger を書き換えるため他のテストへ影響を残さないよう既定へ戻し、一時ディレクトリを消す。 */
    @AfterTest
    fun cleanUp() {
        configurePerantaLogging(verboseLogging = true, platformLogWriter())
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /** 出力先がディレクトリで書き込めなくても、ログの呼び出しは例外を投げない。 */
    @Test
    fun logCallDoesNotThrowWhenLogFileIsNotWritable() {
        val unwritable = createTempDir("peranta-log-unwritable")
        initLogging(verboseLogging = false, logFile = unwritable)

        Logger.withTag("Logging").w(IllegalStateException("boom")) { "write fails" }
    }

    /** 書き込める出力先には、重大度・タグ・メッセージを含む行が追記される。 */
    @Test
    fun logCallAppendsLineWhenLogFileIsWritable() {
        val dir = createTempDir("peranta-log-writable")
        val logFile = dir.resolve("logs").resolve("peranta.log")
        initLogging(verboseLogging = false, logFile = logFile)

        Logger.withTag("Logging").w { "written line" }

        val content = logFile.readText()
        assertTrue(content.contains("Warn [Logging] written line"), "unexpected log content: $content")
    }
}
