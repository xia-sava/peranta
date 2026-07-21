package to.sava.peranta.ui

import to.sava.peranta.net.EndpointServerMatch
import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        assertTrue(item.fixGuidance!!.contains("カスタムヘッダ"))
    }

    /** サーバ不一致では、渡した fixAids がそのまま項目に載る。 */
    @Test
    fun mismatchCarriesFixAidsThrough() {
        val mismatch = EndpointServerMatch.Mismatch(
            endpointOrigin = "https://other.example.com",
            configOrigin = "https://peranta.example.com",
        )
        val fixAids = listOf(
            FixAid.Copy(label = "サーバーURL", value = "https://peranta.example.com"),
            FixAid.Action(label = "ntfy を開く", onRun = {}),
        )
        val item = endpointServerItem(match = mismatch, onReregister = {}, fixAids = fixAids)
        assertEquals(fixAids, item.fixAids)
    }

    /** URL を解釈できない場合も不合格で「登録し直す」導線を持つ。 */
    @Test
    fun unparseableIsFailing() {
        val item = endpointServerItem(match = EndpointServerMatch.Unparseable, onReregister = {})
        assertEquals(HealthCheckState.FAILING, item.state)
        assertEquals("登録し直す", item.fixLabel)
    }

    /** 実行不能かつサーバ不一致が原因なら、情報項目としてその旨を案内し「直す」導線は出さない。 */
    @Test
    fun notRunnableDueToMismatchIsInfoWithoutFix() {
        val item = selfTestItem(
            status = SelfTestStatus.NotRun,
            runnable = false,
            serverMismatch = true,
            onRun = {},
        )
        assertEquals(HealthCheckState.INFO, item.state)
        assertNull(item.fixLabel)
        assertNull(item.onFix)
    }

    /** 実行不能でもサーバ不一致が原因でなければ（endpoint 未払い出し等）対象外項目になる。 */
    @Test
    fun notRunnableWithoutMismatchIsNotApplicable() {
        val item = selfTestItem(
            status = SelfTestStatus.NotRun,
            runnable = false,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.NOT_APPLICABLE, item.state)
    }

    /** 未実行は情報項目として案内し、「テスト実行」導線を持つ。 */
    @Test
    fun notRunIsInfoWithRunFix() {
        val item = selfTestItem(
            status = SelfTestStatus.NotRun,
            runnable = true,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.INFO, item.state)
        assertEquals("テスト実行", item.fixLabel)
        assertNotNull(item.onFix)
    }

    /** 実行中は情報項目として案内し、「直す」導線は出さない。 */
    @Test
    fun runningIsInfoWithoutFix() {
        val item = selfTestItem(
            status = SelfTestStatus.Running,
            runnable = true,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.INFO, item.state)
        assertNull(item.fixLabel)
        assertNull(item.onFix)
    }

    /** 配送確認できれば合格で、「再実行」導線を持つ。 */
    @Test
    fun deliveredIsPassWithRerunFix() {
        val item = selfTestItem(
            status = SelfTestStatus.Done(SelfTestResult.Delivered, atEpochMillis = 0L),
            runnable = true,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.PASS, item.state)
        assertEquals("再実行", item.fixLabel)
    }

    /** 403 拒否は不合格で、ACL 設定と up* トピックの案内を含む。 */
    @Test
    fun publishRejected403MentionsAclAndTopicPattern() {
        val item = selfTestItem(
            status = SelfTestStatus.Done(SelfTestResult.PublishRejected(403), atEpochMillis = 0L),
            runnable = true,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.FAILING, item.state)
        assertTrue(item.detail!!.contains("ACL"))
        assertTrue(item.detail!!.contains("up*"))
    }

    /** 403 以外の拒否は不合格で、実際の HTTP ステータスを案内に含む。 */
    @Test
    fun publishRejected500MentionsStatus() {
        val item = selfTestItem(
            status = SelfTestStatus.Done(SelfTestResult.PublishRejected(500), atEpochMillis = 0L),
            runnable = true,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.FAILING, item.state)
        assertTrue(item.detail!!.contains("500"))
    }

    /** 送信自体に失敗した場合も不合格になる。 */
    @Test
    fun publishFailedIsFailing() {
        val item = selfTestItem(
            status = SelfTestStatus.Done(SelfTestResult.PublishFailed, atEpochMillis = 0L),
            runnable = true,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.FAILING, item.state)
    }

    /** タイムアウトは不合格で、ntfy アプリ側の点検観点（ログイン情報・バッテリー最適化・購読）を案内する。 */
    @Test
    fun timeoutMentionsNtfySideCauses() {
        val item = selfTestItem(
            status = SelfTestStatus.Done(SelfTestResult.Timeout, atEpochMillis = 0L),
            runnable = true,
            serverMismatch = false,
            onRun = {},
        )
        assertEquals(HealthCheckState.FAILING, item.state)
        assertTrue(item.detail!!.contains("ユーザーの管理"))
        assertTrue(item.detail!!.contains("バッテリー最適化"))
        assertTrue(item.detail!!.contains("購読"))
    }
}
