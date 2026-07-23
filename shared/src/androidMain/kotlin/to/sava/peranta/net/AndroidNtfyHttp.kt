package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

/**
 * WebSockets 導入済みの Android 用 ntfy HTTP クライアントを生成する。
 * 定期 ping で half-open（相手不在のまま開きっぱなし）の接続を検知して閉じ、購読側の再接続へつなげる。
 * OkHttp エンジンは WebSockets プラグインの pingInterval を使わないため、OkHttp 側の設定で行う。
 */
fun createNtfyHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            pingInterval(30, TimeUnit.SECONDS)
        }
    }
    install(WebSockets)
}
