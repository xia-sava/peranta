package to.sava.peranta.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

actual fun formatTimestamp(epochMillis: Long, nowEpochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    val formatter = if (at.toLocalDate() == now.toLocalDate()) TIME_FORMATTER else DATE_TIME_FORMATTER
    return at.format(formatter)
}
