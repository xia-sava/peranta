package to.sava.peranta.update

import to.sava.peranta.model.PerantaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatestManifestTest {

    /** 正常な latest.json は両プラットフォームの配布物へデコードされる。 */
    @Test
    fun decodesBothPlatforms() {
        val json = """
            {
              "android": { "versionCode": 12, "versionName": "0.3.0", "url": "https://h/dist/a.apk" },
              "desktop": { "versionCode": 13, "versionName": "0.3.1", "url": "https://h/dist/d.msi" }
            }
        """.trimIndent()

        val manifest = PerantaJson.decodeFromString<LatestManifest>(json)

        assertEquals(PlatformRelease(12, "0.3.0", "https://h/dist/a.apk"), manifest.release(PLATFORM_ANDROID))
        assertEquals(PlatformRelease(13, "0.3.1", "https://h/dist/d.msi"), manifest.release(PLATFORM_DESKTOP))
    }

    /** 片方のプラットフォームキーが欠けていれば、その release は null になる。 */
    @Test
    fun missingPlatformKeyYieldsNull() {
        val json = """
            { "android": { "versionCode": 5, "versionName": "0.1.0", "url": "https://h/a.apk" } }
        """.trimIndent()

        val manifest = PerantaJson.decodeFromString<LatestManifest>(json)

        assertEquals(5, manifest.release(PLATFORM_ANDROID)?.versionCode)
        assertNull(manifest.release(PLATFORM_DESKTOP))
    }

    /** 未知キーは無視し、未対応のプラットフォームキー要求は null を返す。 */
    @Test
    fun ignoresUnknownFieldsAndUnknownKey() {
        val json = """
            {
              "schemaVersion": 2,
              "android": { "versionCode": 7, "versionName": "0.2.0", "url": "https://h/a.apk", "sha256": "abc" }
            }
        """.trimIndent()

        val manifest = PerantaJson.decodeFromString<LatestManifest>(json)

        assertEquals(7, manifest.release(PLATFORM_ANDROID)?.versionCode)
        assertNull(manifest.release("ios"))
    }
}
