package to.sava.peranta.net

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.PerantaJson
import to.sava.peranta.platform.topicForLog
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 再接続バックオフの初期値。 */
private val INITIAL_BACKOFF: Duration = 1.seconds

/** 再接続バックオフの上限。 */
private val MAX_BACKOFF: Duration = 60.seconds

/**
 * Ktor による [NtfyClient] 実装。
 * [httpClient] は WebSockets プラグインを導入済みのものを渡すこと。
 */
class KtorNtfyClient(
    private val config: PerantaConfig,
    private val httpClient: HttpClient,
    private val log: Logger = Logger.withTag("NtfyClient"),
) : NtfyClient {

    private val _connectionState = MutableStateFlow(NtfyConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<NtfyConnectionState> = _connectionState.asStateFlow()

    override suspend fun publish(topic: String, body: String, cacheSeconds: Int?) {
        val response: HttpResponse = httpClient.post(httpUrl(topic)) {
            config.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            cacheSeconds?.let { header("Cache", "${it}s") }
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw NtfyPublishException(response.status.value, "publish failed: ${response.status}")
        }
        log.d { "published to ${topicForLog(topic)} (${body.length} bytes)" }
    }

    override suspend fun fetchHistory(topic: String, since: String): List<NtfyEvent> {
        val response: HttpResponse = httpClient.get(historyUrl(topic, since)) {
            config.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) {
            throw NtfyHistoryException(response.status.value, "history fetch failed: ${response.status}")
        }
        return response.bodyAsText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseFrame(it)?.toEventOrNull() }
            .toList()
            .also { log.d { "fetched ${it.size} history events from ${topicForLog(topic)} (since=$since)" } }
    }

    override fun subscribe(topic: String): Flow<NtfyEvent> = channelFlow {
        var backoff = INITIAL_BACKOFF
        var lastEventId: String? = null
        while (isActive) {
            _connectionState.value = NtfyConnectionState.CONNECTING
            try {
                httpClient.webSocket(
                    urlString = ntfyWsUrl(config.useTls, authority(), topic, lastEventId),
                    request = {
                        config.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    },
                ) {
                    log.d { "websocket connected: ${topicForLog(topic)} (since=$lastEventId)" }
                    var firstFrame = true
                    for (frame in incoming) {
                        if (firstFrame) {
                            backoff = INITIAL_BACKOFF
                            firstFrame = false
                        }
                        if (frame !is Frame.Text) continue
                        val parsed = parseFrame(frame.readText()) ?: continue
                        if (parsed.isOpen) {
                            _connectionState.value = NtfyConnectionState.SUBSCRIBED
                            log.d { "subscription established: ${topicForLog(topic)}" }
                            continue
                        }
                        parsed.toEventOrNull()?.let { event ->
                            if (event.id.isNotBlank()) lastEventId = event.id
                            this@channelFlow.send(event)
                        }
                    }
                }
                log.d { "websocket closed: ${topicForLog(topic)}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 例外そのものは流さない。ktor の例外メッセージには接続先 URL（＝完全な topic）が載る（§16）。
                log.w { "websocket disconnected: ${topicForLog(topic)} (${e::class.simpleName}), retrying in $backoff" }
            }
            _connectionState.value = NtfyConnectionState.DISCONNECTED
            delay(backoff)
            backoff = nextBackoff(backoff)
        }
    }

    private fun parseFrame(text: String): NtfyWsMessage? =
        runCatching { PerantaJson.decodeFromString<NtfyWsMessage>(text) }
            .onFailure { log.w { "failed to parse ntfy frame (${it::class.simpleName})" } }
            .getOrNull()

    private fun httpUrl(topic: String): String = "${config.httpBaseUrl()}/$topic"

    /** ntfy の JSON ポーリング取得 URL（`?poll=1&since=<since>`）。 */
    private fun historyUrl(topic: String, since: String): String =
        "${config.httpBaseUrl()}/$topic/json?poll=1&since=$since"

    private fun authority(): String =
        config.port?.let { "${config.host}:$it" } ?: config.host
}

/**
 * ntfy の WebSocket 購読 URL を組み立てる。
 * [since] に最終受信メッセージの ID を渡すと `?since=<id>` を付けて再接続時の取りこぼしを回収する（初回は null）。
 * ntfy はメッセージ ID を排他境界として扱うため、最終受信イベントの再配送を避けられる。
 */
internal fun ntfyWsUrl(useTls: Boolean, authority: String, topic: String, since: String?): String {
    val scheme = if (useTls) "wss" else "ws"
    val base = "$scheme://$authority/$topic/ws"
    return since?.let { "$base?since=$it" } ?: base
}

/** バックオフを 2 倍にし、[MAX_BACKOFF] で頭打ちにする。 */
internal fun nextBackoff(current: Duration, max: Duration = MAX_BACKOFF): Duration =
    (current * 2).coerceAtMost(max)

/** publish が 2xx 以外で返ったことを示す。 */
class NtfyPublishException(val status: Int, message: String) : Exception(message)

/** 履歴ポーリング取得が 2xx 以外で返ったことを示す。 */
class NtfyHistoryException(val status: Int, message: String) : Exception(message)
