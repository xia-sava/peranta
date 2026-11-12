package to.sava.peranta.platform

import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Desktop のファイルログ（[initLogging]）。書き込みに失敗してもログを呼んだ側の処理を落とさないことと、
 * 書き込める状態では行が追記されることを固定する。
 */
class PerantaLoggingTest {

    /** グローバルの Logger を書き換えるため、他のテストへ影響を残さないよう既定へ戻す。 */
    @AfterTest
    fun restoreGlobalLogger() {
        configurePerantaLogging(verboseLogging = true, platformLogWriter())
    }

    /** 出力先がディレクトリで書き込めなくても、ログの呼び出しは例外を投げない。 */
    @Test
    fun logCallDoesNotThrowWhenLogFileIsNotWritable() {
        val unwritable = Files.createTempDirectory("peranta-log-unwritable").toFile().apply { deleteOnExit() }
        initLogging(verboseLogging = false, logFile = unwritable)

        Logger.withTag("Logging").w(IllegalStateException("boom")) { "write fails" }
    }

    /** 書き込める出力先には、重大度・タグ・メッセージを含む行が追記される。 */
    @Test
    fun logCallAppendsLineWhenLogFileIsWritable() {
        val dir = Files.createTempDirectory("peranta-log-writable").toFile().apply { deleteOnExit() }
        val logFile = dir.resolve("logs").resolve("peranta.log")
        initLogging(verboseLogging = false, logFile = logFile)

        Logger.withTag("Logging").w { "written line" }

        val content = logFile.readText()
        assertTrue(content.contains("Warn [Logging] written line"), "unexpected log content: $content")
        logFile.deleteOnExit()
        logFile.parentFile.deleteOnExit()
    }
}
