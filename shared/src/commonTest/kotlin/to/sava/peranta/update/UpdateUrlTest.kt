package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateUrlTest {

    /** 配布物の URL は、ビルドのたびに上書きされるリリースのタグ配下を指す。 */
    @Test
    fun buildsReleaseAssetUrl() {
        assertEquals(
            "https://github.com/xia-sava/peranta/releases/download/latest/peranta.msi",
            releaseAssetUrl("peranta.msi"),
        )
    }

    /** latest.json も配布物と同じリリース配下に置く。 */
    @Test
    fun manifestUrlPointsAtLatestRelease() {
        assertEquals(releaseAssetUrl("latest.json"), LATEST_MANIFEST_URL)
    }
}
