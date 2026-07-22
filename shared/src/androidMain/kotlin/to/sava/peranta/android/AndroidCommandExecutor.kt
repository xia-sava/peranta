package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import to.sava.peranta.filter.mutePackage
import to.sava.peranta.filter.unmutePackage
import to.sava.peranta.receive.CommandExecutionException
import to.sava.peranta.receive.CommandExecutor

/** NLS が未接続でコマンドを実行できないときに添える理由。 */
private const val NLS_NOT_CONNECTED_MESSAGE =
    "通知へのアクセスが有効でないためコマンドを実行できません"

/**
 * 通知捕捉（NLS）を持つ端末でのコマンド実行（§3.4）。
 * 通知操作（dismiss / invokeAction / reply）は生存中の [PerantaNotificationListenerService] へ委ね、
 * muteApp / unmuteApp は [androidConfigRepository] のフィルタルール部分更新で denylist へ反映する（§7）。
 */
class AndroidCommandExecutor(
    context: Context,
    private val log: Logger = Logger.withTag("CommandExec"),
) : CommandExecutor {

    private val appContext = context.applicationContext

    override suspend fun dismiss(notificationKey: String) {
        listenerService().dismissByKey(notificationKey)
    }

    override suspend fun invokeAction(notificationKey: String, actionIndex: Int) {
        listenerService().invokeActionByKey(notificationKey, actionIndex)
    }

    override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) {
        listenerService().replyByKey(notificationKey, actionIndex, text)
    }

    override suspend fun muteApp(packageName: String) {
        androidConfigRepository(appContext).updateFilterRules { rules -> mutePackage(rules, packageName) }
        log.i { "muted package: $packageName" }
    }

    override suspend fun unmuteApp(packageName: String) {
        androidConfigRepository(appContext).updateFilterRules { rules -> unmutePackage(rules, packageName) }
        log.i { "unmuted package: $packageName" }
    }

    private fun listenerService(): PerantaNotificationListenerService =
        PerantaNotificationListenerService.activeInstance
            ?: throw CommandExecutionException(NLS_NOT_CONNECTED_MESSAGE)
}
