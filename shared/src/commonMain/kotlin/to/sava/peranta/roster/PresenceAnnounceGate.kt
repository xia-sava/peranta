package to.sava.peranta.roster

import to.sava.peranta.model.PresencePayload

/** 同一内容の presence を再 announce するまでの最小間隔（ミリ秒）。 */
const val PRESENCE_REANNOUNCE_MIN_INTERVAL_MILLIS: Long = 60 * 60 * 1000L

/**
 * 同一内容の presence の連続 announce を抑える（§3.2）。内容（fingerprint）が変わったときは常に通し、
 * 同一内容は前回成功から [minIntervalMillis] 経過するまで抑止する。
 * スレッド安全ではないため、呼び出し側で直列化（Mutex 等）して使う。
 */
class PresenceAnnounceGate(
    private val minIntervalMillis: Long = PRESENCE_REANNOUNCE_MIN_INTERVAL_MILLIS,
) {
    private var lastFingerprint: String? = null
    private var lastAnnouncedAtEpochMillis: Long = 0L

    fun shouldAnnounce(fingerprint: String, now: Long): Boolean =
        fingerprint != lastFingerprint || now - lastAnnouncedAtEpochMillis >= minIntervalMillis

    /** announce の publish 成功後にだけ呼ぶ。失敗時は記録せず、次の契機で再試行させる。 */
    fun recordAnnounced(fingerprint: String, now: Long) {
        lastFingerprint = fingerprint
        lastAnnouncedAtEpochMillis = now
    }
}

/** presence の内容部分（id・送信時刻を除く）から同一性判定用の fingerprint を作る。 */
fun presenceFingerprint(presence: PresencePayload): String =
    listOf(
        presence.from,
        presence.deviceName,
        presence.endpoint,
        presence.capabilities.joinToString(","),
        presence.sender.toString(),
    ).joinToString("|")
