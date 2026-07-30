package to.sava.peranta.receive

import to.sava.peranta.model.AppRuleSettings

import co.touchlab.kermit.Logger
import to.sava.peranta.timeline.TimelineItem

/** 自端末が転送していない通知への操作を拒んだときにタイムラインへ出す文言。 */
private const val UNAUTHORIZED_MESSAGE =
    "この端末が転送していない通知は操作できません（履歴から消えた古い通知の可能性もあります）"

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
 * [notificationOps] へ委ねると決めた操作は、その直前に [AuthorizedNotificationKey] の認可を通す。
 * 操作できるのは [items] の送信済みタイムラインに転送実績がある通知だけで、実績が無ければ
 * 他アプリの通知には触れない。
 * 自端末の表示だけを変える [localDismiss] 側は認可の対象外で、転送実績を持たない端末でも
 * 既読同期によるミラー通知の取り下げはそのまま働く。
 *
 * 認可できなかったときの扱いはコマンドの宛先で分かれる。dismiss は全端末へ同報されるため
 * （[to.sava.peranta.send.CommandSender.dismiss]）、他端末が転送した通知の dismiss を受け取ることは
 * 平常の動作であり、ログに留めてタイムラインには残さない。invokeAction / reply は端末を指定して
 * 送られるため、認可できないことは異常として [CommandUnauthorizedException] で記録する。
 *
 * 設定更新（muteApp / unmuteApp）は NLS を要さないため、接続状態に依らず常に [notificationOps] へ委ねる。
 * これらは重複排除の対象になり得るため、未接続時に取りこぼすと再配送されず恒久的に失われる（§7）。
 * 個別の通知ではなく自端末の転送設定を変える操作のため、認可の対象外とする。
 *
 * 委譲先はメソッド呼び出しごとに [isNlsConnected] / [isForwardingIntended] を問うて決めるため、
 * パイプライン構築後に接続状態や設定が変わっても、その時点の実態に沿って振る舞う。
 */
class RoutingCommandExecutor(
    private val isNlsConnected: () -> Boolean,
    private val isForwardingIntended: () -> Boolean,
    private val items: () -> List<TimelineItem>,
    private val notificationOps: NotificationOps,
    private val localDismiss: CommandExecutor,
    private val log: Logger = Logger.withTag("Routing"),
) : CommandExecutor {

    override suspend fun dismiss(notificationKey: String) {
        localDismiss.dismiss(notificationKey)
        if (!isNlsConnected()) return
        val authorized = AuthorizedNotificationKey.authorize(items(), notificationKey) ?: run {
            log.d { "dismiss not authorized for a notification this device did not forward" }
            return
        }
        notificationOps.dismiss(authorized)
    }

    override suspend fun invokeAction(notificationKey: String, actionIndex: Int) {
        if (shouldRunNotificationOp()) {
            notificationOps.invokeAction(authorize(notificationKey), actionIndex)
        } else {
            localDismiss.invokeAction(notificationKey, actionIndex)
        }
    }

    override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) {
        if (shouldRunNotificationOp()) {
            notificationOps.reply(authorize(notificationKey), actionIndex, text)
        } else {
            localDismiss.reply(notificationKey, actionIndex, text)
        }
    }

    override suspend fun muteApp(packageName: String) = notificationOps.muteApp(packageName)

    override suspend fun unmuteApp(packageName: String) = notificationOps.unmuteApp(packageName)

    override suspend fun setAppRule(packageName: String, settings: AppRuleSettings) =
        notificationOps.setAppRule(packageName, settings)

    /**
     * 元通知への操作（invokeAction / reply）を [notificationOps] へ委ねるべきか。
     * NLS 接続中は実行でき、未接続でも転送の意思がある端末では委ねてエラーを記録させる。
     * 未接続の受信専用端末では委ねず、[localDismiss] 側で静かにスキップする。
     */
    private fun shouldRunNotificationOp(): Boolean = isNlsConnected() || isForwardingIntended()

    /** 自端末の転送実績を照合する。認可できなければ [CommandUnauthorizedException] を送出する。 */
    private fun authorize(notificationKey: String): AuthorizedNotificationKey =
        AuthorizedNotificationKey.authorize(items(), notificationKey)
            ?: throw CommandUnauthorizedException(UNAUTHORIZED_MESSAGE)
}
