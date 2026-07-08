package to.sava.peranta.net

import to.sava.peranta.config.PerantaConfig

/**
 * config のスキーム・ホスト・ポートから HTTP(S) のベース URL（末尾スラッシュ無し）を組む。
 * port が null のときはスキーム既定ポート（https=443 / http=80）を使う。
 * ntfy の publish URL・自己更新の latest.json URL の双方がこの導出を共有する。
 */
fun PerantaConfig.httpBaseUrl(): String {
    val scheme = if (useTls) "https" else "http"
    val authority = port?.let { "$host:$it" } ?: host
    return "$scheme://$authority"
}
