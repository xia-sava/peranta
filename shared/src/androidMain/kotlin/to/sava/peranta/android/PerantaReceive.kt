package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.PipelineKey
import to.sava.peranta.blob.AutoFetchRole
import to.sava.peranta.blob.attachmentKindForMimeType
import to.sava.peranta.config.toPipelineKey
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.notificationKeyOrNull
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.receive.LocalDismissCommandExecutor
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.receive.RoutingCommandExecutor
import to.sava.peranta.roster.RosterFetchResult
import to.sava.peranta.roster.RosterStore
import to.sava.peranta.send.CommandSender
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ErrorSuppressor
import to.sava.peranta.timeline.ReceivedMessage
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.TimelineActions
import to.sava.peranta.ui.displayAttachments
import to.sava.peranta.ui.senderIcon
import to.sava.peranta.ui.shell.RosterUi

/** イベントに詰める固定 topic ラベル。エンドポイント URL は秘匿するため運搬に含めない（§16）。 */
private const val EVENT_TOPIC_LABEL = "unifiedpush"

/**
 * Android 受信側のプロセス内シングルトン。UnifiedPush のコールバックから駆動される。
 * 単一の [ReceivePipeline] を保持し、Envelope 文字列の復号 → 宛先検証 → 失効判定 → タイムライン反映を委ね、
 * 反映された通知は OS 通知として表示する（§3.2）。送信側とタイムライン（JSONL）を共有し、
 * 受信・送信・エラーを同一履歴に載せる（§10.1）。
 * StateFlow [items] を UI が購読することで、受信のたびにタイムラインが即時更新される。
 * パイプラインを共有することで seenIds も共有され、並行して届く同一 id メッセージの重複追記を防ぐ。
 */
object PerantaReceive {

    private val log = Logger.withTag("PerantaReceive")
    private val mutex = Mutex()
    private var pipeline: ReceivePipeline? = null
    private var pipelineConfigKey: PipelineKey? = null
    private val errorSuppressor = ErrorSuppressor()
    private val commandScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val httpClient by lazy { createNtfyHttpClient() }

    /** 受信・送信・エラーを載せた現在のタイムライン。UI はこれを購読する。 */
    val items: StateFlow<List<TimelineItem>> get() = PerantaSend.timelineFeed.items

    /**
     * 受信設定が揃った端末の起動時に履歴を読み込み、以後の受信で即時更新できるよう待機状態にする。
     * 何度呼んでもパイプラインは 1 つに保たれる（プロセス内シングルトン）。設定不足なら何もしない。
     */
    suspend fun prime(context: Context) {
        val appContext = context.applicationContext
        val config = androidConfigRepository(appContext).load()
        if (!config.isReadyForUnifiedPushReceive) {
            log.w { "receive not configured; skipping prime" }
            return
        }
        mutex.withLock { pipelineLocked(appContext, config) }
        announcePresence(appContext)
    }

    /**
     * 保持中の受信パイプラインを破棄する。次回の [prime] / [handleEnvelope] で最新設定から作り直される。
     * 設定変更後、Activity 再生成（recreate）だけではプロセス内シングルトンの状態が更新されないため、
     * その直前に呼んで確実に最新設定を反映させる。
     */
    suspend fun reset() {
        mutex.withLock {
            pipeline = null
            pipelineConfigKey = null
        }
    }

    /**
     * 設定がパイプライン構成（[PipelineKey]）に影響する形で変わっていたら、保持中の受信
     * パイプラインを破棄して最新設定で組み直す。変わっていなければ何もしない。
     * 鍵の作成・作り直しの即時反映（§10.2）に使う。Activity の再生成を伴わない。
     */
    suspend fun rebuildIfPipelineConfigChanged(context: Context) {
        val appContext = context.applicationContext
        val repo = androidConfigRepository(appContext)
        val nextKey = repo.load().copy(deviceId = repo.ensureDeviceId()).toPipelineKey()
        val unchanged = mutex.withLock {
            if (pipeline != null && pipelineConfigKey == nextKey) return@withLock true
            pipeline = null
            pipelineConfigKey = null
            false
        }
        if (unchanged) return
        prime(appContext)
    }

    /**
     * UnifiedPush で受け取った 1 メッセージ（暗号文 Envelope 文字列）を処理する。
     * 復号 → 宛先検証 → 失効判定 → タイムライン反映を受信中核に委ね、
     * 反映されたアイテムを OS 通知として表示する（§3.2）。設定不足なら復号できないため何もしない。
     */
    suspend fun handleEnvelope(context: Context, rawMessage: String) {
        val appContext = context.applicationContext
        val config = androidConfigRepository(appContext).load()
        if (!config.isReadyForUnifiedPushReceive) {
            log.w { "receive not configured; dropping incoming message" }
            return
        }
        mutex.withLock {
            pipelineLocked(appContext, config).handleEvent(eventFor(rawMessage))
        }
    }

