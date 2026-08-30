package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertEquals

class FileProviderAuthorityTest {

    /** authority は自パッケージ名から導く。値は androidApp の manifest が宣言する provider と一致する。 */
    @Test
    fun authorityIsDerivedFromPackageName() {
        assertEquals("to.sava.peranta.fileprovider", fileProviderAuthority("to.sava.peranta"))
    }
}
