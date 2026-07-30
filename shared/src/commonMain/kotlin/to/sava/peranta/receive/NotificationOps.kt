package to.sava.peranta.receive

/**
 * 他アプリの通知を操作する実行系（§3.4）。元通知を握っているプラットフォーム機構
 * （Android の NLS 等）へ橋渡しする実装がこれを担う。
 *
 * 通知操作（dismiss / invokeAction / reply）の対象は [AuthorizedNotificationKey] でしか受け取らない。
 * 自端末が転送した通知だけを操作対象にするための境界で、素の notificationKey では呼べない。
 *
 * 実行に失敗した場合（対象通知が見つからない・actionIndex が範囲外・返信入力欄が無い等）は
 * 理由を添えた [CommandExecutionException] を送出する。
 */
interface NotificationOps {

    /** 対象通知を取り下げる。 */
    suspend fun dismiss(notificationKey: AuthorizedNotificationKey)

    /** 対象通知の [actionIndex] 番のアクションボタンを発火する。 */
    suspend fun invokeAction(notificationKey: AuthorizedNotificationKey, actionIndex: Int)

    /** 対象通知の [actionIndex] 番のアクションへ [text] を返信として詰めて発火する。 */
    suspend fun reply(notificationKey: AuthorizedNotificationKey, actionIndex: Int, text: String)

    /**
     * [packageName] を転送対象から除外する（denylist へ追加、§7）。
     * 個別の通知ではなく自端末の転送設定を変える操作のため、[AuthorizedNotificationKey] による
     * 認可の対象外とする。
     */
    suspend fun muteApp(packageName: String)

    /**
     * [packageName] を転送対象へ戻す（denylist の除外ルールを取り除く、§7）。
     * [muteApp] と同じく認可の対象外とする。
     */
    suspend fun unmuteApp(packageName: String)
}
