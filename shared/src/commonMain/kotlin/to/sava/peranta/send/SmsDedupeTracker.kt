package to.sava.peranta.send

/** 重複抑止で SMS を記憶する既定の保持時間（§3.1）。 */
const val SMS_DEDUPE_WINDOW_MILLIS: Long = 60 * 1000L

/**
 * 直接受信した SMS を短期記憶し、SMS アプリの通知として NLS 経由で二重に拾われた分を
 * 判定して落とす（§3.1）。単一スレッド（NLS / SMS 受信は主にメインスレッド）での利用を前提とする。
 * 通知側では送信番号が欠けることが多いため、正規化した本文の包含だけで突き合わせる。
 */
class SmsDedupeTracker(
    private val windowMillis: Long = SMS_DEDUPE_WINDOW_MILLIS,
) {
    private val seenBodies = mutableMapOf<String, Long>()

    /** 直接受信した SMS 本文を [at] 時点で記憶する。 */
    fun recordSms(body: String, at: Long) {
        prune(at)
        val normalized = normalize(body)
        if (normalized.isNotBlank()) seenBodies[normalized] = at
    }

    /** 通知（[title] / [text]）が記憶済みの SMS 本文を含むなら重複とみなす。 */
    fun isDuplicateNotification(title: String?, text: String?, at: Long): Boolean {
        prune(at)
        val haystack = normalize("${title.orEmpty()} ${text.orEmpty()}")
        if (haystack.isBlank()) return false
        return seenBodies.keys.any { body -> haystack.contains(body) }
    }

    private fun prune(at: Long) {
        val iterator = seenBodies.entries.iterator()
        while (iterator.hasNext()) {
            if (at - iterator.next().value > windowMillis) iterator.remove()
        }
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()
}
