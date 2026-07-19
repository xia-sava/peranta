package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.receive.LocalDismissCommandExecutor
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.send.CommandSender
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.TimelineActions

/** イベントに詰める固定 topic ラベル。エンドポイント URL は秘匿するため運搬に含めない（§16）。 */
private const val EVENT_TOPIC_LABEL = "unifiedpush"

/** 同一内容のエラーを連続で重複追記しないための抑止時間枠。 */
private const val ERROR_DEDUPE_WINDOW_MILLIS: Long = 60 * 1000L

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
    private val recentErrors = mutableMapOf<String, Long>()
    private val commandScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val httpClient by lazy { createNtfyHttpClient() }

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())

    /** 受信・送信・エラーを載せた現在のタイムライン。UI はこれを購読する。 */
    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()

    /**
     * 受信ロールの起動時に履歴を読み込み、以後の受信で即時更新できるよう待機状態にする。
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
        mutex.withLock { pipeline = null }
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
     * 画面回転などで同じエラーが連続して積まれないよう、直近の同一メッセージは抑止する。
     */
    suspend fun reportError(context: Context, message: String) {
        val appContext = context.applicationContext
        mutex.withLock {
            if (isRecentDuplicateError(message, nowEpochMillis())) {
                log.i { "suppressing duplicate error: $message" }
                return
            }
            val item = ErrorItem(
                id = newPayloadId(),
                timestampEpochMillis = nowEpochMillis(),
                message = message,
                kind = ErrorKind.OTHER,
            )
            try {
                PerantaSend.timelineStore.append(item)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to persist error item" }
            }
            _items.value = _items.value + item
        }
    }

    private fun isRecentDuplicateError(message: String, at: Long): Boolean {
        recentErrors.entries.removeAll { at - it.value > ERROR_DEDUPE_WINDOW_MILLIS }
        val previous = recentErrors[message]
        recentErrors[message] = at
        return previous != null && at - previous <= ERROR_DEDUPE_WINDOW_MILLIS
    }

    private suspend fun pipelineLocked(
        appContext: Context,
        config: PerantaConfig,
    ): ReceivePipeline {
        pipeline?.let { return it }
        val presenter = AndroidNotificationPresenter(appContext)
        // 送信ロール端末（NLS 保有）は通知操作を実行する。受信専用端末は既読同期の dismiss のみ
        // 意味を持つ executor を注入し、対象通知を表示済みローカル通知から取り下げる（§3.4）。
        val commandExecutor = if (config.sendEnabled) {
            AndroidCommandExecutor(appContext)
        } else {
            LocalDismissCommandExecutor(
                items = { _items.value },
                dismissLocal = { payloadId -> presenter.cancel(payloadId) },
            )
        }
        val created = ReceivePipeline(
            ntfy = null,
            cipher = perantaCipher(config),
            store = PerantaSend.timelineStore,
            deviceId = androidConfigRepository(appContext).ensureDeviceId(),
            commandExecutor = commandExecutor,
            persistSensitiveHistory = config.persistSensitiveHistory,
            onItemAppended = { item -> onAppended(presenter, item) },
        )
        created.loadHistory()
        _items.value = created.items.value
        pipeline = created
        return created
    }

    private fun onAppended(presenter: AndroidNotificationPresenter, item: TimelineItem) {
        present(presenter, item)
        pipeline?.let { _items.value = it.items.value }
    }

    private fun present(presenter: AndroidNotificationPresenter, item: TimelineItem) {
        when (item) {
            is ReceivedNotification -> presenter.show(item)
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
     * タイムライン UI 用の操作束を作る（§10.1）。アクション発火・非表示は送信元へ一点指定、
     * 「消す」は既読同期のため全端末へブロードキャストしつつ、表示済みローカル通知も取り下げる。
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

    private fun dismissFromTimeline(appContext: Context, item: ReceivedNotification) {
        commandScope.launch {
            try {
                AndroidNotificationPresenter(appContext).cancel(item.payload.id)
                val notificationKey = (item.payload as? NotificationPayload)?.notificationKey ?: run {
                    log.i { "dismiss ignored (no notification key) payload=${item.payload.id}" }
                    return@launch
                }
                commandSender(appContext)?.dismiss(notificationKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to dismiss from timeline" }
            }
        }
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
        return CommandSender(config, cipher, ntfy, SendPipeline(cipher, ntfy, PerantaSend.timelineStore))
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