    /**
     * 登録などタイムライン処理の外で生じたエラーをタイムラインへ反映する（§10.5）。
     * 画面回転などで同じエラーが連続して積まれないよう、抑止は受信中核と同じ [ErrorSuppressor] に委ねる。
     */
    suspend fun reportError(context: Context, message: String) {
        mutex.withLock {
            val at = nowEpochMillis()
            if (!errorSuppressor.allows(ErrorKind.OTHER, message, at)) {
                log.i { "suppressing duplicate error: $message" }
                return
            }
            val item = ErrorItem(
                id = newPayloadId(),
                timestampEpochMillis = at,
                message = message,
                kind = ErrorKind.OTHER,
            )
            PerantaSend.timelineFeed.record(item)
        }
    }

    private suspend fun pipelineLocked(
        appContext: Context,
        config: PerantaConfig,
    ): ReceivePipeline {
        pipeline?.let { return it }
        val presenter = AndroidNotificationPresenter(appContext)
        // 通知操作は実行時点の NLS 接続で選び、設定更新（mute/unmute）は接続に依らず常に反映する（§3.4/§7）。
        // NLS の許可・切断や設定変更が後から起きても、コマンドごとに実態を問い直して振る舞う。
        // 他アプリの通知への操作は、送信済みタイムラインに転送実績がある通知だけに限る（§3.4）。
        val commandExecutor = RoutingCommandExecutor(
            isNlsConnected = { PerantaNotificationListenerService.activeInstance != null },
            isForwardingIntended = { androidConfigRepository(appContext).load().sendEnabled },
            items = { PerantaSend.timelineFeed.items.value },
            notificationOps = AndroidNotificationOps(appContext),
            localDismiss = LocalDismissCommandExecutor(
                items = { PerantaSend.timelineFeed.items.value },
                dismissLocal = { payloadId -> presenter.cancel(payloadId) },
            ),
        )
        val created = ReceivePipeline(
            ntfy = null,
            cipher = perantaCipher(config),
            feed = PerantaSend.timelineFeed,
            deviceId = androidConfigRepository(appContext).ensureDeviceId(),
            commandExecutor = commandExecutor,
            persistSensitiveHistory = config.persistSensitiveHistory,
            onItemAppended = { item -> onAppended(presenter, item) },
            onItemUpdated = { item -> onUpdated(appContext, presenter, item) },
        )
        created.loadHistory()
        pipeline = created
        pipelineConfigKey = config.copy(deviceId = androidConfigRepository(appContext).ensureDeviceId()).toPipelineKey()
        return created
    }

    private fun onAppended(presenter: AndroidNotificationPresenter, item: TimelineItem) {
        present(presenter, item)
    }

    /**
     * 改版で差し替わった受信通知を表示へ反映する（§4.3.1）。後から届いた画像と送信者アイコンを取得し、
     * 表示済みの通知を音を鳴らさずに出し直す。本文画像は自動表示が OFF なら取得しない。
     */
    private fun onUpdated(context: Context, presenter: AndroidNotificationPresenter, item: TimelineItem) {
        if (item !is ReceivedNotification) return
        val imageRef = item.payload.displayAttachments()
            .firstOrNull { attachmentKindForMimeType(it.mimeType) == AttachmentKind.IMAGE }
        val senderIconRef = item.payload.senderIcon()
        if (imageRef == null && senderIconRef == null) return
        commandScope.launch {
            val config = androidConfigRepository(context).load()
            val image = imageRef
                ?.let { AndroidAttachmentReceive.notificationImage(context, config, it, AutoFetchRole.DISPLAY_IMAGE) }
            val senderIcon = senderIconRef
                ?.let { AndroidAttachmentReceive.notificationImage(context, config, it, AutoFetchRole.SENDER_ICON) }
            if (image == null && senderIcon == null) return@launch
            presenter.update(item, image, senderIcon)
        }
    }

    private fun present(presenter: AndroidNotificationPresenter, item: TimelineItem) {
        when (item) {
            is ReceivedNotification -> presenter.show(item)
            is ReceivedMessage -> presenter.show(item)
            is ErrorItem -> presenter.showError(item)
            else -> Unit
        }
    }

    private fun eventFor(rawMessage: String): NtfyEvent = NtfyEvent(
        id = "",
        time = nowEpochMillis(),
        topic = EVENT_TOPIC_LABEL,
        message = rawMessage,
    )

    /**
     * タイムライン UI 用の操作束を作る（§10.1）。アクション発火・返信・非表示は送信元へ一点指定、
     * 「送信元の通知を消す」は既読同期のため全端末へブロードキャストしつつ、表示済みローカル通知も
     * 取り下げる。
     * mute はアプリフィルタ画面（§10.4-1）と同じ経路（[appFilterController]）でローカルミラーへも反映する。
     */
    fun timelineActions(context: Context): TimelineActions {
        val appContext = context.applicationContext
        val filterController = appFilterController(appContext)
        return TimelineActions(
            invokeAction = { payload, index ->
                launchCommand(appContext) { it.invokeAction(payload.from, payload.notificationKey, index) }
            },
            dismiss = { item -> dismissFromTimeline(appContext, item) },
            muteApp = { payload ->
                filterController.setMirroredMute(payload.packageName, payload.from, mute = true)
            },
            reply = { payload, index, text ->
                launchCommand(appContext) { it.reply(payload.from, payload.notificationKey, index, text) }
            },
            hideFromTimeline = { item -> commandScope.launch { hideFromTimeline(item.id) } },
        )
    }

