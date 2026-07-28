package to.sava.peranta

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** ビルド時に生成される版数リソースのクラスパス名。 */
private const val VERSION_RESOURCE = "/peranta-version.properties"

/** 既定 versionCode（版数ソースを解決できない場合のフォールバック）。 */
private const val FALLBACK_VERSION_CODE = 1

/** 既定 versionName。 */
private const val FALLBACK_VERSION_NAME = "0.0.0"

/**
 * Desktop の版数。ビルド時に注入される生成リソースを最優先で読み、
 * 無ければシステムプロパティ、それも無ければ既定値へフォールバックする。
 */
object DesktopVersion {

    private val properties: Properties = Properties().apply {
        DesktopVersion::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { load(it) }
    }

    val versionCode: Int =
        (properties.getProperty("versionCode") ?: System.getProperty("peranta.versionCode"))
            ?.toIntOrNull() ?: FALLBACK_VERSION_CODE

    val versionName: String =
        properties.getProperty("versionName")
            ?: System.getProperty("peranta.versionName")
            ?: FALLBACK_VERSION_NAME

    /**
     * 自分を含む成果物（jar またはクラス出力）の更新時刻。開発ビルドの見分けに使う（§12）。
     * 実行経路によっては解決できないため null を返しうる。
     */
    val buildEpochMillis: Long? = runCatching {
        val location = DesktopVersion::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
        Files.getLastModifiedTime(Path.of(location.toURI())).toMillis()
    }.getOrNull()
}
