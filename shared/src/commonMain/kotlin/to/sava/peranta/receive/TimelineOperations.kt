package to.sava.peranta.receive

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.send.CommandSender
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.TimelineActions

/**
 * 受信アイテムに対するこの端末発の操作（§3.4 / §10.1 / §10.4-1）を組む。
 * コマンドの送信、「元通知は消えた」（sourceDismissed）のマーク、タイムラインからの非表示、表示の取り下げを束ねる。
 *
 * 操作束（[timelineActions]・[appFilterController]）の操作は [scope] のコルーチンで行い、
 * 失敗はログに残して呼び出し元へ漏らさない。
 */
class TimelineOperations(
    private val scope: CoroutineScope,
    /** 現在のタイムライン。元通知を転送した端末を引くのに使う（[sourceDeviceOf]）。 */
    private val items: () -> List<TimelineItem>,
    /** コマンドを送る [CommandSender] を組む。送信の設定が揃っていなければ null。 */
    private val commandSender: () -> CommandSender?,
    /** 自端末のタイムラインで、[notificationKey] の受信通知を「元通知は消えた」状態にマークする（§3.4）。 */
    private val markSourceDismissed: suspend (notificationKey: String) -> Unit,
    /** 自端末のタイムラインから [itemId] の受信通知を消す（§10.1）。 */
    private val hideFromTimeline: suspend (itemId: String) -> Unit,
    /** この端末が出した表示（ミラー通知・トースト）を取り下げる。 */
    private val withdrawDisplay: suspend (payloadId: String) -> Unit,
    private val log: Logger = Logger.withTag("TimelineOps"),
) {

    /**
     * タイムライン UI 用の操作束を作る（§10.1）。アクション発火・返信・非表示は送信元へ一点指定し、
     * 「送信元の通知を消す」は既読同期のため全端末へブロードキャストしつつ、この端末の表示も取り下げる。
     * mute はアプリフィルタ画面（§10.4-1）と同じ経路（[filter]）でローカルミラーへも反映する。
     */
    fun timelineActions(filter: AppFilterController): TimelineActions = TimelineActions(
        invokeAction = { payload, index ->
            launchCommand { it.invokeAction(payload.from, payload.notificationKey, index) }
        },
        dismiss = { item -> dismissItem(item) },
        muteApp = { payload -> filter.setMirroredMute(payload.packageName, payload.from, mute = true) },
        reply = { payload, index, text ->
            launchCommand { it.reply(payload.from, payload.notificationKey, index, text) }
        },
        hideFromTimeline = { item -> hideItem(item) },
        dismissAll = { targets -> dismissItems(targets) },
    )

    /**
     * 受信専用端末のアプリフィルタ画面（§10.4-1）向けコントローラを組む。
     * チェック操作は [repository] のローカルミラー（filterRules）へ反映すると同時に、送信元の端末へ
     * mute/unmute コマンドを送る。宛先はタイムライン履歴に記録された送信元 deviceId を使う。
     */
    fun appFilterController(repository: ConfigRepository): AppFilterController = AppFilterController(
        repository = repository,
        commandScope = scope,
        sendMuteCommand = { packageName, senderDeviceId, mute ->
            try {
                commandSender()?.let { sender ->
                    if (mute) {
                        sender.muteApp(senderDeviceId, packageName)
                    } else {
                        sender.unmuteApp(senderDeviceId, packageName)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには接続先 URL（＝完全な topic）が載る（§11）。
                log.w { "failed to send mute command for $packageName (${error::class.simpleName})" }
            }
        },
        sendAppRuleCommand = { packageName, senderDeviceId, settings ->
            try {
                commandSender()?.setAppRule(senderDeviceId, packageName, settings)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには接続先 URL（＝完全な topic）が載る（§11）。
                log.w { "failed to send app rule command for $packageName (${error::class.simpleName})" }
            }
        },
    )

    /**
     * 既読同期の dismiss を全端末へ送り、自端末のタイムラインにもマークを反映する（§3.4）。
     * ブロードキャストは自端末を除外するため、マークは自分で付ける。送信の設定が揃っていなければマークだけを反映する。
     * ミラー通知・トーストの「送信元の通知を消す」と払いのけが使う。
     * [sourceDeviceId] は応答を求める相手で、null なら応答を求めない。省くとタイムラインから引く。
     * 失敗は呼び出し元へ伝える。
     */
    suspend fun dismissSource(notificationKey: String, sourceDeviceId: String? = sourceDeviceOf(notificationKey)) {
        commandSender()?.dismiss(notificationKey, sourceDeviceId)
        markSourceDismissed(notificationKey)
    }

    /**
     * [notificationKey] の元通知を転送した端末（§3.4 の応答を求める相手）をタイムラインから引く。
     * ミラー通知の操作は key しか運ばないため、受信アイテムの送信元から補う。見つからなければ null。
     */
    fun sourceDeviceOf(notificationKey: String): String? =
        items().asSequence()
            .filterIsInstance<ReceivedNotification>()
            .lastOrNull { it.payload.notificationKeyOrNull() == notificationKey }
            ?.payload
            ?.from

    /**
     * タイムラインの ✓ ボタンで [item] を消す（§3.4）。この端末の表示を取り下げてから、元通知があれば
     * 既読同期を送ってマークを反映する。応答は [item] の送信元に求める。
     * 元通知に紐づかない（notificationKey を持たない）アイテムは表示の取り下げだけにする。
     */
    private fun dismissItem(item: ReceivedNotification) {
        scope.launch {
            try {
                withdrawDisplay(item.payload.id)
                val notificationKey = item.payload.notificationKeyOrNull() ?: run {
                    log.i { "dismiss ignored (no notification key) payload=${item.payload.id}" }
                    return@launch
                }
                dismissSource(notificationKey, item.payload.from)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには接続先 URL（＝完全な topic）が載る（§11）。
                log.w { "failed to dismiss from timeline (${error::class.simpleName})" }
            }
        }
    }

    /**
     * 元通知が生きている通知をまとめて消す（§10.1）。まず表示をアイテムごとに取り下げ、それから
     * 既読同期を送ってマークを反映する。同じ元通知を指すアイテムが複数あってもコマンドは 1 通知につき 1 回にまとめる。
     * 応答（§3.4）は転送元の端末ごとに最初の 1 件だけに求める。実行端末はコマンドを順に処理するため
     * 後ろのものほど応答が遅れ、全件に求めると届いていても待ち時間を超えてしまう。
     * 途中で失敗したら、残りは行わない。
     */
    private fun dismissItems(targets: List<ReceivedNotification>) {
        scope.launch {
            try {
                targets.forEach { withdrawDisplay(it.payload.id) }
                val ackRequestedSources = mutableSetOf<String>()
                targets.mapNotNull { item -> item.payload.notificationKeyOrNull()?.let { it to item.payload.from } }
                    .distinctBy { (notificationKey, _) -> notificationKey }
                    .forEach { (notificationKey, sourceDeviceId) ->
                        dismissSource(notificationKey, sourceDeviceId.takeIf { ackRequestedSources.add(it) })
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには接続先 URL（＝完全な topic）が載る（§11）。
                log.w { "failed to dismiss all from timeline (${error::class.simpleName})" }
            }
        }
    }

    /** [item] をこの端末のタイムラインから消す（§10.1）。他端末には何も送らない。 */
    private fun hideItem(item: ReceivedNotification) {
        scope.launch {
            try {
                hideFromTimeline(item.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w { "failed to hide from timeline (${error::class.simpleName})" }
            }
        }
    }

    /** 送信元の端末へコマンドを送る。送信の設定が揃っていなければ何もしない。 */
    private fun launchCommand(block: suspend (CommandSender) -> Unit) {
        scope.launch {
            try {
                commandSender()?.let { block(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには接続先 URL（＝完全な topic）が載る（§11）。
                log.w { "failed to send command (${error::class.simpleName})" }
            }
        }
    }
}
