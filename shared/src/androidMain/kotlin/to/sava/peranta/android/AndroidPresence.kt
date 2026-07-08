package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.roster.CAPABILITY_COMMAND
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.buildPresencePayload
import to.sava.peranta.roster.publishPresence

private val presenceLog = Logger.withTag("PerantaPresence")

/**
 * control topic へ自端末の presence を告知する（§3.5）。
 * 起動時（[PerantaReceive.prime]）とエンドポイント確定時（[PerantaUnifiedPushReceiver.onNewEndpoint]）に呼ぶ。
 * control topic・共有鍵・エンドポイント URL のいずれかが未設定なら何もしない。best-effort で、失敗しても伝播させない。
 */
suspend fun announcePresence(context: Context) {
    val appContext = context.applicationContext
    val repo = androidConfigRepository(appContext)
    val config = repo.load()
    val controlTopic = config.controlTopic ?: return
    val endpoint = config.unifiedPushEndpoint ?: return
    val deviceName = config.deviceName ?: return
    if (config.sharedKeyBase64 == null || config.keyId == null) return

    val deviceId = repo.ensureDeviceId()
    val httpClient = createNtfyHttpClient()
    try {
        val presence = buildPresencePayload(
            deviceId = deviceId,
            deviceName = deviceName,
            endpoint = endpoint,
            capabilities = listOf(CAPABILITY_DISPLAY, CAPABILITY_COMMAND),
            sender = config.sendEnabled,
            now = nowEpochMillis(),
        )
        publishPresence(perantaCipher(config), KtorNtfyClient(config, httpClient), controlTopic, presence)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        presenceLog.w(error) { "presence announce failed" }
    } finally {
        httpClient.close()
    }
}
