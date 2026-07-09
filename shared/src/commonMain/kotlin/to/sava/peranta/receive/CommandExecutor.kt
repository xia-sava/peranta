package to.sava.peranta.receive

/**
 * 受信した command ペイロード（§4.1）をプラットフォーム固有に実行する窓口（§3.4）。
 * NLS 操作・denylist 書き込みは commonMain から直接呼べないため、実装は androidMain で差し込み、
 * [ReceivePipeline] に注入する。commonMain 側は宛先・失効・必須フィールドの検証まで済ませてから呼ぶため、
 * 各メソッドの引数は非 null で妥当な値が渡る。
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
}

/** コマンド実行が失敗したことを、タイムライン表示用の理由付きで表す例外。 */
class CommandExecutionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
