package to.sava.peranta.platform

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/** ログに残す topic 末尾の文字数（§8）。 */
private const val TOPIC_LOG_SUFFIX_LENGTH = 4

/** 省略した先頭部分を表す記号。 */
private const val TRUNCATION_MARK = "…"

/**
 * ログの出力先と、ログに残す最小重大度を設定する（§11）。両プラットフォームとも起動時にこれだけを呼ぶ。
 * 出力先も一緒に受け取るのは、呼び忘れが「ログが 1 行も出ない」という形ですぐ判るようにするため。
 */
fun configurePerantaLogging(verboseLogging: Boolean, vararg writers: LogWriter) {
    Logger.setLogWriters(*writers)
    applyLogVerbosity(verboseLogging)
}

/**
 * ログに残す最小重大度を [verboseLogging] から決めて適用する（§11）。
 * 既定（false）は Info 以上だけを残し、true にすると Verbose まで残す。
 * 稼働中に設定が変わったときの反映にも使う。
 */
fun applyLogVerbosity(verboseLogging: Boolean) {
    Logger.setMinSeverity(if (verboseLogging) Severity.Verbose else Severity.Info)
}

/**
 * topic をログへ出すための短縮表現（§8）。末尾 [TOPIC_LOG_SUFFIX_LENGTH] 文字だけを残す。
 * topic 名は共有鍵から独立に採番した推測困難な値で、知られれば購読・投稿の入口になるため、
 * 完全な値はどの重大度のログにも出さない。残す長さは、記録どうしがどの topic のものかを
 * 見分けられる一方で、残りを推測する手がかりにはならない長さとして選んである。
 */
fun topicForLog(topic: String): String =
    topic.takeIf { it.length > TOPIC_LOG_SUFFIX_LENGTH }
        ?.takeLast(TOPIC_LOG_SUFFIX_LENGTH)
        ?.let { "$TRUNCATION_MARK$it" }
        ?: TRUNCATION_MARK
