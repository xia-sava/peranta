package to.sava.peranta.toast

import androidx.compose.ui.graphics.ImageBitmap

/**
 * トーストに載せる元通知のアクション（§3.3）。ラベルは発信元をヘッダに出すぶん接頭辞を付けず、
 * 元通知に付いていた名前をそのまま出す。
 */
data class ToastAction(
    /** 元通知でのアクションの位置。発火するコマンド（§3.4）はこの位置を指す。 */
    val index: Int,
    /** ボタンに出す名前。 */
    val label: String,
    /**
     * 受信端末で本文を入力してから送るアクションか（§3.4 のインライン返信）。
     * トーストの中では入力を受けないため、押すとタイムラインの返信入力を開く（§3.3）。
     */
    val needsInput: Boolean = false,
)

/** 1 トーストの表示内容。 */
data class ReceivedNotificationToast(
    val id: String,
    val title: String,
    val body: String,
    /** 発信元の表示名（§3.3）。タイトルだけでは何の通知か分からないためヘッダに添える。 */
    val source: String? = null,
    /** 本文から抽出した先頭 URL。あれば「開く」ボタンを追加する（§3.3）。 */
    val openUrl: String? = null,
    /** 元通知のアクション。ボタンとして並べ、押すと発出元で実行する（§3.3）。 */
    val actions: List<ToastAction> = emptyList(),
    /** 通知に付いていた画像。本文より遅れて届くため、表示中に差し込まれることがある（§4.3.1）。 */
    val image: ImageBitmap? = null,
    /** 元通知の送信者アイコン。画像と同じく本文より遅れて届くことがある（§4.3.1）。 */
    val senderIcon: ImageBitmap? = null,
)

/** トースト表示の結果。 */
sealed interface ToastResult {
    /** 本体クリック。 */
    data object Clicked : ToastResult

    /** ユーザーが閉じた（× ボタン・スワイプ）。 */
    data object Dismissed : ToastResult

    /** 「開く」ボタン押下。 */
    data object ButtonOpen : ToastResult

    /** 「消す」ボタン押下。 */
    data object ButtonDismiss : ToastResult

    /** 元通知のアクションボタン押下。[index] は元通知でのアクションの位置（§3.4）。 */
    data class ButtonAction(val index: Int) : ToastResult

    /** 既読同期などで表示側から取り下げた（§3.4）。 */
    data object Closed : ToastResult

    /** 表示できなかった。 */
    data object Failed : ToastResult
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

    /**
     * 表示中のトーストの内容を [item] へ差し替える（後から届いた画像の差し込み、§4.3.1）。
     * 同じ id のトーストが表示されていなければ何もしない。
     */
    suspend fun update(item: ReceivedNotificationToast)
}

/** トーストを出さない環境で使う no-op 実装。 */
object NoOpToaster : Toaster {
    override suspend fun show(item: ReceivedNotificationToast): ToastResult = ToastResult.Failed
    override suspend fun close(id: String) = Unit
    override suspend fun update(item: ReceivedNotificationToast) = Unit
}
