package to.sava.peranta.receive

import co.touchlab.kermit.Logger
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem

/**
 * 受信専用端末（Desktop・NLS 未接続の Android）向けの [CommandExecutor]（§3.4）。
 * NLS を持たないためアクション発火・返信・denylist 反映はできず、既読同期の dismiss のみ意味を持つ。
 *
 * dismiss はタイムライン [items] を走査して同じ notificationKey の受信通知を探し、
 * 見つかればその payload.id で自端末が表示したローカル通知を取り下げる（[dismissLocal]）。
 * 見つからない場合は、既に消えている／未受信とみなして非致命的に扱う（他端末による削除と競合し得るため）。
 */
class LocalDismissCommandExecutor(
    private val items: () -> List<TimelineItem>,
    private val dismissLocal: suspend (payloadId: String) -> Unit,
    private val log: Logger = Logger.withTag("LocalDismiss"),
) : CommandExecutor {

    override suspend fun dismiss(notificationKey: String) {
        val target = items().asSequence()
            .filterIsInstance<ReceivedNotification>()
            .firstOrNull { (it.payload as? NotificationPayload)?.notificationKey == notificationKey }
        if (target == null) {
            log.i { "dismiss target not present (already gone?) key=$notificationKey" }
            return
        }
        dismissLocal(target.payload.id)
        log.i { "local notification dismissed id=${target.payload.id}" }
    }

    override suspend fun invokeAction(notificationKey: String, actionIndex: Int) {
        log.d { "invokeAction ignored on display-only device key=$notificationKey" }
    }

    override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) {
        log.d { "reply ignored on display-only device key=$notificationKey" }
    }

    override suspend fun muteApp(packageName: String) {
        log.d { "muteApp ignored on display-only device package=$packageName" }
    }

    override suspend fun unmuteApp(packageName: String) {
        log.d { "unmuteApp ignored on display-only device package=$packageName" }
    }
}
