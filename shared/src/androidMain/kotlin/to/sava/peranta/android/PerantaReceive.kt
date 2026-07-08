package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.newPayloadId
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem

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
        // コマンド実行は NLS を持つ送信ロール端末（スマホ）でのみ行う（§3.4）。
        // 受信専用端末では executor を持たず、届いた command は無視する。
        val created = ReceivePipeline(
            ntfy = null,
            cipher = perantaCipher(config),
            store = PerantaSend.timelineStore,
            deviceId = androidConfigRepository(appContext).ensureDeviceId(),
            commandExecutor = if (config.sendEnabled) AndroidCommandExecutor(appContext) else null,
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
