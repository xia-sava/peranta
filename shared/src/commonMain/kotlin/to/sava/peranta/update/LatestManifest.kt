package to.sava.peranta.update

import kotlinx.serialization.Serializable

/** 自己更新の対象プラットフォーム識別子（latest.json のキー、§12）。 */
const val PLATFORM_ANDROID: String = "android"
const val PLATFORM_DESKTOP: String = "desktop"

/**
 * 1 プラットフォーム分の配布物情報。
 * 配布物の所在はアプリが固定の配布元から組み立てるため、マニフェストからは受け取らない（§12）。
 */
@Serializable
data class PlatformRelease(
    val versionCode: Int,
    val versionName: String,
    /** 配布物の SHA-256（16 進）。ダウンロードした実体の照合に使う。欠けたマニフェストは受理しない。 */
    val sha256: String,
)

/**
 * `latest.json` の構造（§12）。プラットフォーム毎に配布物を持つ。
 * 未知フィールドは [to.sava.peranta.model.PerantaJson] の設定で無視する。
 */
@Serializable
data class LatestManifest(
    val android: PlatformRelease? = null,
    val desktop: PlatformRelease? = null,
) {
    /** [platformKey] に対応する配布物を返す。未対応キー・欠落は null。 */
    fun release(platformKey: String): PlatformRelease? = when (platformKey) {
        PLATFORM_ANDROID -> android
        PLATFORM_DESKTOP -> desktop
        else -> null
    }
}
