package to.sava.peranta.net

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout

/** 更新経路の接続確立を待つ上限。 */
private const val UPDATE_CONNECT_TIMEOUT_MILLIS = 15_000L

/** 更新経路で受信が止まったまま待つ上限。 */
private const val UPDATE_STALL_TIMEOUT_MILLIS = 60_000L

/**
 * 自己更新用の HTTP クライアントの設定（§12）。接続の確立と無通信を打ち切り、https からの降格を許さない。
 * 配布物は数十 MB あるため全体の所要時間は縛らない。ntfy の購読は接続を開いたまま待ち続けるので、
 * この打ち切りを課さないようクライアントを分ける。リダイレクトは追う。
 */
fun HttpClientConfig<*>.configureUpdateClient() {
    install(HttpTimeout) {
        connectTimeoutMillis = UPDATE_CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = UPDATE_STALL_TIMEOUT_MILLIS
    }
    install(HttpRedirect) {
        allowHttpsDowngrade = false
    }
}
