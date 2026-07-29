package to.sava.peranta.blob

import to.sava.peranta.model.AttachmentRef

/**
 * 送信者アイコンとして載せてよい符号化後の上限バイト（§4.3.1）。
 * 送信側の符号化と受信側の自動取得が同じ値を見る。
 */
const val MAX_SENDER_ICON_BYTES: Long = 64L * 1024

/**
 * 通知本体の画像として載せてよい符号化後の上限バイト（§4.3.1）。
 * 送信側の符号化と受信側の自動取得が同じ値を見る。
 */
const val MAX_NOTIFICATION_IMAGE_BYTES: Long = 2L * 1024 * 1024

/**
 * 自動取得する添付の役割（§4.3.1）。役割ごとに上限バイトと設定トグルの掛かり方が違うため、
 * 判断する側は「どの表示面のために取りに行くのか」を渡す。
 */
enum class AutoFetchRole {
    /** バブルのヘッダ・OS 通知の largeIcon に出す送信者アイコン。 */
    SENDER_ICON,

    /** 添付カード・OS 通知の本体・トーストに出す本文画像。 */
    DISPLAY_IMAGE,
}

/** [role] の添付を自動取得してよい宣言サイズの上限。 */
fun autoFetchLimitBytes(role: AutoFetchRole): Long = when (role) {
    AutoFetchRole.SENDER_ICON -> MAX_SENDER_ICON_BYTES
    AutoFetchRole.DISPLAY_IMAGE -> MAX_NOTIFICATION_IMAGE_BYTES
}

/** [ref] の宣言サイズが [role] の自動取得上限を超えているか。 */
fun exceedsAutoFetchLimit(ref: AttachmentRef, role: AutoFetchRole): Boolean =
    ref.sizeBytes > autoFetchLimitBytes(role)

/** サーバ側の添付保持期限を過ぎているか（過ぎているとダウンロードできない）。 */
fun isBlobExpired(ref: AttachmentRef, now: Long): Boolean {
    val expiresAt = ref.blobExpiresAtEpochMillis ?: return false
    return expiresAt < now
}

/**
 * ユーザーの操作なしに [ref] を取りに行ってよいかを決める（§4.3・§4.3.1）。
 *
 * 自動取得は同意なしにネットワーク I/O とディスク書き込みを起こすため、その条件をこの 1 関数へ集める。
 * 表示面（バブル・添付カード・トースト・OS 通知）が増えても、通る条件は常にここで数え上げられる。
 * 判断材料は宣言サイズ [AttachmentRef.sizeBytes] であり、実際に届くバイト列の長さではない。
 * 宣言と実体の食い違いは blob 形式の検証（[validateBlobEnc]）と AEAD が捕まえる。
 *
 * 全文添付（kind=TEXT）の自動取得だけは本文表示の一部として別の上限を持つ
 * （[exceedsFullTextAutoFetchLimit]）。
 *
 * [alreadyFetched] が真（取得済み）または [transferStarted] が真（進行中・失敗・キャンセル済み）なら
 * 取りに行かない。失敗後の自動リトライを避けるため、終了状態も「開始済み」として扱う。
 */
fun shouldAutoFetch(
    ref: AttachmentRef,
    role: AutoFetchRole,
    autoDisplayImages: Boolean,
    now: Long,
    alreadyFetched: Boolean = false,
    transferStarted: Boolean = false,
): Boolean {
    if (alreadyFetched || transferStarted) return false
    if (isBlobExpired(ref, now)) return false
    if (exceedsAutoFetchLimit(ref, role)) return false
    return when (role) {
        // 送信者アイコンには手動取得の導線が無いため、画像の自動表示トグルとは無関係に取得する（§4.3.1）。
        AutoFetchRole.SENDER_ICON -> true
        AutoFetchRole.DISPLAY_IMAGE ->
            autoDisplayImages && attachmentCategoryFor(ref.mimeType, ref.fileName) == AttachmentCategory.IMAGE
    }
}
