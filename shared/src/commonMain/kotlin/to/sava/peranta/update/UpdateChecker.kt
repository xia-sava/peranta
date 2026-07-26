package to.sava.peranta.update

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import to.sava.peranta.model.PerantaJson

/** 配布物を置くリポジトリと、ビルドのたびに上書きされるリリースのタグ（§12）。 */
private const val RELEASE_REPOSITORY = "xia-sava/peranta"
private const val RELEASE_TAG = "latest"

/** GitHub Releases 上の配布物 URL を組む（§12）。 */
fun releaseAssetUrl(assetName: String): String =
    "https://github.com/$RELEASE_REPOSITORY/releases/download/$RELEASE_TAG/$assetName"

/** latest.json の所在（§12）。接続先の設定とは独立に引けるよう固定の配布元を指す。 */
val LATEST_MANIFEST_URL: String = releaseAssetUrl("latest.json")

/**
 * 配布元の latest.json を取得し、自分の [currentVersionCode] と [platformKey] の配布物を比較する。
 * ネットワーク失敗・JSON 不正・プラットフォームキー欠落はいずれも [UpdateStatus.Failed] とし、
 * 理由を握り潰さない。
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val currentVersionCode: Int,
    private val platformKey: String,
    private val manifestUrl: String = LATEST_MANIFEST_URL,
    private val log: Logger = Logger.withTag("UpdateChecker"),
) {
    suspend fun check(): UpdateStatus {
        val response = fetch(manifestUrl)
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
        return UpdateStatus.Available(release.versionName, release.url, release.sha256)
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
