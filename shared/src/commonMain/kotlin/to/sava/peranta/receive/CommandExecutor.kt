package to.sava.peranta.receive

import to.sava.peranta.model.AppRuleSettings

/**
 * 受信した command ペイロード（§4.1）を実行する [ReceivePipeline] 側の窓口（§3.4）。
 * [ReceivePipeline] は宛先・失効・必須フィールドの検証まで済ませてから呼ぶため、
 * 各メソッドの引数は非 null で妥当な値が渡る。
 *
 * 対象通知は素の notificationKey で受け取る。自端末の表示を操作する実装
 * （[LocalDismissCommandExecutor]）が直接これを担い、他アプリの通知を操作する実装は
 * [AuthorizedNotificationKey] を要求する [NotificationOps] の側に置く。
 * 両者の振り分けは [RoutingCommandExecutor] が担う。
 *
 * 実行に失敗した場合（対象通知が見つからない・actionIndex が範囲外・返信入力欄が無い等）は
 * 理由を添えた [CommandExecutionException] を送出する。[ReceivePipeline] がこれを捕捉し、
 * タイムラインへエラーとして記録する。
 */
interface CommandExecutor {

    /** 対象通知を取り下げる。 */
    suspend fun dismiss(notificationKey: String)

    /** 対象通知の [actionIndex] 番のアクションボタンを発火する。 */
    suspend fun invokeAction(notificationKey: String, actionIndex: Int)

    /** 対象通知の [actionIndex] 番のアクションへ [text] を返信として詰めて発火する。 */
    suspend fun reply(notificationKey: String, actionIndex: Int, text: String)

    /** [packageName] を転送対象から除外する（denylist へ追加、§7）。 */
    suspend fun muteApp(packageName: String)

    /** [packageName] を転送対象へ戻す（denylist の除外ルールを取り除く、§7）。 */
    suspend fun unmuteApp(packageName: String)

    /**
     * [packageName] のアプリごとの扱いを [settings] の内容へ更新する（§7）。
     * 転送可否・優先度上書き・伏せ字・払いのけの扱いをまとめて置き換える。
     */
    suspend fun setAppRule(packageName: String, settings: AppRuleSettings)
}

/** コマンド実行が失敗したことを、タイムライン表示用の理由付きで表す例外。 */
class CommandExecutionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
