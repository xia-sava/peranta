package to.sava.peranta.android

import android.content.Context
import android.os.Build
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState
import to.sava.peranta.ui.HealthChecker
import to.sava.peranta.ui.receiveSetupHealthItems

/** 省電力設定へ直接誘導できず案内のみを出すメーカー（AQUOS の SHARP など）。 */
private val OEM_POWER_SAVE_MANUFACTURERS = setOf("SHARP")

/**
 * Android 端末の動作チェック（§10.5）を、現在の設定に応じた条件で組み立てる。
 * 権限・省電力除外の実判定と「直す」操作・素材は [AndroidSetupProbe] に委ね、結果を [HealthCheckItem] に写す。
 * UnifiedPush 系は受信のセットアップ手順（[AndroidReceiveSetupProvider]）を診断項目へ変換し、
 * 修復手段は持たず [onOpenReceiveSetup] で受信のセットアップ画面へ誘導するだけにする。
 */
class AndroidHealthChecker(
    context: Context,
    private val onOpenReceiveSetup: () -> Unit,
) : HealthChecker {

    private val appContext = context.applicationContext
    private val probe = AndroidSetupProbe(appContext)
    private val receiveSetupProvider = AndroidReceiveSetupProvider(appContext)

    override suspend fun check(): List<HealthCheckItem> {
        val config = androidConfigRepository(appContext).load()
        return buildList {
            if (config.sendEnabled) {
                add(notificationListenerItem())
                add(selfBatteryItem())
                if (config.smsDirectReceive) {
                    add(smsPermissionItem())
                }
            }
            if (config.isReadyForUnifiedPushReceive) {
                addAll(receiveSetupHealthItems(receiveSetupProvider.items(), onOpenReceiveSetup))
            }
            if (displaysNotifications(config)) {
                add(postNotificationsItem())
            }
            oemGuidanceItem()?.let { add(it) }
        }
    }

    /** 受信して通知を表示する端末か（受信設定が揃っている）。送信端末もエラー通知・受信通知を表示する。 */
    private fun displaysNotifications(config: PerantaConfig): Boolean =
        config.isReadyForUnifiedPushReceive

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
