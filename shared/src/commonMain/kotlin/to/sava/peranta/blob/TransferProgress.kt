package to.sava.peranta.blob

/** 添付転送（アップロード/ダウンロード）の進行状態。 */
enum class TransferState {
    /** 開始前・キュー待ち。 */
    PENDING,

    /** 転送中。 */
    RUNNING,

    /** 完了。 */
    COMPLETED,

    /** 失敗（ユーザーの手動再試行に委ねる）。 */
    FAILED,

    /** ユーザーによるキャンセル。 */
    CANCELLED,
}

/**
 * 1 件の添付転送の進捗（§4.3）。UI（進捗バー・NN%）と通知が購読する。
 * [totalBytes] が 0 以下（サイズ未確定）のときは [percent] を 0 とする。
 */
data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long,
    val state: TransferState,
) {
    /** 0..100 に丸めた進捗率。 */
    val percent: Int
        get() = if (totalBytes <= 0L) {
            0
        } else {
            ((transferredBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        }

    companion object {
        /** 開始直後（0 バイト、[TransferState.RUNNING]）の進捗を返す。 */
        fun running(totalBytes: Long): TransferProgress =
            TransferProgress(transferredBytes = 0L, totalBytes = totalBytes, state = TransferState.RUNNING)
    }
}
