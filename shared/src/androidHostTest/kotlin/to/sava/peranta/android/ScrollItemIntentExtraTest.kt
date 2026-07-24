package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollItemIntentExtraTest {

    /** 通常の id はそのまま透過する。 */
    @Test
    fun passesThroughNonBlankId() {
        assertEquals("abc-123", normalizeScrollItemId("abc-123"))
    }

    /** 欠落（extra 無し由来の null）は「対象なし」として null のまま。 */
    @Test
    fun nullStaysNull() {
        assertNull(normalizeScrollItemId(null))
    }

    /** 空文字・空白のみは「対象なし」として null に丸める。 */
    @Test
    fun blankBecomesNull() {
        assertNull(normalizeScrollItemId(""))
        assertNull(normalizeScrollItemId("   "))
    }
}