    /**
     * 受信専用端末のアプリフィルタ画面（§10.4-1）向けコントローラを組む。
     * チェック操作は自端末のローカルミラー（filterRules）へ反映すると同時に、送信元スマホへ
     * mute/unmute コマンドを送る。宛先はタイムライン履歴に記録された送信元 deviceId を使う。
     */
    fun appFilterController(context: Context): AppFilterController {
        val appContext = context.applicationContext
        return AppFilterController(
            repository = androidConfigRepository(appContext),
            commandScope = commandScope,
            sendMuteCommand = { packageName, senderDeviceId, mute ->
                try {
                    commandSender(appContext)?.let { sender ->
                        if (mute) {
                            sender.muteApp(senderDeviceId, packageName)
                        } else {
                            sender.unmuteApp(senderDeviceId, packageName)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    log.w(error) { "failed to send mute command for $packageName" }
                }
            },
        )
    }

    /** 参加端末一覧ドロップダウンの取得口を組む（§3.5）。control topic か共有鍵が無ければ null。 */
    fun rosterUi(context: Context): RosterUi? {
        val appContext = context.applicationContext
        val repo = androidConfigRepository(appContext)
        val config = repo.load()
        if (config.controlTopic == null || !config.hasSharedKey) return null
        return RosterUi(
            selfDeviceId = repo.ensureDeviceId(),
            fetch = {
                val fresh = androidConfigRepository(appContext).load()
                val controlTopic = fresh.controlTopic
                if (controlTopic == null || !fresh.hasSharedKey) {
                    RosterFetchResult.FetchFailed
                } else {
                    RosterStore(KtorNtfyClient(fresh, httpClient), perantaCipher(fresh), controlTopic).fetch()
                }
            },
        )
    }

    /**
     * 既読同期（§3.4）の dismiss を全端末へ送り、自端末のタイムラインにもマークを反映する。
     * ミラー通知の「送信元の通知を消す」（[NotificationDismissReceiver]）とタイムラインの操作の
     * 共通経路。ブロードキャストは自端末を除外するため、マークは自分で付ける。
     */
    suspend fun dismissSourceNotification(context: Context, notificationKey: String) {
        val appContext = context.applicationContext
        commandSender(appContext)?.dismiss(notificationKey)
        markSourceDismissed(notificationKey)
    }

    private fun dismissFromTimeline(appContext: Context, item: ReceivedNotification) {
        commandScope.launch {
            try {
                AndroidNotificationPresenter(appContext).cancel(item.payload.id)
                val notificationKey = item.payload.notificationKeyOrNull() ?: run {
                    log.i { "dismiss ignored (no notification key) payload=${item.payload.id}" }
                    return@launch
                }
                dismissSourceNotification(appContext, notificationKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to dismiss from timeline" }
            }
        }
    }

    /**
     * 既読同期のブロードキャストが自端末を除外するため、自端末での「送信元の通知を消す」操作は自分の
     * タイムラインアイテムに sourceDismissed（§3.4）が反映されない穴がある。保持中のパイプラインへ
     * 直接マークを依頼して埋める。パイプライン未生成（未 prime）なら何もしない。
     */
    private suspend fun markSourceDismissed(notificationKey: String) {
        val current = mutex.withLock { pipeline } ?: return
        current.markSourceDismissed(notificationKey)
    }

    /**
     * 保持中のパイプラインへタイムラインからの非表示（§10.1）を依頼する。
     * パイプライン未生成（未 prime）なら何もしない。
     */
    private suspend fun hideFromTimeline(itemId: String) {
        val current = mutex.withLock { pipeline } ?: return
        current.hideFromTimeline(itemId)
    }

    private fun launchCommand(appContext: Context, block: suspend (CommandSender) -> Unit) {
        commandScope.launch {
            try {
                commandSender(appContext)?.let { block(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to send command" }
            }
        }
    }

    /**
     * 受信端末から送信元スマホへコマンドを送るための [CommandSender] を組む。
     * 送信に必要な設定（トークン・配送先解決）が揃っていなければ null。
     */
    private fun commandSender(appContext: Context): CommandSender? {
        val repo = androidConfigRepository(appContext)
        val config = repo.load().copy(deviceId = repo.ensureDeviceId())
        if (!config.isReadyForSend) {
            log.w { "not ready to send; cannot send command" }
            return null
        }
        val cipher = perantaCipher(config)
        val ntfy = KtorNtfyClient(config, httpClient)
        return CommandSender(config, cipher, ntfy, SendPipeline(cipher, ntfy, PerantaSend.timelineFeed))
    }

    /** UnifiedPush メッセージ処理をエラーで落とさないためのラッパ。例外はログに残す。 */
    suspend fun handleEnvelopeCatching(context: Context, rawMessage: String) {
        try {
            handleEnvelope(context, rawMessage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "failed to handle incoming unifiedpush message" }
        }
    }
}
