package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevVersionTest {

    /** ビルド時刻が判れば版数の後ろに「dev-{時刻}」として添える。 */
    @Test
    fun buildTimeIsAppendedToVersionName() {
        val shown = devVersionName("0.0.0", 1_700_000_000_000L)
        assertTrue(
            Regex("""^0\.0\.0 \(dev-\d{8}_\d{6}\)$""").matches(shown),
            "unexpected format: $shown",
        )
    }

    /** ビルド時刻を解決できない実行経路では印だけを付ける。 */
    @Test
    fun devMarkerOnlyWhenBuildTimeUnknown() {
        assertEquals("0.0.0 (dev)", devVersionName("0.0.0", null))
    }
}
