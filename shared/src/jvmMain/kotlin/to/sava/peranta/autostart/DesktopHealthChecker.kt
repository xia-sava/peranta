package to.sava.peranta.autostart

import to.sava.peranta.DesktopSelfTest
import to.sava.peranta.net.SelfTestResult
import to.sava.peranta.net.SelfTestStatus
import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState
import to.sava.peranta.ui.HealthChecker

/** 動作チェックにおけるサーバ経由の受信テスト項目の id（§10.5）。 */
const val RECEIVE_SELF_TEST_ID: String = "receive-self-test"

/**
 * Desktop の動作チェック（§10.5）。点検項目はログオン時自動起動（§3.3）と
 * サーバ経由の受信テスト（§10.5）。配布物でない開発実行では自動起動を扱えないため、
 * その項目は対象外（画面に出さない）とする。
 *
 * [selfTest] は「今この瞬間、正常稼働中の受信機」を返すプロバイダ。[check] は毎回使い捨て
 * 生成される（呼び出し側は値でなくラムダで受け、実行時点の受信機実体を読む）。
 */
class DesktopHealthChecker(
    private val autoStart: AutoStartManager,
    private val selfTest: () -> DesktopSelfTest? = { null },
) : HealthChecker {

    override suspend fun check(): List<HealthCheckItem> = listOf(autoStartItem(), selfTestItem())

    private fun autoStartItem(): HealthCheckItem = when (autoStart.status()) {
        AutoStartStatus.NOT_SUPPORTED -> HealthCheckItem(
            id = "autostart",
            label = "ログオン時の自動起動",
            state = HealthCheckState.NOT_APPLICABLE,
        )

        AutoStartStatus.ENABLED -> HealthCheckItem(
            id = "autostart",
            label = "ログオン時の自動起動",
            state = HealthCheckState.PASS,
            detail = "サインイン時にトレイ常駐で自動起動します。",
            fixLabel = "解除する",
            onFix = { autoStart.disable() },
        )

        AutoStartStatus.DISABLED -> HealthCheckItem(
            id = "autostart",
            label = "ログオン時の自動起動",
            state = HealthCheckState.FAILING,
            detail = "サインイン後すぐ受信を始めるには自動起動を登録してください。",
            fixLabel = "登録する",
            onFix = { check(autoStart.enable()) { "自動起動の登録に失敗しました。しばらくしてから再試行してください。" } },
        )
    }

    /**
     * サーバ経由の受信テスト（§10.5）。受信機が未稼働（設定不足・エラー停止）なら実行できない理由を
     * INFO で示す。稼働中なら probe の現在状態を写し、実行ボタンは状態に依らず常設する。
     */
    private fun selfTestItem(): HealthCheckItem {
        val handle = selfTest() ?: return HealthCheckItem(
            id = RECEIVE_SELF_TEST_ID,
            label = "サーバ経由の受信テスト",
            state = HealthCheckState.INFO,
            detail = "受信を開始していないため実行できません。" +
                "設定（サーバ・アクセストークン・端末名・暗号キー）を確認してください。",
        )
        val status = handle.selfTestStatus.value
        return HealthCheckItem(
            id = RECEIVE_SELF_TEST_ID,
            label = "サーバ経由の受信テスト",
            state = selfTestStateOf(status),
            detail = selfTestDetailOf(status),
            fixLabel = if (status == SelfTestStatus.NotRun) "テスト実行" else "再実行",
            onFix = handle::startSelfTest,
        )
    }
}

/** [SelfTestStatus] から動作チェックの合否状態への写像。実行中・未実行は情報表示に留める。 */
private fun selfTestStateOf(status: SelfTestStatus): HealthCheckState = when (status) {
    SelfTestStatus.NotRun, SelfTestStatus.Running -> HealthCheckState.INFO
    is SelfTestStatus.Done ->
        if (status.result == SelfTestResult.Delivered) HealthCheckState.PASS else HealthCheckState.FAILING
}

/** [SelfTestStatus] の状態説明文。 */
private fun selfTestDetailOf(status: SelfTestStatus): String = when (status) {
    SelfTestStatus.NotRun ->
        "まだ実行していません。テスト通知を自分宛にサーバ経由で送り、実際に受信できるかを確認します。"
    SelfTestStatus.Running -> "確認中です。数秒お待ちください（自動で再チェックされます）。"
    is SelfTestStatus.Done -> selfTestResultDetail(status.result)
}

/** [SelfTestResult] の結末ごとの事実記述と対処案内。 */
private fun selfTestResultDetail(result: SelfTestResult): String = when (result) {
    SelfTestResult.Delivered -> "サーバ経由の配送を確認しました。"
    SelfTestResult.Timeout ->
        "テスト通知を送信しましたが、5 秒以内に届きませんでした。ネットワークと購読接続の状態を確認して再実行してください。"
    is SelfTestResult.PublishRejected ->
        "サーバが送信を拒否しました（HTTP ${result.status}）。アクセストークンとサーバ設定を確認してください。"
    SelfTestResult.PublishFailed -> "サーバに接続できませんでした。サーバ設定とネットワークを確認してください。"
}
