package to.sava.peranta.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateInstallerTest {

    /** http/https でホストを持つ URL は開いてよい。 */
    @Test
    fun acceptsHttpAndHttps() {
        assertTrue(isBrowsableHttpUrl("http://example.com/app.msi"))
        assertTrue(isBrowsableHttpUrl("https://example.com/app.msi"))
        assertTrue(isBrowsableHttpUrl("HTTPS://Example.com/app.msi"))
    }

    /** http/https 以外のスキームは拒否する（file・javascript 等）。 */
    @Test
    fun rejectsNonHttpSchemes() {
        assertFalse(isBrowsableHttpUrl("file:///etc/passwd"))
        assertFalse(isBrowsableHttpUrl("javascript:alert(1)"))
        assertFalse(isBrowsableHttpUrl("ftp://example.com/app.msi"))
    }

    /** スキーム・ホストを欠く、または不正な形式の URL は拒否する。 */
    @Test
    fun rejectsMalformedOrHostlessUrls() {
        assertFalse(isBrowsableHttpUrl("example.com/app.msi"))
        assertFalse(isBrowsableHttpUrl("http://"))
        assertFalse(isBrowsableHttpUrl("http:// space"))
        assertFalse(isBrowsableHttpUrl(""))
    }
}
