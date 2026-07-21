package to.sava.peranta.net

import io.ktor.http.Url
import to.sava.peranta.config.PerantaConfig

/** エンドポイント URL と設定サーバの照合結果。 */
sealed interface EndpointServerMatch {
    /** スキーム・ホスト・ポートが一致。 */
    data object Match : EndpointServerMatch

    /** 不一致。origin は「scheme://host[:port]」表記（既定ポートは省略）。 */
    data class Mismatch(val endpointOrigin: String, val configOrigin: String) : EndpointServerMatch

    /** URL として解釈できない。 */
    data object Unparseable : EndpointServerMatch
}

/** https の既定ポート。 */
private const val DEFAULT_PORT_HTTPS: Int = 443

/** http の既定ポート。 */
private const val DEFAULT_PORT_HTTP: Int = 80

/**
 * [endpoint]（UnifiedPush 払い出し URL）が [config] のサーバを向いているか照合する（§10.5）。
 * スキーム・ホスト・ポートを既定ポートへ正規化して比較し、ホストは大文字小文字を無視する。
 * [endpoint] が URL として解釈できない場合は [EndpointServerMatch.Unparseable] を返す。
 */
fun matchEndpointServer(endpoint: String, config: PerantaConfig): EndpointServerMatch {
    // Ktor はスキーム無しの文字列を localhost 基準の相対 URL として解釈するため、先に不正として扱う。
    if (!endpoint.contains("://")) {
        return EndpointServerMatch.Unparseable
    }
    val url = try {
        Url(endpoint)
    } catch (error: Exception) {
        return EndpointServerMatch.Unparseable
    }
    val configScheme = if (config.useTls) "https" else "http"
    val configPort = config.port ?: if (config.useTls) DEFAULT_PORT_HTTPS else DEFAULT_PORT_HTTP

    val matches = url.protocol.name.equals(configScheme, ignoreCase = true) &&
        url.host.equals(config.host, ignoreCase = true) &&
        url.port == configPort
    if (matches) {
        return EndpointServerMatch.Match
    }
    return EndpointServerMatch.Mismatch(
        endpointOrigin = originOf(url.protocol.name, url.host, url.port),
        configOrigin = config.httpBaseUrl(),
    )
}

/** 「scheme://host[:port]」表記を組む。既定ポート（https=443 / http=80）は省略する。 */
private fun originOf(scheme: String, host: String, port: Int): String {
    val isDefaultPort = (scheme.equals("https", ignoreCase = true) && port == DEFAULT_PORT_HTTPS) ||
        (scheme.equals("http", ignoreCase = true) && port == DEFAULT_PORT_HTTP)
    val authority = if (isDefaultPort) host else "$host:$port"
    return "$scheme://$authority"
}
