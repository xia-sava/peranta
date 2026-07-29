package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

/** 更新経路の接続確立を待つ上限。 */
private const val UPDATE_CONNECT_TIMEOUT_MILLIS = 15_000L

/** 更新経路で受信が止まったまま待つ上限。 */
private const val UPDATE_STALL_TIMEOUT_MILLIS = 60_000L

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

/**
 * 自己更新用の HTTP クライアントを生成する（§12）。
 * 配布物は数十 MB あるため全体の所要時間は縛らず、接続の確立と無通信だけを打ち切る。
 * ntfy の購読は接続を開いたまま待ち続けるので、この打ち切りを課さないようクライアントを分ける。
 * リダイレクトは追うが https からの降格は許さない。
 */
fun createUpdateHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = UPDATE_CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = UPDATE_STALL_TIMEOUT_MILLIS
    }
    install(HttpRedirect) {
        allowHttpsDowngrade = false
    }
}
