package to.sava.peranta.platform

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit

/**
 * 出力されたログ行をメモリに溜めるだけのテスト用 [LogWriter]。
 * ログ衛生（本文・秘密がログへ出ないこと）の検査に使う。
 */
class RecordingLogWriter : LogWriter() {

    private val lines = mutableListOf<String>()

    /** 記録済みのログ行。severity・タグ・メッセージ・例外の文字列表現を 1 行に連ねたもの。 */
    val recorded: List<String>
        get() = lines.toList()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        lines += buildString {
            append(severity.name)
            append(" [")
            append(tag)
            append("] ")
            append(message)
            throwable?.let {
                append('\n')
                append(it.stackTraceToString())
            }
        }
    }
}

/** 全 severity（verbose 以上）を [writer] へ流す [Logger]。 */
fun recordingLogger(writer: RecordingLogWriter, tag: String): Logger =
    Logger(loggerConfigInit(writer, minSeverity = Severity.Verbose), tag)
