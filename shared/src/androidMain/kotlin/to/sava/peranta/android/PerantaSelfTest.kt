package to.sava.peranta.android

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import to.sava.peranta.net.KtorNtfyClient
import to.sava.peranta.net.SelfTestProbe
import to.sava.peranta.net.SelfTestStatus
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.roster.topicOf

/**
 * 自己疎通テストの Android 側入口（§10）。健康診断から実行し、受信口の横取りで到達を判定する。
 * プローブ状態を単一に保つためのシングルトン。
 */
object PerantaSelfTest {

    private val probe = SelfTestProbe()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val log = Logger.withTag("SelfTest")

    /** プローブの現在状態。健康診断の項目描画がこれを読む。 */
    val status: StateFlow<SelfTestStatus> get() = probe.status

    /** テストを非同期で開始する（実行中は何もしない）。受信設定が未整備なら理由をログに残して何もしない。 */
    fun start(context: Context) {
        val appContext = context.applicationContext
        val config = androidConfigRepository(appContext).load()
        val endpoint = config.unifiedPushEndpoint ?: run {
            log.i { "self-test skipped: unifiedPush endpoint not issued" }
            return
        }
        if (config.accessToken.isNullOrBlank()) {
            log.i { "self-test skipped: access token not configured" }
            return
        }
        val topic = topicOf(endpoint)
        scope.launch {
            val httpClient = createNtfyHttpClient()
            try {
                probe.run(KtorNtfyClient(config, httpClient), topic)
            } finally {
                httpClient.close()
            }
        }
    }

    /** 受信口から呼ばれ、マーカーなら横取りして true を返す。 */
    fun consumeMarker(rawMessage: String): Boolean = probe.consumeMarker(rawMessage)
}
