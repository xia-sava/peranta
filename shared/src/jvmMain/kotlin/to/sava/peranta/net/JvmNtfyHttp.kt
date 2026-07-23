package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/**
 * WebSockets 導入済みの Desktop 用 ntfy HTTP クライアントを生成する。
 * 定期 ping で half-open（相手不在のまま開きっぱなし）の接続を検知して閉じ、購読側の再接続へつなげる。
 */
fun createNtfyHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets) {
        pingIntervalMillis = 30_000
    }
}
