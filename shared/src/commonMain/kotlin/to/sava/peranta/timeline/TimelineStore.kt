package to.sava.peranta.timeline

/** タイムライン履歴の永続化層（§11）。実体は差し替え可能とする。 */
interface TimelineStore {

    /** 1 アイテムを追記する。 */
    suspend fun append(item: TimelineItem)

    /** 保存済みの全アイテムを時系列（追記順）で返す。 */
    suspend fun loadAll(): List<TimelineItem>

    /**
     * 失効分（expiresAtEpochMillis < [now]）と上限超過分を落とす。
     * 上限を超える場合は新しい [maxItems] 件を残す。
     */
    suspend fun prune(maxItems: Int = 1000, now: Long)
}
