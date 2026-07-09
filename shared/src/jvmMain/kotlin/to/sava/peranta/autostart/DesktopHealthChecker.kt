package to.sava.peranta.autostart

import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState
import to.sava.peranta.ui.HealthChecker

/**
 * Desktop の健康診断（§10.5）。現状の点検項目はログオン時自動起動（§3.3）のみ。
 * 配布物でない開発実行では自動起動を扱えないため、その項目は対象外（画面に出さない）とする。
 */
class DesktopHealthChecker(private val autoStart: AutoStartManager) : HealthChecker {

    override suspend fun check(): List<HealthCheckItem> = listOf(autoStartItem())

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
}
