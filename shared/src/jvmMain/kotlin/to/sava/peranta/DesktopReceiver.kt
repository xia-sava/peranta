package to.sava.peranta

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.isDevMode
import to.sava.peranta.config.withDevOverrides
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.net.httpBaseUrl
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.receive.LocalDismissCommandExecutor
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.send.CommandSender
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.roster.CAPABILITY_COMMAND
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.buildPresencePayload
import to.sava.peranta.roster.publishPresence
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.defaultTimelineFile
import to.sava.peranta.toast.ToastResult
import to.sava.peranta.toast.Toaster
import to.sava.peranta.toast.createDesktopToaster
import to.sava.peranta.toast.toastContentFor
import to.sava.peranta.ui.AppFilterController
import to.sava.peranta.ui.TimelineActions
import kotlin.io.encoding.Base64

/**
 * Desktop の設定を settings から読む。
 * [devMode] が真のときだけ開発用オーバーライド（[withDevOverrides]）を適用する。
 * 偽のとき（配布物）はオーバーライドを一切適用せず、TLS を常に有効化する（§16）。
 * 端末名があれば安定 deviceId を確定し、受信 topic 未設定なら topic を採番・永続化する。
 */
fun loadDesktopConfig(
    settings: Settings = Settings(),
    devMode: Boolean = isDevMode(),
): PerantaConfig {
    val repo = ConfigRepository(settings)
    val config = if (devMode) repo.load().withDevOverrides() else repo.load().copy(useTls = true)
    val deviceName = config.deviceName ?: return config
    val deviceId = repo.ensureDeviceId()
    val topic = config.receiveTopic ?: repo.ensureReceiveTopic(deviceName)
    return config.copy(deviceId = deviceId, receiveTopic = topic)
}

/**
 * Desktop 起動時の設定読み込みと、設定画面コントローラを同一の settings 実体から作る。
 * これにより desktopApp 側は multiplatform-settings の型に依存せず設定 UI を配線できる。
 * [devMode] は設定画面の TLS 切替可否など UI 側の分岐にも渡す。
 */
class DesktopSettings(
    settings: Settings = Settings(),
    val devMode: Boolean = isDevMode(),
) {
    /** 設定ストアに紐づくリポジトリ。設定画面・アプリフィルタ画面の永続化で共有する。 */
    val repository: ConfigRepository = ConfigRepository(settings)
    val config: PerantaConfig = loadDesktopConfig(settings, devMode)
    val controller: SettingsController = SettingsController(repository)
}

/**
 * Desktop 受信の中核を組み立てる。設定が揃っている（[PerantaConfig.isReadyForReceive]）
 * 前提で生成すること。受信通知は Windows トーストにも表示する（[toaster]）。
 * [repository] はアプリフィルタ画面（§10.4-1）のローカルミラー永続化と共有する設定リポジトリ。
 */
