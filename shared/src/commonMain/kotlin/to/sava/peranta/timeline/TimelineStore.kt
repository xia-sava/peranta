package to.sava.peranta.timeline

import to.sava.peranta.model.newPayloadId

/** タイムライン履歴の永続化層（§11）。実体は差し替え可能とする。 */
interface TimelineStore {

    /** 1 アイテムを追記する。 */
    suspend fun append(item: TimelineItem)

    /**
     * エラーを追記する。抑止を持つ実装は時間枠で絞る（§10.1）。
     * 戻り値は追記したアイテム。抑止したときは null。
     * 通知でも知らせるかは呼び出し側が決める。抑止しない実装では毎回追記し、[append] の失敗はそのまま伝える。
     */
    suspend fun appendError(kind: ErrorKind, message: String, at: Long): ErrorItem? =
        ErrorItem(id = newPayloadId(), timestampEpochMillis = at, message = message, kind = kind).also { append(it) }

    /** 保存済みの全アイテムを時系列（追記順）で返す。 */
    suspend fun loadAll(): List<TimelineItem>

    /**
     * 失効分（expiresAtEpochMillis < [now]）と上限超過分を落とす。
     * 上限を超える場合は新しい [maxItems] 件を残す。
     * [maxAgeMillis] が非 null のとき、[now] からその経過時間より古いアイテム
     * （timestampEpochMillis が cutoff 未満）も落とす（§11 の保持日数）。null なら日数では剪定しない。
     */
    suspend fun prune(maxItems: Int = 1000, now: Long, maxAgeMillis: Long? = null)
}
