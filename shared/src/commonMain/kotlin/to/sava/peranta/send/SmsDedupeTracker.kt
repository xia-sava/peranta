package to.sava.peranta.send

import to.sava.peranta.model.SmsPayload

/** 重複抑止で SMS を記憶する既定の保持時間（§3.1）。 */
const val SMS_DEDUPE_WINDOW_MILLIS: Long = 60 * 1000L

/**
 * 直接受信した SMS を短期記憶し、SMS アプリの通知として NLS 経由で二重に拾われた分を
 * 判定して落とす（§3.1）。単一スレッド（NLS / SMS 受信は主にメインスレッド）での利用を前提とする。
 * 通知側では送信番号が欠けることが多いため、正規化した本文の包含だけで突き合わせる。
 *
 * 突き合わせが成立した通知は、その SMS に対応する元通知でもある。転送した [SmsPayload] を
 * 併せて記憶しておき、既読同期（§3.4）を効かせるための改版送信に使う。
 */
class SmsDedupeTracker(
    private val windowMillis: Long = SMS_DEDUPE_WINDOW_MILLIS,
) {
    private val seenBodies = mutableMapOf<String, SeenSms>()

    /** 直接受信した SMS 本文を [at] 時点で記憶する。 */
    fun recordSms(body: String, at: Long) {
        prune(at)
        val normalized = normalize(body)
        if (normalized.isNotBlank()) seenBodies[normalized] = SeenSms(at = at)
    }

    /**
     * 転送した [payload] を本文に紐づけて記憶する。本文が未記憶（[recordSms] 前・保持時間切れ）なら
     * 何もしない。対応づけは転送内容が確定してから行うため、[recordSms] とは別の呼び出しになる。
     */
    fun recordForwarded(body: String, payload: SmsPayload) {
        val normalized = normalize(body)
        val seen = seenBodies[normalized] ?: return
        seenBodies[normalized] = seen.copy(payload = payload)
    }

    /** 通知（[title] / [text]）が記憶済みの SMS 本文を含むなら重複とみなす。 */
    fun isDuplicateNotification(title: String?, text: String?, at: Long): Boolean =
        matchingKey(title, text, at) != null

    /**
     * 通知（[title] / [text]）に対応する転送済みの [SmsPayload] を取り出し、記憶から外す。
     * 対応づけは 1 回で足りるため、同じ SMS の通知が再投稿されても改版を送り直さない。
     * 一致しない、または転送内容が未確定なら null。
     */
    fun consumeForwardedPayload(title: String?, text: String?, at: Long): SmsPayload? {
        val key = matchingKey(title, text, at) ?: return null
        val seen = seenBodies.getValue(key)
        val payload = seen.payload ?: return null
        seenBodies[key] = seen.copy(payload = null)
        return payload
    }

    /** [title] / [text] に含まれる記憶済み SMS 本文の正規化キー。無ければ null。 */
    private fun matchingKey(title: String?, text: String?, at: Long): String? {
        prune(at)
        val haystack = normalize("${title.orEmpty()} ${text.orEmpty()}")
        if (haystack.isBlank()) return null
        return seenBodies.keys.firstOrNull { body -> haystack.contains(body) }
    }

    private fun prune(at: Long) {
        val iterator = seenBodies.entries.iterator()
        while (iterator.hasNext()) {
            if (at - iterator.next().value.at > windowMillis) iterator.remove()
        }
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()

    /** 記憶した SMS 1 件。[payload] は転送内容が確定していれば入る。 */
    private data class SeenSms(val at: Long, val payload: SmsPayload? = null)
}