class DesktopReceiver(
    val config: PerantaConfig,
    private val repository: ConfigRepository,
    private val toaster: Toaster = createDesktopToaster(),
    private val onToastClicked: () -> Unit = {},
    private val log: Logger = Logger.withTag("DesktopReceiver"),
) {
    private val httpClient = createNtfyHttpClient()
    private val store = JsonlTimelineStore(defaultTimelineFile())
    private val cipher = MessageCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
    private val ntfy = KtorNtfyClient(config, httpClient, Logger.withTag("NtfyClient"))
    private val toastScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commandSender = CommandSender(config, cipher, ntfy, SendPipeline(cipher, ntfy, store))

    // 受信専用端末では dismiss のみ意味を持つ executor を注入する。他 command 種別は no-op（§3.4）。
    private val dismissExecutor = LocalDismissCommandExecutor(
        items = ::currentItems,
        dismissLocal = { payloadId -> toaster.close(payloadId) },
    )
    private val pipeline = ReceivePipeline(
        ntfy = ntfy,
        cipher = cipher,
        store = store,
        deviceId = config.deviceId!!,
        commandExecutor = dismissExecutor,
        persistSensitiveHistory = config.persistSensitiveHistory,
        onItemAppended = ::handleAppended,
    )

    /** UI が購読するタイムライン。 */
    val items: StateFlow<List<TimelineItem>> = pipeline.items

    private fun currentItems(): List<TimelineItem> = pipeline.items.value

    /** 起動時剪定と presence 告知を行い、受信 topic の購読を開始する。キャンセルまで戻らない。 */
    suspend fun run() {
        store.prune(now = nowEpochMillis())
        announcePresence()
        log.i { "starting desktop receiver for device=${config.deviceName}" }
        pipeline.start(config.receiveTopic!!)
    }

    /**
     * control topic へ自端末の presence を告知する（§3.5）。
     * control topic 未設定なら何もしない。失敗しても受信開始は妨げない。
     */
    private suspend fun announcePresence() {
        val controlTopic = config.controlTopic ?: return
        val deviceId = config.deviceId ?: return
        val receiveTopic = config.receiveTopic ?: return
        try {
            val presence = buildPresencePayload(
                deviceId = deviceId,
                deviceName = config.deviceName ?: deviceId,
                endpoint = "${config.httpBaseUrl()}/$receiveTopic",
                capabilities = listOf(CAPABILITY_DISPLAY, CAPABILITY_COMMAND),
                sender = config.sendEnabled,
                now = nowEpochMillis(),
            )
            publishPresence(cipher, ntfy, controlTopic, presence)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "presence announce failed" }
        }
    }

    /** タイムラインに載った新規アイテムをトースト表示へ回す（受信処理はブロックしない）。 */
    private fun handleAppended(item: TimelineItem) {
        when (item) {
            is ReceivedNotification -> toastScope.launch { showNotificationToast(item) }
            is ErrorItem -> toastScope.launch { showErrorToast(item) }
            else -> Unit
        }
    }

    private suspend fun showNotificationToast(item: ReceivedNotification) {
        val content = toastContentFor(item) ?: return
        when (toaster.show(content)) {
            ToastResult.ButtonDismiss -> requestDismiss(item.payload)
            ToastResult.Clicked -> {
                log.i { "toast clicked id=${item.id}" }
                onToastClicked()
            }

            else -> Unit
        }
    }

    private suspend fun showErrorToast(item: ErrorItem) {
        toaster.show(toastContentFor(item))
    }

    /**
     * トーストの「消す」押下時に既読同期（§3.4）の dismiss を全端末へブロードキャストする。
     * SMS など notificationKey を持たない通知は取り下げ対象にならないためログのみとする。
     */
    private fun requestDismiss(payload: Payload) {
        val notificationKey = (payload as? NotificationPayload)?.notificationKey ?: run {
            log.i { "dismiss ignored (no notification key) payload=${payload.id}" }
            return
        }
        toastScope.launch { commandSender.dismiss(notificationKey) }
    }

    /**
     * タイムライン UI 用の操作束を作る（§10.1）。アクション発火・非表示は送信元へ一点指定、
     * 「消す」は既読同期のため全端末へブロードキャストしつつ、表示済みトーストも取り下げる。
     * mute はアプリフィルタ画面（§10.4-1）と同じ経路（[appFilterController]）でローカルミラーへも反映する。
     */
    fun timelineActions(): TimelineActions = TimelineActions(
        invokeAction = { payload, index ->
            toastScope.launch {
                commandSender.invokeAction(payload.from, payload.notificationKey, index)
            }
        },
        dismiss = { item -> dismissFromTimeline(item) },
        muteApp = { payload ->
            appFilterController().setMirroredMute(payload.packageName, payload.from, mute = true)
        },
    )

    /**
     * 受信専用端末のアプリフィルタ画面（§10.4-1）向けコントローラを組む。
     * チェック操作は [repository] のローカルミラー（filterRules）へ反映すると同時に、送信元スマホへ
     * mute/unmute コマンドを送る。宛先はタイムライン履歴に記録された送信元 deviceId を使う。
     */
    fun appFilterController(): AppFilterController = AppFilterController(
        repository = repository,
        commandScope = toastScope,
        sendMuteCommand = { packageName, senderDeviceId, mute ->
            try {
                if (mute) {
                    commandSender.muteApp(senderDeviceId, packageName)
                } else {
                    commandSender.unmuteApp(senderDeviceId, packageName)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to send mute command for $packageName" }
            }
        },
    )

    private fun dismissFromTimeline(item: ReceivedNotification) {
        toastScope.launch {
            toaster.close(item.payload.id)
            val notificationKey = (item.payload as? NotificationPayload)?.notificationKey ?: run {
                log.i { "dismiss ignored (no notification key) payload=${item.payload.id}" }
                return@launch
            }
            commandSender.dismiss(notificationKey)
        }
    }

    /** 保持するリソース（トーストコルーチンと HTTP クライアント）を閉じる。 */
    fun close() {
        toastScope.cancel()
        httpClient.close()
    }
}
