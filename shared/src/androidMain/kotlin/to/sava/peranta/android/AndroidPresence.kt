package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.roster.PresenceAnnounceGate
import to.sava.peranta.roster.PresenceAnnounceScheduler
import to.sava.peranta.roster.buildPresencePayload
import to.sava.peranta.roster.presenceCapabilities
import to.sava.peranta.roster.presenceFingerprint
import to.sava.peranta.roster.publishPresence

private val presenceLog = Logger.withTag("PerantaPresence")

private val presenceScope = CoroutineScope(SupervisorJob() + ioDispatcher)
private val announceMutex = Mutex()
private val announceGate = PresenceAnnounceGate()
private val reannounceScheduler = PresenceAnnounceScheduler<Context>(presenceScope) { context ->
    announcePresence(context)
}

/**
 * NLS の接続/切断を契機に presence の再 announce を要求する（§3.1、§3.2）。デバウンスして
 * 最終状態を 1 回だけ announce する。fire-and-forget で、呼び出し元スレッドをブロックしない。
 */
fun requestPresenceReannounce(context: Context) {
    reannounceScheduler.request(context.applicationContext)
}

/**
 * control topic へ自端末の presence を告知する（§3.5）。
 * 起動時（[PerantaReceive.prime]）、エンドポイント確定時（[PerantaUnifiedPushReceiver.onNewEndpoint]）、
 * NLS 接続/切断時（[requestPresenceReannounce]）に呼ぶ。呼び出しは直列化し、同一内容の連続 announce は
 * [PresenceAnnounceGate] で抑止する（§3.2、§3.3）。
 * control topic・共有鍵・エンドポイント URL のいずれかが未設定なら何もしない。best-effort で、失敗しても伝播させない。
 */
suspend fun announcePresence(context: Context) = announceMutex.withLock {
    val appContext = context.applicationContext
    val repo = androidConfigRepository(appContext)
    val config = repo.load()
    val controlTopic = config.controlTopic ?: return@withLock
    val endpoint = config.unifiedPushEndpoint ?: return@withLock
    val deviceName = config.deviceName ?: return@withLock
    if (config.sharedKeyBase64 == null || config.keyId == null) return@withLock

    val deviceId = repo.ensureDeviceId()
    val httpClient = createNtfyHttpClient()
    try {
        val presence = buildPresencePayload(
            deviceId = deviceId,
            deviceName = deviceName,
            endpoint = endpoint,
            // 表示能力は受信設定の充足、コマンド実行能力は通知捕捉（NLS）の実接続で決まる（§3.5）。
            capabilities = presenceCapabilities(
                canDisplay = config.isReadyForUnifiedPushReceive,
                canCommand = PerantaNotificationListenerService.activeInstance != null,
            ),
            sender = config.sendEnabled,
            now = nowEpochMillis(),
        )
        val fingerprint = presenceFingerprint(presence)
        if (!announceGate.shouldAnnounce(fingerprint, presence.sentAtEpochMillis)) {
            presenceLog.d { "presence unchanged; skipping announce" }
            return@withLock
        }
        publishPresence(perantaCipher(config), KtorNtfyClient(config, httpClient), controlTopic, presence)
        announceGate.recordAnnounced(fingerprint, presence.sentAtEpochMillis)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        presenceLog.w(error) { "presence announce failed" }
    } finally {
        httpClient.close()
    }
}
