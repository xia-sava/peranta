package to.sava.peranta.blob

/** キャッシュの最終アクセスからの保持上限（24 時間、§4.3）。 */
const val ATTACHMENT_CACHE_MAX_AGE_MILLIS: Long = 24L * 60 * 60 * 1000

/** キャッシュ合計サイズの上限（1 GiB、§4.3）。 */
const val ATTACHMENT_CACHE_MAX_TOTAL_BYTES: Long = 1L shl 30

/**
 * 剪定判定の対象となるキャッシュ 1 件（blob 単位のディレクトリに対応）。
 * [id] は blobId、[sizeBytes] はディレクトリ内の合計バイト、[lastAccessEpochMillis] は最終アクセス時刻。
 */
data class CachedAttachment(
    val id: String,
    val sizeBytes: Long,
    val lastAccessEpochMillis: Long,
)

/**
 * 剪定で削除すべきキャッシュ id を決める純粋関数（§4.3）。
 * 判定は 2 段階:
 * 1. 最終アクセスから [maxAgeMillis] を超過したものは削除する。
 * 2. 残りの合計サイズが [maxTotalBytes] を超える分を、古い順（最終アクセスが古いもの）から削除する。
 * 実際のファイル削除は呼び出し側が行い、失敗はログに残して次回へ回す。
 */
fun selectAttachmentsToPrune(
    entries: List<CachedAttachment>,
    now: Long,
    maxAgeMillis: Long = ATTACHMENT_CACHE_MAX_AGE_MILLIS,
    maxTotalBytes: Long = ATTACHMENT_CACHE_MAX_TOTAL_BYTES,
): List<String> {
    val expired = entries.filter { now - it.lastAccessEpochMillis > maxAgeMillis }
    val expiredIds = expired.map { it.id }.toSet()
    val survivors = entries.filterNot { it.id in expiredIds }

    val prunedForSize = mutableListOf<String>()
    var total = survivors.sumOf { it.sizeBytes }
    if (total > maxTotalBytes) {
        survivors
            .sortedBy { it.lastAccessEpochMillis }
            .forEach { entry ->
                if (total <= maxTotalBytes) return@forEach
                prunedForSize.add(entry.id)
                total -= entry.sizeBytes
            }
    }
    return expired.map { it.id } + prunedForSize
}
