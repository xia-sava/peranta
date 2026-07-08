package to.sava.peranta.send

/** 同一通知の連続更新を抑止する既定の時間枠（§3.1）。 */
const val NOTIFICATION_UPDATE_WINDOW_MILLIS: Long = 10 * 1000L

/** notificationKey と本文を 1 キーへ束ねる際の区切り（本文中に現れない制御文字）。 */
private const val SIGNATURE_SEPARATOR: Char = '\u0000'

/**
 * 同一 notificationKey の連続更新を短期記憶し、直近の時間枠内に同一 key かつ同一本文で
 * 再掲された通知を抑止する。単一スレッド（NLS の onNotificationPosted）での利用を前提とする。
 */
class NotificationUpdateTracker(
    private val windowMillis: Long = NOTIFICATION_UPDATE_WINDOW_MILLIS,
) {
    private val lastSeen = mutableMapOf<String, Long>()

    /**
     * [notificationKey] と [body] の組を [at] 時点で記録し、直近 [windowMillis] 以内に
     * 同一の組を見ていれば true（抑止対象）を返す。初出・時間枠超過なら false。
     */
    fun isRepeatUpdate(notificationKey: String, body: String, at: Long): Boolean {
        prune(at)
        val signature = notificationKey + SIGNATURE_SEPARATOR + body
        val previous = lastSeen[signature]
        lastSeen[signature] = at
        return previous != null && at - previous <= windowMillis
    }

    private fun prune(at: Long) {
        val iterator = lastSeen.entries.iterator()
        while (iterator.hasNext()) {
            if (at - iterator.next().value > windowMillis) iterator.remove()
        }
    }
}
