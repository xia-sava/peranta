package to.sava.peranta.update

import kotlinx.serialization.SerializationException
import to.sava.peranta.model.PerantaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LatestManifestTest {

    /** 正常な latest.json は両プラットフォームの配布物へデコードされる。 */
    @Test
    fun decodesBothPlatforms() {
        val json = """
            {
              "android": { "versionCode": 12, "versionName": "0.3.0", "sha256": "a1" },
              "desktop": { "versionCode": 13, "versionName": "0.3.1", "sha256": "b2" }
            }
        """.trimIndent()

        val manifest = PerantaJson.decodeFromString<LatestManifest>(json)

        assertEquals(PlatformRelease(12, "0.3.0", "a1"), manifest.release(PLATFORM_ANDROID))
        assertEquals(PlatformRelease(13, "0.3.1", "b2"), manifest.release(PLATFORM_DESKTOP))
    }

    /** 片方のプラットフォームキーが欠けていれば、その release は null になる。 */
    @Test
    fun missingPlatformKeyYieldsNull() {
        val json = """
            { "android": { "versionCode": 5, "versionName": "0.1.0", "sha256": "a1" } }
        """.trimIndent()

        val manifest = PerantaJson.decodeFromString<LatestManifest>(json)

        assertEquals(5, manifest.release(PLATFORM_ANDROID)?.versionCode)
        assertNull(manifest.release(PLATFORM_DESKTOP))
    }

    /** 未知キー（配布物の所在を含む）は無視し、未対応のプラットフォームキー要求は null を返す。 */
    @Test
    fun ignoresUnknownFieldsAndUnknownKey() {
        val json = """
            {
              "schemaVersion": 2,
              "android": { "versionCode": 7, "versionName": "0.2.0", "url": "https://h/a.apk", "sha256": "a1" }
            }
        """.trimIndent()

        val manifest = PerantaJson.decodeFromString<LatestManifest>(json)

        assertEquals(7, manifest.release(PLATFORM_ANDROID)?.versionCode)
        assertNull(manifest.release("ios"))
    }

    /** sha256 を欠いたマニフェストは解析の時点で受理しない（照合を省く経路を作らない）。 */
    @Test
    fun rejectsReleaseWithoutDigest() {
        val json = """
            { "desktop": { "versionCode": 12, "versionName": "0.3.0" } }
        """.trimIndent()

        assertFailsWith<SerializationException> {
            PerantaJson.decodeFromString<LatestManifest>(json)
        }
    }
}
