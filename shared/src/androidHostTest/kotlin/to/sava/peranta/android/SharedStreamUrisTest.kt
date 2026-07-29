package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 自パッケージ名（FileProvider の authority もこのパッケージに属する）。 */
private const val SELF_PACKAGE = "to.sava.peranta"

/**
 * exported な受け口が受け取った添付 Uri の検証（§4.3）。
 * 共有元アプリが自分で開ける先だけを受理し、Peranta 自身の権限でしか開けない先を弾くことを固定する。
 */
class SharedStreamUrisTest {

    /** 他アプリの ContentProvider の content:// はそのまま受理する（共有シートの正当な経路）。 */
    @Test
    fun acceptsContentUriFromAnotherApp() {
        assertTrue(isAcceptedSharedStream("content", "com.android.providers.media.documents", SELF_PACKAGE))
    }

    /** authority を解決できない content:// も受理する（自パッケージの Provider は必ず解決できるため）。 */
    @Test
    fun acceptsContentUriWithUnresolvableAuthority() {
        assertTrue(isAcceptedSharedStream("content", null, SELF_PACKAGE))
    }

    /** スキームの大文字小文字は問わない。 */
    @Test
    fun acceptsContentSchemeRegardlessOfCase() {
        assertTrue(isAcceptedSharedStream("CONTENT", "com.example.gallery", SELF_PACKAGE))
    }

    /** file:// は拒否する。アプリのデータディレクトリを直接指せるため。 */
    @Test
    fun rejectsFileUri() {
        assertFalse(isAcceptedSharedStream("file", null, SELF_PACKAGE))
    }

    /** content / file 以外のスキームも拒否する。 */
    @Test
    fun rejectsOtherSchemes() {
        assertFalse(isAcceptedSharedStream("android.resource", "com.example.gallery", SELF_PACKAGE))
        assertFalse(isAcceptedSharedStream("https", null, SELF_PACKAGE))
        assertFalse(isAcceptedSharedStream(null, null, SELF_PACKAGE))
    }

    /** 自パッケージの Provider が持つ content:// は拒否する（渡す側が自分では開けない先のため）。 */
    @Test
    fun rejectsContentUriFromOwnProvider() {
        assertFalse(isAcceptedSharedStream("content", SELF_PACKAGE, SELF_PACKAGE))
    }
}
