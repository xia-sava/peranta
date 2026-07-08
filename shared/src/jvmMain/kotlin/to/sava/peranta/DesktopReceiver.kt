package to.sava.peranta

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.withDevOverrides
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.receive.ReceivePipeline
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.timeline.defaultTimelineFile
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
 * 前提で生成すること。
 */
class DesktopReceiver(
    val config: PerantaConfig,
    private val log: Logger = Logger.withTag("DesktopReceiver"),
) {
    private val httpClient = createNtfyHttpClient()
    private val store = JsonlTimelineStore(defaultTimelineFile())
    private val cipher = MessageCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
    private val ntfy = KtorNtfyClient(config, httpClient, Logger.withTag("NtfyClient"))
    private val pipeline = ReceivePipeline(ntfy, cipher, store, config.deviceName!!)

    /** UI が購読するタイムライン。 */
    val items: StateFlow<List<TimelineItem>> = pipeline.items

    /** 起動時剪定を行い、受信 topic の購読を開始する。キャンセルまで戻らない。 */
    suspend fun run() {
        store.prune(now = nowEpochMillis())
        log.i { "starting desktop receiver for device=${config.deviceName}" }
        pipeline.start(config.receiveTopic!!)
    }

    /** 保持する HTTP クライアントを閉じる。 */
    fun close() {
        httpClient.close()
    }
}
