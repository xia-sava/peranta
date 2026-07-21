package to.sava.peranta.update

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import to.sava.peranta.config.DEFAULT_HOST
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.PerantaJson
import to.sava.peranta.net.httpBaseUrl

/** latest.json の配信パス。ntfy とは別のサーバ直下の静的パス（§12）。 */
private const val LATEST_MANIFEST_PATH = "dist/latest.json"

/** config のホスト設定から latest.json の URL を導出する（`https://{host}/dist/latest.json`、§12）。 */
fun latestManifestUrl(config: PerantaConfig): String =
    "${config.httpBaseUrl()}/$LATEST_MANIFEST_PATH"

/**
 * サーバの latest.json を取得し、自分の [currentVersionCode] と [platformKey] の配布物を比較する。
 * ネットワーク失敗・JSON 不正・プラットフォームキー欠落はいずれも [UpdateStatus.Failed] とし、
 * 理由を握り潰さない。
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val config: PerantaConfig,
    private val currentVersionCode: Int,
    private val platformKey: String,
    private val log: Logger = Logger.withTag("UpdateChecker"),
) {
    suspend fun check(): UpdateStatus {
        if (config.host.isBlank() || config.host == DEFAULT_HOST) {
            return UpdateStatus.NotConfigured
        }
        val url = latestManifestUrl(config)
        val response = fetch(url)
            ?: return UpdateStatus.Failed("latest.json の取得に失敗しました")
        if (!response.status.isSuccess()) {
            return UpdateStatus.Failed("latest.json の取得に失敗しました (HTTP ${response.status.value})")
        }
        val manifest = decode(response)
            ?: return UpdateStatus.Failed("latest.json の解析に失敗しました")
        val release = manifest.release(platformKey)
            ?: return UpdateStatus.Failed("latest.json にプラットフォーム '$platformKey' の項目がありません")
        if (release.versionCode <= currentVersionCode) {
            return UpdateStatus.UpToDate
        }
        log.i { "update available: ${release.versionName} (code ${release.versionCode})" }
        return UpdateStatus.Available(release.versionName, release.url)
    }

    private suspend fun fetch(url: String): HttpResponse? =
        try {
            httpClient.get(url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "latest.json fetch failed" }
            null
        }

    private suspend fun decode(response: HttpResponse): LatestManifest? =
        try {
            PerantaJson.decodeFromString<LatestManifest>(response.bodyAsText())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "latest.json decode failed" }
            null
        }
}
