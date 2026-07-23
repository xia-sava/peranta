package to.sava.peranta.toast

/** SnoreToast に渡す 1 トーストの表示内容。 */
data class ReceivedNotificationToast(
    val id: String,
    val title: String,
    val body: String,
    /** 本文から抽出した先頭 URL。あれば「開く」ボタンを追加する（§3.3）。 */
    val openUrl: String? = null,
)

/**
 * トースト表示の結果。「消す」ボタンのみの構成では SnoreToast の exit code だけで判別できるが、
 * 「開く」ボタンを追加した構成では exit code が両ボタンで共通（ButtonPressed）になるため、
 * stdout に出るボタン名も合わせて判別する（§3.3）。
 */
enum class ToastResult {
    /** 本体クリック（exit 0）。 */
    Clicked,

    /** ユーザーが閉じた（exit 2）。 */
    Dismissed,

    /** 表示時間切れ（exit 3）。 */
    TimedOut,

    /** 「開く」ボタン押下（exit 4 + stdout「開く」）。 */
    ButtonOpen,

    /** 「消す」ボタン押下（exit 4。1 ボタン構成では exit code のみで判別）。 */
    ButtonDismiss,

    /** プロセス起動失敗・未知の exit code・stdout からボタンを特定できない場合。 */
    Failed,
}

/**
 * Windows ネイティブトーストの表示・取り下げ抽象。
 * 実装は SnoreToast プロセスを起動する（Windows 専用）。非対応環境では [NoOpToaster]。
 */
interface Toaster {

    /** [item] をトースト表示し、ユーザー操作の結果を返す。表示中はブロックする。 */
    suspend fun show(item: ReceivedNotificationToast): ToastResult

    /** 表示済みトースト [id] を取り下げる（既読同期での取り下げに使う。§3.4）。 */
    suspend fun close(id: String)
}

/** Windows でない環境や exe が無い環境で使う no-op 実装。 */
object NoOpToaster : Toaster {
    override suspend fun show(item: ReceivedNotificationToast): ToastResult = ToastResult.Failed
    override suspend fun close(id: String) = Unit
}
