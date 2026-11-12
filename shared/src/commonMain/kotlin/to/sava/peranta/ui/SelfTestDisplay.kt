package to.sava.peranta.ui

import to.sava.peranta.net.SELF_TEST_TIMEOUT
import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus

/** 受信テストの実行中の説明。 */
const val SELF_TEST_RUNNING_DETAIL: String = "確認中です。数秒お待ちください（自動で再チェックされます）。"

/** 受信テストの実行ボタンのラベル。未実行なら「テスト実行」、それ以外は「再実行」。 */
fun selfTestActionLabel(status: SelfTestStatus): String =
    if (status == SelfTestStatus.NotRun) "テスト実行" else "再実行"

/** 受信テストの結末の事実だけを述べる文。対処の案内は呼び出し側が続ける。 */
fun selfTestResultFact(result: SelfTestResult): String = when (result) {
    SelfTestResult.Delivered -> "サーバ経由の配送を確認しました。"
    SelfTestResult.Timeout ->
        "テスト通知を送信しましたが、${SELF_TEST_TIMEOUT.inWholeSeconds} 秒以内に届きませんでした。"
    is SelfTestResult.PublishRejected -> "サーバが送信を拒否しました（HTTP ${result.status}）。"
    SelfTestResult.PublishFailed -> "サーバに接続できませんでした。"
}

/** 受信テストの合否。未実行・実行中は合否を出さず null。 */
fun selfTestPassed(status: SelfTestStatus): Boolean? = when (status) {
    SelfTestStatus.NotRun, SelfTestStatus.Running -> null
    is SelfTestStatus.Done -> status.result == SelfTestResult.Delivered
}
