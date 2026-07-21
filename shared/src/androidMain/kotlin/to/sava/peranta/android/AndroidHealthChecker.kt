package to.sava.peranta.android

import android.content.Context
import android.os.Build
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.net.EndpointServerMatch
import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState
import to.sava.peranta.ui.HealthChecker
import to.sava.peranta.ui.endpointServerItem
import to.sava.peranta.ui.selfTestItem

/** 省電力設定へ直接誘導できず案内のみを出すメーカー（AQUOS の SHARP など）。 */
private val OEM_POWER_SAVE_MANUFACTURERS = setOf("SHARP")

/**
 * Android 端末の健康診断（§10.5）を、現在の設定に応じた条件で組み立てる。
 * 権限・省電力除外・ディストリビュータ・UnifiedPush 登録などの実判定と「直す」操作・素材は
 * [AndroidSetupProbe] に委ね、結果を [HealthCheckItem] に写す。
 */
class AndroidHealthChecker(context: Context) : HealthChecker {

    private val appContext = context.applicationContext
    private val probe = AndroidSetupProbe(appContext)

    override suspend fun check(): List<HealthCheckItem> {
        val config = androidConfigRepository(appContext).load()
        val ntfyInstalled = probe.ntfyInstalled()
        return buildList {
            if (config.sendEnabled) {
                add(notificationListenerItem())
                add(selfBatteryItem())
                if (config.smsDirectReceive) {
                    add(smsPermissionItem())
                }
            }
            if (config.isReadyForUnifiedPushReceive) {
                val match = probe.endpointMatch(config)
                add(ntfyInstalledItem(ntfyInstalled))
                add(unifiedPushRegisteredItem(config))
                add(
                    endpointServerItem(
                        match = match,
                        onReregister = probe::reregister,
                        fixAids = probe.ntfyServerAids(config),
                    ),
                )
                add(
                    selfTestItem(
                        status = PerantaSelfTest.status.value,
                        runnable = config.unifiedPushEndpoint != null &&
                            !config.accessToken.isNullOrBlank() && match == EndpointServerMatch.Match,
                        serverMismatch = match is EndpointServerMatch.Mismatch,
                        onRun = probe::startSelfTest,
                    ),
                )
                add(ntfyBatteryItem(ntfyInstalled))
            }
            if (displaysNotifications(config)) {
                add(postNotificationsItem())
            }
            oemGuidanceItem()?.let { add(it) }
        }
    }

    /** 受信して通知を表示する端末か（受信設定が揃い、かつ送信ロールでない）。 */
    private fun displaysNotifications(config: PerantaConfig): Boolean =
        config.isReadyForUnifiedPushReceive && !config.sendEnabled

    private fun notificationListenerItem(): HealthCheckItem {
        val granted = probe.nlsGranted()
        return HealthCheckItem(
            id = "nls",
            label = "通知へのアクセス",
            state = if (granted) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (granted) null else "通知を捕捉して転送するには通知へのアクセスを許可してください。",
            fixLabel = if (granted) null else "権限を許可",
            onFix = if (granted) null else probe::openNls,
        )
    }

    private fun selfBatteryItem(): HealthCheckItem {
        val ignoring = probe.selfBatteryIgnored()
        return HealthCheckItem(
            id = "self-battery",
            label = "バッテリー最適化の除外",
            state = if (ignoring) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (ignoring) null else "バックグラウンドで通知を取りこぼさないよう最適化から除外してください。",
            fixLabel = if (ignoring) null else "設定を開く",
            onFix = if (ignoring) null else probe::requestIgnoreSelfBattery,
        )
    }

    private fun smsPermissionItem(): HealthCheckItem {
        val granted = probe.smsGranted()
        return HealthCheckItem(
            id = "sms",
            label = "SMS の受信",
            state = if (granted) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (granted) null else "SMS を直接受信して転送するには SMS 受信を許可してください。",
            fixLabel = if (granted) null else "設定を開く",
            onFix = if (granted) null else probe::openAppDetailsSettings,
            fixGuidance = if (granted) null else AndroidSetupProbe.SMS_FIX_GUIDANCE,
        )
    }

    private fun ntfyInstalledItem(installed: Boolean): HealthCheckItem =
        HealthCheckItem(
            id = "ntfy-installed",
            label = "ntfy アプリの導入",
            state = if (installed) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (installed) null else "UnifiedPush の配信には ntfy アプリが必要です。導入して既定に設定してください。",
            fixLabel = if (installed) null else "インストール",
            onFix = if (installed) null else probe::openNtfyInStore,
            fixGuidance = if (installed) null else AndroidSetupProbe.NTFY_INSTALLED_FIX_GUIDANCE,
        )

    private fun unifiedPushRegisteredItem(config: PerantaConfig): HealthCheckItem {
        val registered = probe.upRegistered(config)
        return HealthCheckItem(
            id = "unifiedpush",
            label = "UnifiedPush の登録",
            state = if (registered) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (registered) null else "受信エンドポイントの払い出しを受けるには UnifiedPush へ登録してください。",
            fixLabel = if (registered) null else "登録する",
            onFix = if (registered) null else probe::register,
        )
    }

    private fun ntfyBatteryItem(ntfyInstalled: Boolean): HealthCheckItem {
        if (!ntfyInstalled) {
            return HealthCheckItem(id = "ntfy-battery", label = "ntfy のバッテリー最適化除外", state = HealthCheckState.NOT_APPLICABLE)
        }
        val ignoring = probe.ntfyBatteryIgnored()
        return HealthCheckItem(
            id = "ntfy-battery",
            label = "ntfy のバッテリー最適化除外",
            state = if (ignoring) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (ignoring) {
                null
            } else {
                "常時受信のため ntfy を最適化から除外してください。開いた一覧から「ntfy」を探して除外します。"
            },
            fixLabel = if (ignoring) null else "設定を開く",
            onFix = if (ignoring) null else probe::openBatteryOptimizationList,
        )
    }

    private fun postNotificationsItem(): HealthCheckItem {
        val enabled = probe.notificationsEnabled()
        return HealthCheckItem(
            id = "post-notifications",
            label = "通知の表示",
            state = if (enabled) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (enabled) null else "受信した通知を表示するにはこのアプリの通知を有効にしてください。",
            fixLabel = if (enabled) null else "設定を開く",
            onFix = if (enabled) null else probe::openAppNotificationSettings,
        )
    }

    private fun oemGuidanceItem(): HealthCheckItem? {
        if (Build.MANUFACTURER.uppercase() !in OEM_POWER_SAVE_MANUFACTURERS) {
            return null
        }
        return HealthCheckItem(
            id = "oem-power-save",
            label = "端末独自の省電力設定",
            state = HealthCheckState.INFO,
            detail = "この端末（${Build.MANUFACTURER}）には独自の省電力機能があります。" +
                "受信が遅れる場合は端末の設定で Peranta と ntfy を省電力の対象外にしてください。",
        )
    }
}
