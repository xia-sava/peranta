package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.messageCipher
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.roster.PresenceAnnouncer
import to.sava.peranta.roster.PresenceSource
import to.sava.peranta.roster.publishPresence

private val presenceLog = Logger.withTag("PerantaPresence")

// 告知はプロセスの寿命のスコープで走らせる。NLS の切断の直後にサービスが破棄されても、その告知を失わない。
private val presenceScope = CoroutineScope(SupervisorJob() + ioDispatcher)

private val presenceAnnouncerLock = Any()

@Volatile
private var sharedPresenceAnnouncer: PresenceAnnouncer? = null

/** プロセス内で共有する presence の告知（[PresenceAnnouncer]）。どの契機の告知も同じ直列化と抑止を通す。 */
private fun presenceAnnouncer(context: Context): PresenceAnnouncer =
    sharedPresenceAnnouncer ?: synchronized(presenceAnnouncerLock) {
        sharedPresenceAnnouncer ?: context.applicationContext.let { appContext ->
            PresenceAnnouncer(
                scope = presenceScope,
                source = { loadPresenceSource(appContext) },
                log = presenceLog,
            )
        }.also { sharedPresenceAnnouncer = it }
    }

/**
 * NLS の接続/切断、通知の転送、アイテムの受信を契機に presence の再 announce を要求する（§3.5）。
 * デバウンスして最終状態を 1 回だけ announce する。fire-and-forget で、呼び出し元スレッドを
 * ブロックしない。同一内容の再告知は最小間隔で抑止する。
 * 転送と受信を契機に含めるのは、稼働し続けている端末ほど control topic 上の presence が保持期間を
 * 過ぎて消え、コマンドの宛先として引けなくなるため（§3.5）。
 */
fun requestPresenceReannounce(context: Context) {
    presenceAnnouncer(context).request()
}

/**
 * control topic へ自端末の presence を告知する（§3.5）。
 * 起動時（[PerantaReceive.prime]）とエンドポイント確定時（[PerantaUnifiedPushReceiver.onNewEndpoint]）に呼び、
 * 告知を終えるまで待つ。[requestPresenceReannounce] の告知とも直列化し、同一内容の連続 announce は抑止する。
 * 設定が足りなければ（[androidPresenceSource]）何もしない。best-effort で、失敗しても伝播させない。
 */
suspend fun announcePresence(context: Context) {
    presenceAnnouncer(context).announce()
}

/** 保存済みの設定と NLS の接続状態から、告知に載せる自端末の情報を組む。 */
private fun loadPresenceSource(context: Context): PresenceSource? {
    val repo = androidConfigRepository(context)
    val config = repo.load()
    return androidPresenceSource(
        config = config,
        deviceId = repo::ensureDeviceId,
        canCommand = PerantaNotificationListenerService.activeInstance != null,
        publish = { controlTopic, presence -> publishPresenceWithOwnClient(config, controlTopic, presence) },
    )
}

/**
 * Android の設定から、presence の告知に載せる自端末の情報を組む（§3.5）。
 * control topic・UnifiedPush のエンドポイント URL・端末名・共有鍵・keyId のいずれかが未設定なら null を返し、
 * そのときは [deviceId] を呼ばない（端末 ID を確定させない）。
 * 表示能力は受信設定の充足で決まり、コマンド実行能力は通知捕捉（NLS）の実接続（[canCommand]）で決まる。
 * 返す [PresenceSource.publish] は、設定の control topic と presence を [publish] へ渡す。
 */
internal fun androidPresenceSource(
    config: PerantaConfig,
    deviceId: () -> String,
    canCommand: Boolean,
    publish: suspend (controlTopic: String, presence: PresencePayload) -> Unit,
): PresenceSource? {
    val controlTopic = config.controlTopic ?: return null
    val endpoint = config.unifiedPushEndpoint ?: return null
    val deviceName = config.deviceName ?: return null
    val keyId = config.keyId ?: return null
    if (config.sharedKeyBase64 == null) return null
    return PresenceSource(
        deviceId = deviceId(),
        deviceName = deviceName,
        endpoint = endpoint,
        canDisplay = config.isReadyForUnifiedPushReceive,
        canCommand = canCommand,
        sender = config.sendEnabled,
        destination = "$controlTopic|$keyId",
        publish = { presence -> publish(controlTopic, presence) },
    )
}

/** 告知のたびに HTTP クライアントを作って publish し、終えたら閉じる。 */
private suspend fun publishPresenceWithOwnClient(
    config: PerantaConfig,
    controlTopic: String,
    presence: PresencePayload,
) {
    val httpClient = createNtfyHttpClient()
    try {
        publishPresence(config.messageCipher(), KtorNtfyClient(config, httpClient), controlTopic, presence)
    } finally {
        httpClient.close()
    }
}
