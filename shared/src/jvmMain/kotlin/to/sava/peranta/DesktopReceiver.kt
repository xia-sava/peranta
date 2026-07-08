package to.sava.peranta

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.withDevOverrides
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.defaultTimelineFile
import to.sava.peranta.toast.ToastResult
import to.sava.peranta.toast.Toaster
import to.sava.peranta.toast.createDesktopToaster
import to.sava.peranta.toast.toastContentFor
import kotlin.io.encoding.Base64

/**
 * Desktop の設定を settings + 開発用オーバーライドから読む。
 * 端末名があり受信 topic 未設定なら topic を採番・永続化する。
 */
fun loadDesktopConfig(settings: Settings = Settings()): PerantaConfig {
    val repo = ConfigRepository(settings)
    val config = repo.load().withDevOverrides()
    val deviceName = config.deviceName ?: return config
    val topic = config.receiveTopic ?: repo.ensureReceiveTopic(deviceName)
    return config.copy(receiveTopic = topic)
}

/**
 * Desktop 受信の中核を組み立てる。設定が揃っている（[PerantaConfig.isReadyForReceive]）
 * 前提で生成すること。受信通知は Windows トーストにも表示する（[toaster]）。
 */
class DesktopReceiver(
    val config: PerantaConfig,
    private val toaster: Toaster = createDesktopToaster(),
    private val onToastClicked: () -> Unit = {},
    private val log: Logger = Logger.withTag("DesktopReceiver"),
) {
    private val httpClient = createNtfyHttpClient()
    private val store = JsonlTimelineStore(defaultTimelineFile())
    private val cipher = MessageCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
    private val ntfy = KtorNtfyClient(config, httpClient, Logger.withTag("NtfyClient"))
    private val toastScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val pipeline = ReceivePipeline(
        ntfy = ntfy,
        cipher = cipher,
        store = store,
        deviceName = config.deviceName!!,
        onItemAppended = ::handleAppended,
    )

    /** UI が購読するタイムライン。 */
    val items: StateFlow<List<TimelineItem>> = pipeline.items

    /** 起動時剪定を行い、受信 topic の購読を開始する。キャンセルまで戻らない。 */
    suspend fun run() {
        store.prune(now = nowEpochMillis())
        log.i { "starting desktop receiver for device=${config.deviceName}" }
        pipeline.start(config.receiveTopic!!)
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
     * 「消す」押下時に既読同期（§3.4）の dismiss を送出するためのフック。
     * command 送出は M8 のスコープであり、ここでは対象通知の記録までを担う。
     */
    private fun requestDismiss(payload: Payload) {
        val notificationKey = (payload as? NotificationPayload)?.notificationKey
        log.i { "dismiss requested from toast: payload=${payload.id} key=$notificationKey" }
    }

    /** 保持するリソース（トーストコルーチンと HTTP クライアント）を閉じる。 */
    fun close() {
        toastScope.cancel()
        httpClient.close()
    }
}
