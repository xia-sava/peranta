package to.sava.peranta.ui

import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SelfTestDisplayTest {

    private fun done(result: SelfTestResult): SelfTestStatus =
        SelfTestStatus.Done(result, atEpochMillis = 0L)

    /** 実行中の説明は固定の文言である。 */
    @Test
    fun runningDetailIsFixedText() {
        assertEquals("確認中です。数秒お待ちください（自動で再チェックされます）。", SELF_TEST_RUNNING_DETAIL)
    }

    /** 実行ボタンのラベルは、未実行だけ「テスト実行」で、実行中と完了後（結果を問わず）は「再実行」。 */
    @Test
    fun actionLabelDependsOnlyOnWhetherRun() {
        val expected = listOf(
            SelfTestStatus.NotRun to "テスト実行",
            SelfTestStatus.Running to "再実行",
            done(SelfTestResult.Delivered) to "再実行",
            done(SelfTestResult.Timeout) to "再実行",
            done(SelfTestResult.PublishRejected(403)) to "再実行",
            done(SelfTestResult.PublishFailed) to "再実行",
        )
        expected.forEach { (status, label) ->
            assertEquals(label, selfTestActionLabel(status), "status=$status")
        }
    }

    /** 結末ごとの事実の文。拒否は HTTP ステータスを含み、タイムアウトは待ち時間の秒数を含む。 */
    @Test
    fun resultFactStatesOnlyTheOutcome() {
        val expected = listOf(
            SelfTestResult.Delivered to "サーバ経由の配送を確認しました。",
            SelfTestResult.Timeout to "テスト通知を送信しましたが、5 秒以内に届きませんでした。",
            SelfTestResult.PublishRejected(403) to "サーバが送信を拒否しました（HTTP 403）。",
            SelfTestResult.PublishRejected(500) to "サーバが送信を拒否しました（HTTP 500）。",
            SelfTestResult.PublishFailed to "サーバに接続できませんでした。",
        )
        expected.forEach { (result, fact) ->
            assertEquals(fact, selfTestResultFact(result), "result=$result")
        }
    }

    /** 未実行と実行中は合否を出さず null。完了後は配送できたときだけ true で、他の結末はすべて false。 */
    @Test
    fun passedIsNullUntilDoneAndTrueOnlyWhenDelivered() {
        val expected = listOf(
            SelfTestStatus.NotRun to null,
            SelfTestStatus.Running to null,
            done(SelfTestResult.Delivered) to true,
            done(SelfTestResult.Timeout) to false,
            done(SelfTestResult.PublishRejected(403)) to false,
            done(SelfTestResult.PublishFailed) to false,
        )
        expected.forEach { (status, passed) ->
            assertEquals(passed, selfTestPassed(status), "status=$status")
        }
    }
}
