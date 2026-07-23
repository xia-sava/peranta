package to.sava.peranta.ui

/** エポックミリ秒をローカルの時:分表記へ整形する。 */
expect fun formatTimeOfDay(epochMillis: Long): String

private const val MILLIS_PER_MINUTE: Long = 60 * 1000L
private const val MILLIS_PER_HOUR: Long = 60 * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY: Long = 24 * MILLIS_PER_HOUR

/**
 * [epochMillis] から [nowEpochMillis] までの経過時間を相対表記にする。1 分未満（未来時刻を含む）は
 * 「たった今」、60 分未満は「N分前」、24 時間未満は「N時間前」、それ以上は「N日前」。
 */
fun formatRelativeTime(nowEpochMillis: Long, epochMillis: Long): String {
    val elapsedMillis = nowEpochMillis - epochMillis
    return when {
        elapsedMillis < MILLIS_PER_MINUTE -> "たった今"
        elapsedMillis < MILLIS_PER_HOUR -> "${elapsedMillis / MILLIS_PER_MINUTE}分前"
        elapsedMillis < MILLIS_PER_DAY -> "${elapsedMillis / MILLIS_PER_HOUR}時間前"
        else -> "${elapsedMillis / MILLIS_PER_DAY}日前"
    }
}
