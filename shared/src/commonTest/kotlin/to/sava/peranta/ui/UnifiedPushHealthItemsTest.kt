package to.sava.peranta.ui

import to.sava.peranta.net.EndpointServerMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedPushHealthItemsTest {

    /** endpoint 未払い出し（match が null）は対象外項目になる。 */
    @Test
    fun nullMatchIsNotApplicable() {
        val item = endpointServerItem(match = null, onReregister = null)
        assertEquals(HealthCheckState.NOT_APPLICABLE, item.state)
    }

    /** 一致していれば合格で「直す」導線は無い。 */
    @Test
    fun matchIsPassWithoutFix() {
        val item = endpointServerItem(match = EndpointServerMatch.Match, onReregister = {})
        assertEquals(HealthCheckState.PASS, item.state)
        assertNull(item.fixLabel)
    }

    /** サーバ不一致は不合格になり、案内文に両方の origin が含まれ「登録し直す」導線を持つ。 */
    @Test
    fun mismatchIsFailingWithBothOriginsInGuidance() {
        val mismatch = EndpointServerMatch.Mismatch(
            endpointOrigin = "https://other.example.com",
            configOrigin = "https://peranta.example.com",
        )
        val item = endpointServerItem(match = mismatch, onReregister = {})
        assertEquals(HealthCheckState.FAILING, item.state)
        assertEquals("登録し直す", item.fixLabel)
        assertTrue(item.detail!!.contains(mismatch.endpointOrigin))
        assertTrue(item.detail!!.contains(mismatch.configOrigin))
        assertTrue(item.fixGuidance!!.contains(mismatch.configOrigin))
    }

    /** URL を解釈できない場合も不合格で「登録し直す」導線を持つ。 */
    @Test
    fun unparseableIsFailing() {
        val item = endpointServerItem(match = EndpointServerMatch.Unparseable, onReregister = {})
        assertEquals(HealthCheckState.FAILING, item.state)
        assertEquals("登録し直す", item.fixLabel)
    }
}
