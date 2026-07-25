package to.sava.peranta.toast

/** 1 トーストの表示内容。 */
data class ReceivedNotificationToast(
    val id: String,
    val title: String,
    val body: String,
    /** 本文から抽出した先頭 URL。あれば「開く」ボタンを追加する（§3.3）。 */
    val openUrl: String? = null,
)

/** トースト表示の結果。 */
enum class ToastResult {
    /** 本体クリック。 */
    Clicked,

    /** ユーザーが閉じた（× ボタン・スワイプ）。 */
    Dismissed,

    /** 「開く」ボタン押下。 */
    ButtonOpen,

    /** 「消す」ボタン押下。 */
    ButtonDismiss,

    /** 既読同期などで表示側から取り下げた（§3.4）。 */
    Closed,

    /** 表示できなかった。 */
    Failed,
}

/**
 * トースト表示・取り下げの抽象。実装は Compose のウィンドウで自前に描く（[ComposeToaster]）。
 * トーストを出さない環境では [NoOpToaster]。
 */
interface Toaster {

    /** [item] をトースト表示し、ユーザー操作の結果を返す。表示中はサスペンドする。 */
    suspend fun show(item: ReceivedNotificationToast): ToastResult

    /** 表示済みトースト [id] を取り下げる（既読同期での取り下げに使う。§3.4）。 */
    suspend fun close(id: String)
}

/** トーストを出さない環境で使う no-op 実装。 */
object NoOpToaster : Toaster {
    override suspend fun show(item: ReceivedNotificationToast): ToastResult = ToastResult.Failed
    override suspend fun close(id: String) = Unit
}
