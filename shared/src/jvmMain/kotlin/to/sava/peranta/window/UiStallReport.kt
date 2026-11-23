package to.sava.peranta.window

import co.touchlab.kermit.Logger
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** UI スレッドの停止の記録ファイル名の前置き。 */
private const val REPORT_NAME_PREFIX = "ui-stall-"

/** UI スレッドの停止の記録ファイル名の後置き。 */
private const val REPORT_NAME_SUFFIX = ".txt"

/** 記録ファイル名に入れる UTC 時刻の形（例 `20261121T080630Z`）。固定幅なので名前順が時刻順になる。 */
private val REPORT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/** 残す記録ファイルの数。これを超えた古いものは書き出しの後に消す。 */
internal const val UI_STALL_REPORT_KEEP = 5

private val log = Logger.withTag("UiStall")

/**
 * UI スレッドの停止時に採った全スレッドのスタックを、スレッドごとに名前・状態・デーモンかどうか・
 * スタックの各フレームの形で整形する（§3.3）。含めるのはスレッド名と、各フレームのクラス名・メソッド名・
 * ソースファイル名・行番号だけで、通知の本文などは含まれない。
 */
internal fun formatUiStallReport(stacks: Map<Thread, Array<StackTraceElement>>): String =
    stacks.entries
        .sortedBy { (thread, _) -> thread.name }
        .joinToString(separator = "\n") { (thread, frames) ->
            buildString {
                appendLine("\"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}")
                frames.forEach { appendLine("\tat $it") }
            }
        }

/**
 * [stacks] を [dir] の `ui-stall-<UTC 時刻>.txt` へ書き、書いたファイルを返す。書いた後に、新しい順で
 * [keep] 個を超える古い記録を消す（§3.3）。書けなければ [java.io.IOException] を投げる。
 */
internal fun writeUiStallReport(
    dir: File,
    at: Instant,
    stacks: Map<Thread, Array<StackTraceElement>>,
    keep: Int = UI_STALL_REPORT_KEEP,
): File =
    File(dir, "$REPORT_NAME_PREFIX${REPORT_TIME_FORMAT.format(at)}$REPORT_NAME_SUFFIX")
        .also { it.writeText(formatUiStallReport(stacks)) }
        .also { pruneUiStallReports(dir, keep) }

/** [dir] の記録ファイルのうち、名前（＝時刻）が新しい順で [keep] 個を超えるものを消す。 */
internal fun pruneUiStallReports(dir: File, keep: Int) {
    dir.listFiles { file -> file.name.startsWith(REPORT_NAME_PREFIX) && file.name.endsWith(REPORT_NAME_SUFFIX) }
        .orEmpty()
        .sortedByDescending { it.name }
        .drop(keep)
        .filterNot { it.delete() }
        .forEach { log.w { "could not remove the old UI stall report ${it.name}" } }
}
