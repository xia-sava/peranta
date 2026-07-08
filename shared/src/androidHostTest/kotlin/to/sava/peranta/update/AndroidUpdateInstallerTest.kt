package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidUpdateInstallerTest {

    private val expected = "to.sava.peranta"

    /** packageName が自アプリと一致すればインストールへ進める。 */
    @Test
    fun acceptsMatchingPackage() {
        assertTrue(isExpectedApkPackage(expected, expected))
    }

    /** 別アプリの APK は拒否する。 */
    @Test
    fun rejectsDifferentPackage() {
        assertFalse(isExpectedApkPackage("com.evil.app", expected))
    }

    /** 解析不能（null。APK でない・破損等）は拒否する。 */
    @Test
    fun rejectsUnparseableApk() {
        assertFalse(isExpectedApkPackage(null, expected))
    }
}
