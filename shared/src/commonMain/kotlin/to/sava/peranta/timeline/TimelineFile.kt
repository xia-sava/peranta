package to.sava.peranta.timeline

/**
 * JSONL タイムラインのファイル I/O を抽象化する最小インターフェース。
 * 置き場所（Android の filesDir / Windows の %APPDATA%）はプラットフォーム毎に解決する。
 */
interface TimelineFile {

    /** 全行を読む。ファイルが無ければ空リスト。 */
    fun readLines(): List<String>

    /** 1 行を追記する。 */
    fun appendLine(line: String)

    /** 全内容を [lines] で置き換える。 */
    fun overwrite(lines: List<String>)
}

/** プラットフォーム既定の保存先を指す [TimelineFile] を返す。 */
expect fun defaultTimelineFile(): TimelineFile
