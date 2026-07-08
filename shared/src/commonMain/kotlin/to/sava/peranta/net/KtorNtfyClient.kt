package to.sava.peranta.net

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.PerantaJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 再接続バックオフの初期値。 */
private val INITIAL_BACKOFF: Duration = 1.seconds

/** 再接続バックオフの上限。 */
private val MAX_BACKOFF: Duration = 60.seconds

/**
 * フレーム無受信での死活検知しきい値。ntfy は約 45 秒毎に keepalive を流すため、
 * その 2 倍を無応答とみなして再接続する。
 */
private val WATCHDOG_TIMEOUT: Duration = 90.seconds

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
        log.d { "published to $topic (${body.length} bytes)" }
    }

    override fun subscribe(topic: String): Flow<NtfyEvent> = channelFlow {
        var backoff = INITIAL_BACKOFF
        var lastEventTime: Long? = null
        while (isActive) {
            _connectionState.value = NtfyConnectionState.CONNECTING
            try {
                httpClient.webSocket(
                    urlString = ntfyWsUrl(config.useTls, authority(), topic, lastEventTime),
                    request = {
                        config.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    },
                ) {
                    log.d { "websocket connected: $topic (since=$lastEventTime)" }
                    var firstFrame = true
                    while (isActive) {
                        val received: ChannelResult<Frame>? =
                            receiveWithin(WATCHDOG_TIMEOUT) { incoming.receiveCatching() }
                        if (received == null) {
                            log.w { "watchdog: no frame in $WATCHDOG_TIMEOUT, reconnecting: $topic" }
                            break
                        }
                        val frame = received.getOrNull() ?: break
                        if (firstFrame) {
                            backoff = INITIAL_BACKOFF
                            firstFrame = false
                        }
                        if (frame !is Frame.Text) continue
                        val parsed = parseFrame(frame.readText()) ?: continue
                        if (parsed.isOpen) {
                            _connectionState.value = NtfyConnectionState.SUBSCRIBED
                            log.d { "subscription established: $topic" }
                            continue
                        }
                        parsed.toEventOrNull()?.let { event ->
                            lastEventTime = event.time
                            this@channelFlow.send(event)
                        }
                    }
                }
                log.d { "websocket closed: $topic" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "websocket disconnected: $topic, retrying in $backoff" }
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

    private fun authority(): String =
        config.port?.let { "${config.host}:$it" } ?: config.host
}

/**
 * ntfy の WebSocket 購読 URL を組み立てる。
 * [since] を渡すと `?since=<time>` を付けて再接続時の取りこぼしを回収する（初回は null）。
 */
internal fun ntfyWsUrl(useTls: Boolean, authority: String, topic: String, since: Long?): String {
    val scheme = if (useTls) "wss" else "ws"
    val base = "$scheme://$authority/$topic/ws"
    return since?.let { "$base?since=$it" } ?: base
}

/** バックオフを 2 倍にし、[MAX_BACKOFF] で頭打ちにする。 */
internal fun nextBackoff(current: Duration, max: Duration = MAX_BACKOFF): Duration =
    (current * 2).coerceAtMost(max)

/** [timeout] 以内に [block] が完了すればその結果を、超過すれば null を返す。死活検知に用いる。 */
internal suspend fun <T> receiveWithin(timeout: Duration, block: suspend () -> T): T? =
    withTimeoutOrNull(timeout) { block() }

/** publish が 2xx 以外で返ったことを示す。 */
class NtfyPublishException(val status: Int, message: String) : Exception(message)
