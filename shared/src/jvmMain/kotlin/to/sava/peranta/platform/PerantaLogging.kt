package to.sava.peranta.platform

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import java.io.File
import java.time.Instant

/** ローテーションの閾値（バイト）。 */
private const val MAX_LOG_BYTES = 1_000_000L

/** 保持する旧ログの世代数。 */
private const val ROTATION_KEEP = 3

/**
 * kermit のグローバル出力先を「コンソール + アプリのデータ領域の logs\peranta.log」に設定し、
 * [verboseLogging] からログに残す最小重大度を決める（§11）。アプリ起動時に一度だけ呼ぶ。
 */
fun initLogging(verboseLogging: Boolean, logFile: File = File(JvmPaths.logDir, "peranta.log")) {
    configurePerantaLogging(verboseLogging, platformLogWriter(), FileLogWriter(logFile))
}

/**
 * ログを 1 ファイルへ追記し、サイズ超過で世代ローテーションする [LogWriter]。
 * 記録するのは [configurePerantaLogging] が通した重大度の行だけで、通知の本文・タイトルと
 * 完全な topic 名はどの重大度でも含まれない。ファイルはアプリ専用領域に置く。
 */
private class FileLogWriter(private val file: File) : LogWriter() {

    private val lock = Any()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = buildString {
            append(Instant.now())
            append(' ')
            append(severity.name)
            append(" [")
            append(tag)
            append("] ")
            append(message)
            throwable?.let {
                append('\n')
                append(it.stackTraceToString())
            }
            append('\n')
        }
        synchronized(lock) {
            rotateIfNeeded()
            file.parentFile?.mkdirs()
            file.appendText(line)
        }
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < MAX_LOG_BYTES) return
        val oldest = File("${file.path}.$ROTATION_KEEP")
        if (oldest.exists()) oldest.delete()
        for (i in ROTATION_KEEP - 1 downTo 1) {
            val src = File("${file.path}.$i")
            if (src.exists()) src.renameTo(File("${file.path}.${i + 1}"))
        }
        file.renameTo(File("${file.path}.1"))
    }
}
