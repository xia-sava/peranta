package to.sava.peranta.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun formatTimeOfDay(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
