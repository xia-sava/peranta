package to.sava.peranta.update

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import to.sava.peranta.model.PerantaJson

/** 配布物を置くリポジトリと、ビルドのたびに上書きされるリリースのタグ（§12）。 */
private const val RELEASE_REPOSITORY = "xia-sava/peranta"
private const val RELEASE_TAG = "latest"

/** GitHub Releases 上の配布物 URL を組む（§12）。 */
fun releaseAssetUrl(assetName: String): String =
    "https://github.com/$RELEASE_REPOSITORY/releases/download/$RELEASE_TAG/$assetName"

/** プラットフォーム毎の配布物のファイル名（§12）。固定名なのでビルドを跨いで変わらない。 */
private val RELEASE_ASSET_NAMES = mapOf(
    PLATFORM_ANDROID to "peranta.apk",
    PLATFORM_DESKTOP to "peranta.msi",
)

/** latest.json の所在（§12）。接続先の設定とは独立に引けるよう固定の配布元を指す。 */
val LATEST_MANIFEST_URL: String = releaseAssetUrl("latest.json")

/** マニフェストの署名の所在（§12）。マニフェストと同じ場所に同じ名前で並べる。 */
private const val MANIFEST_SIGNATURE_SUFFIX = ".sig"

/** 署名を確かめられなかったときの理由。確かめられなかったこと以上の内部事情は伝えない。 */
private const val SIGNATURE_UNVERIFIED = "配布物の署名を確認できませんでした"

/**
 * 配布元の latest.json を取得し、自分の [currentVersionCode] と [platformKey] の配布物を比較する。
 * ネットワーク失敗・JSON 不正・プラットフォームキー欠落はいずれも [UpdateStatus.Failed] とし、
 * 理由を握り潰さない。
 *
 * 配布物の取得先はマニフェストの指定を受け付けず、[releaseAssetUrl] で固定の配布元から組み立てる。
 * マニフェストが動かせるのは版と照合値だけになる。
 *
 * マニフェストは署名を検証してからでないと解釈しない。署名が無い・読めない・鍵が合わない場合は
 * いずれも拒否し、検証を省く経路を作らない。検証の対象は取得したバイト列そのものとする。
 *
 * [manifestUrl] と [publicKey] は取得先と信頼の起点を差し替えるための口で、
 * 配布物としての動作では固定の [LATEST_MANIFEST_URL] と [MANIFEST_PUBLIC_KEY] を使う。
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val currentVersionCode: Int,
    private val platformKey: String,
    private val manifestUrl: String = LATEST_MANIFEST_URL,
    private val publicKey: String = MANIFEST_PUBLIC_KEY,
    private val log: Logger = Logger.withTag("UpdateChecker"),
) {
    suspend fun check(): UpdateStatus {
        val assetName = RELEASE_ASSET_NAMES[platformKey]
            ?: return UpdateStatus.Failed("プラットフォーム '$platformKey' の配布物がありません")
        val response = fetch(manifestUrl)
            ?: return UpdateStatus.Failed("latest.json の取得に失敗しました")
        if (!response.status.isSuccess()) {
            return UpdateStatus.Failed("latest.json の取得に失敗しました (HTTP ${response.status.value})")
        }
        val body = bytesOf(response)
            ?: return UpdateStatus.Failed("latest.json の取得に失敗しました")
        if (!hasValidSignature(body)) {
            log.w { "manifest signature not verified" }
            return UpdateStatus.Failed(SIGNATURE_UNVERIFIED)
        }
        val manifest = decode(body)
            ?: return UpdateStatus.Failed("latest.json の解析に失敗しました")
        val release = manifest.release(platformKey)
            ?: return UpdateStatus.Failed("latest.json にプラットフォーム '$platformKey' の項目がありません")
        if (release.versionCode <= currentVersionCode) {
            log.i { "update check: up to date (code $currentVersionCode)" }
            return UpdateStatus.UpToDate
        }
        log.i { "update available: ${release.versionName} (code ${release.versionCode})" }
        return UpdateStatus.Available(release.versionName, releaseAssetUrl(assetName), release.sha256)
    }

    /** マニフェストに添えられた署名を取得して照合する。取得できない署名は不正な署名と同じく拒否する。 */
    private suspend fun hasValidSignature(manifest: ByteArray): Boolean =
        fetch("$manifestUrl$MANIFEST_SIGNATURE_SUFFIX")
            ?.takeIf { response -> response.status.isSuccess() }
            ?.let { response -> bytesOf(response) }
            ?.let { signature -> verifyManifestSignature(manifest, signature.decodeToString(), publicKey) }
            ?: false

    private suspend fun fetch(url: String): HttpResponse? =
        try {
            httpClient.get(url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "latest.json fetch failed" }
            null
        }

    private suspend fun bytesOf(response: HttpResponse): ByteArray? =
        try {
            response.bodyAsBytes()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "response body read failed" }
            null
        }

    private fun decode(manifest: ByteArray): LatestManifest? =
        try {
            PerantaJson.decodeFromString<LatestManifest>(manifest.decodeToString())
        } catch (error: Exception) {
            log.w(error) { "latest.json decode failed" }
            null
        }
}
