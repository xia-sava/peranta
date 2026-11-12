package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 更新経路が接続の確立を待つ上限（§12）。 */
const val EXPECTED_UPDATE_CONNECT_TIMEOUT_MILLIS: Long = 15_000L

/** 更新経路が無通信のまま待つ上限（§12）。 */
const val EXPECTED_UPDATE_STALL_TIMEOUT_MILLIS: Long = 60_000L

/**
 * エンジンへ渡す手前で止める要求の宛先。止め損ねても名前解決できないよう、予約済みの `.invalid` を使う。
 */
private const val STOPPED_REQUEST_URL = "https://example.invalid/peranta.msi"

/** 要求をエンジンへ渡す手前で止めたことを示す。 */
private class RequestStoppedBeforeEngine : RuntimeException("request stopped before the engine")

/**
 * [client] が自己更新用の設定（[configureUpdateClient]）を通っていることを確かめる。
 * 打ち切りの設定が要求に載るところまで進め、エンジンへ渡す手前で止めるので通信はしない。
 */
suspend fun assertUpdateClientConfigured(client: HttpClient) {
    assertNotNull(client.pluginOrNull(HttpTimeout), "update client has no HttpTimeout")
    var timeouts: HttpTimeoutConfig? = null
    client.plugin(HttpSend).intercept { request ->
        timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
        throw RequestStoppedBeforeEngine()
    }

    assertFailsWith<RequestStoppedBeforeEngine> { client.get(STOPPED_REQUEST_URL) }

    val applied = assertNotNull(timeouts, "update request carried no timeouts")
    assertEquals(EXPECTED_UPDATE_CONNECT_TIMEOUT_MILLIS, applied.connectTimeoutMillis)
    assertEquals(EXPECTED_UPDATE_STALL_TIMEOUT_MILLIS, applied.socketTimeoutMillis)
    assertNull(applied.requestTimeoutMillis)
}
