package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/** WebSockets 導入済みの Desktop 用 ntfy HTTP クライアントを生成する。 */
fun createNtfyHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets)
}
