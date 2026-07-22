package to.sava.peranta.receive

/**
 * 逆方向コマンド（§3.4）の実行先を、通知操作と設定更新で使い分ける [CommandExecutor]。
 *
 * 元通知への操作（dismiss / invokeAction / reply）は実行時点の通知捕捉（NLS）接続状態で選ぶ。
 * - dismiss は表示済みミラー通知を常に [localDismiss] で取り下げ、NLS 接続中は [notificationOps] で
 *   元通知も取り下げる。
 * - invokeAction / reply は NLS 接続中のみ実行できる。未接続でも転送の意思がある端末
 *   （[isForwardingIntended]）では [notificationOps] へ委ね、「通知へのアクセスが無効」のエラーを
 *   記録させる。未接続の受信専用端末では [localDismiss] 側で静かにスキップする。
 *
 * 設定更新（muteApp / unmuteApp）は NLS を要さないため、接続状態に依らず常に [notificationOps] へ委ねる。
 * これらは重複排除の対象になり得るため、未接続時に取りこぼすと再配送されず恒久的に失われる（§7）。
 *
 * 委譲先はメソッド呼び出しごとに [isNlsConnected] / [isForwardingIntended] を問うて決めるため、
 * パイプライン構築後に接続状態や設定が変わっても、その時点の実態に沿って振る舞う。
 */
class RoutingCommandExecutor(
    private val isNlsConnected: () -> Boolean,
    private val isForwardingIntended: () -> Boolean,
    private val notificationOps: CommandExecutor,
    private val localDismiss: CommandExecutor,
) : CommandExecutor {

    override suspend fun dismiss(notificationKey: String) {
        localDismiss.dismiss(notificationKey)
        if (isNlsConnected()) notificationOps.dismiss(notificationKey)
    }

    override suspend fun invokeAction(notificationKey: String, actionIndex: Int) {
        if (shouldRunNotificationOp()) {
            notificationOps.invokeAction(notificationKey, actionIndex)
        } else {
            localDismiss.invokeAction(notificationKey, actionIndex)
        }
    }

    override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) {
        if (shouldRunNotificationOp()) {
            notificationOps.reply(notificationKey, actionIndex, text)
        } else {
            localDismiss.reply(notificationKey, actionIndex, text)
        }
    }

    override suspend fun muteApp(packageName: String) = notificationOps.muteApp(packageName)

    override suspend fun unmuteApp(packageName: String) = notificationOps.unmuteApp(packageName)

    /**
     * 元通知への操作（invokeAction / reply）を [notificationOps] へ委ねるべきか。
     * NLS 接続中は実行でき、未接続でも転送の意思がある端末では委ねてエラーを記録させる。
     * 未接続の受信専用端末では委ねず、[localDismiss] 側で静かにスキップする。
     */
    private fun shouldRunNotificationOp(): Boolean = isNlsConnected() || isForwardingIntended()
}
