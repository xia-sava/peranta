package to.sava.peranta.android

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import co.touchlab.kermit.Logger
import org.unifiedpush.android.connector.UnifiedPush
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.net.EndpointServerMatch
import to.sava.peranta.net.httpBaseUrl
import to.sava.peranta.net.matchEndpointServer
import to.sava.peranta.ui.FixAid
import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthCheckState
import to.sava.peranta.ui.HealthChecker
import to.sava.peranta.ui.endpointServerItem
import to.sava.peranta.ui.selfTestItem

/** ntfy アプリのパッケージ名。ディストリビュータ導入・省電力除外の点検対象。 */
private const val NTFY_PACKAGE = "io.heckel.ntfy"

/** 省電力設定へ直接誘導できず案内のみを出すメーカー（AQUOS の SHARP など）。 */
private val OEM_POWER_SAVE_MANUFACTURERS = setOf("SHARP")

/**
 * Android 端末の健康診断（§10.5）を、現在の設定に応じた条件で組み立てる。
 * 権限・省電力除外・ディストリビュータ・UnifiedPush 登録などの実判定は PackageManager /
 * UnifiedPush / PowerManager / NotificationManagerCompat へ委ね、結果を [HealthCheckItem] に写す。
 * 「直す」導線はシステム設定 Intent の起動で、解決できない機種向けに段階フォールバックする。
 */
class AndroidHealthChecker(context: Context) : HealthChecker {

    private val appContext = context.applicationContext
    private val log = Logger.withTag("HealthCheck")
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    override suspend fun check(): List<HealthCheckItem> {
        val config = androidConfigRepository(appContext).load()
        val ntfyInstalled = UnifiedPush.getDistributors(appContext).contains(NTFY_PACKAGE)
        return buildList {
            if (config.sendEnabled) {
                add(notificationListenerItem())
                add(selfBatteryItem())
                if (config.smsDirectReceive) {
                    add(smsPermissionItem())
                }
            }
            if (config.isReadyForUnifiedPushReceive) {
                val match = config.unifiedPushEndpoint?.let { matchEndpointServer(it, config) }
                add(ntfyInstalledItem(ntfyInstalled))
                add(unifiedPushRegisteredItem(config))
                add(
                    endpointServerItem(
                        match = match,
                        onReregister = { PerantaUnifiedPush.reregister(appContext) },
                        fixAids = listOf(
                            FixAid.Copy(label = "サーバーURL（既定のサーバー / サービスURL の2箇所）", value = config.httpBaseUrl()),
                            FixAid.Copy(label = "ヘッダ名", value = "Authorization"),
                            FixAid.Copy(label = "ヘッダ値", value = "Bearer ${config.accessToken.orEmpty()}", sensitive = true),
                            FixAid.Action(label = "ntfy を開く", onRun = ::openNtfyApp),
                        ),
                    ),
                )
                add(
                    selfTestItem(
                        status = PerantaSelfTest.status.value,
                        runnable = config.unifiedPushEndpoint != null &&
                            !config.accessToken.isNullOrBlank() && match == EndpointServerMatch.Match,
                        serverMismatch = match is EndpointServerMatch.Mismatch,
                        onRun = { PerantaSelfTest.start(appContext) },
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
        val component = ComponentName(appContext, PerantaNotificationListenerService::class.java)
        val granted = notificationManager.isNotificationListenerAccessGranted(component)
        return HealthCheckItem(
            id = "nls",
            label = "通知へのアクセス",
            state = if (granted) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (granted) null else "通知を捕捉して転送するには通知へのアクセスを許可してください。",
            fixLabel = if (granted) null else "権限を許可",
            onFix = if (granted) null else ::openNotificationListenerSettings,
        )
    }

    private fun selfBatteryItem(): HealthCheckItem {
        val ignoring = powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        return HealthCheckItem(
            id = "self-battery",
            label = "バッテリー最適化の除外",
            state = if (ignoring) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (ignoring) null else "バックグラウンドで通知を取りこぼさないよう最適化から除外してください。",
            fixLabel = if (ignoring) null else "設定を開く",
            onFix = if (ignoring) null else ::requestIgnoreSelfBattery,
        )
    }

    private fun smsPermissionItem(): HealthCheckItem {
        val granted = appContext.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        return HealthCheckItem(
            id = "sms",
            label = "SMS の受信",
            state = if (granted) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (granted) null else "SMS を直接受信して転送するには SMS 受信を許可してください。",
            fixLabel = if (granted) null else "設定を開く",
            onFix = if (granted) null else ::openAppDetailsSettings,
            fixGuidance = if (granted) {
                null
            } else {
                "アプリ情報画面が開きます。「権限」から「SMS」を選んで許可に変更し、この画面に戻ってください。"
            },
        )
    }

    private fun ntfyInstalledItem(installed: Boolean): HealthCheckItem =
        HealthCheckItem(
            id = "ntfy-installed",
            label = "ntfy アプリの導入",
            state = if (installed) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (installed) null else "UnifiedPush の配信には ntfy アプリが必要です。導入して既定に設定してください。",
            fixLabel = if (installed) null else "インストール",
            onFix = if (installed) null else ::openNtfyInStore,
            fixGuidance = if (installed) {
                null
            } else {
                "ストアが開きます。ntfy をインストールしたら一度 ntfy を開いて通知の許可を済ませ、" +
                    "この画面に戻って再チェックしてください。"
            },
        )

    private fun unifiedPushRegisteredItem(config: PerantaConfig): HealthCheckItem {
        val registered = UnifiedPush.getAckDistributor(appContext) != null &&
            config.unifiedPushEndpoint != null
        return HealthCheckItem(
            id = "unifiedpush",
            label = "UnifiedPush の登録",
            state = if (registered) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (registered) null else "受信エンドポイントの払い出しを受けるには UnifiedPush へ登録してください。",
            fixLabel = if (registered) null else "登録する",
            onFix = if (registered) null else ::registerUnifiedPush,
        )
    }

    private fun registerUnifiedPush() {
        PerantaUnifiedPush.register(appContext)
    }

    private fun ntfyBatteryItem(ntfyInstalled: Boolean): HealthCheckItem {
        if (!ntfyInstalled) {
            return HealthCheckItem(id = "ntfy-battery", label = "ntfy のバッテリー最適化除外", state = HealthCheckState.NOT_APPLICABLE)
        }
        val ignoring = powerManager.isIgnoringBatteryOptimizations(NTFY_PACKAGE)
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
            onFix = if (ignoring) null else ::openBatteryOptimizationList,
        )
    }

    private fun postNotificationsItem(): HealthCheckItem {
        val enabled = notificationManager.areNotificationsEnabled()
        return HealthCheckItem(
            id = "post-notifications",
            label = "通知の表示",
            state = if (enabled) HealthCheckState.PASS else HealthCheckState.FAILING,
            detail = if (enabled) null else "受信した通知を表示するにはこのアプリの通知を有効にしてください。",
            fixLabel = if (enabled) null else "設定を開く",
            onFix = if (enabled) null else ::openAppNotificationSettings,
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

    private fun openNotificationListenerSettings() {
        val component = ComponentName(appContext, PerantaNotificationListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        startFirstResolvable(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetailsIntent(), genericSettingsIntent())
    }

    private fun requestIgnoreSelfBattery() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
        startFirstResolvable(request, batteryOptimizationListIntent(), appDetailsIntent(), genericSettingsIntent())
    }

    private fun openBatteryOptimizationList() {
        startFirstResolvable(batteryOptimizationListIntent(), genericSettingsIntent())
    }

    private fun openNtfyInStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$NTFY_PACKAGE"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$NTFY_PACKAGE"))
        startFirstResolvable(market, web)
    }

    /** ntfy アプリを起動する。導入済み前提の操作で、解決できなければストア導線へ降格する。 */
    private fun openNtfyApp() {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(NTFY_PACKAGE)
        if (launchIntent == null) {
            openNtfyInStore()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(launchIntent)
        } catch (error: ActivityNotFoundException) {
            log.w(error) { "ntfy launch intent not resolvable" }
            openNtfyInStore()
        }
    }

    private fun openAppNotificationSettings() {
        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        startFirstResolvable(settings, appDetailsIntent(), genericSettingsIntent())
    }

    private fun openAppDetailsSettings() {
        startFirstResolvable(appDetailsIntent(), genericSettingsIntent())
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun genericSettingsIntent(): Intent = Intent(Settings.ACTION_SETTINGS)

    private fun packageUri(): Uri = Uri.fromParts("package", appContext.packageName, null)

    /**
     * 候補 Intent を順に起動し、最初に解決できたものを使う（§10.5 のフォールバック）。
     * OEM で目的の設定画面が解決できない場合に備え、汎用の設定画面まで段階的に降格する。
     */
    private fun startFirstResolvable(vararg candidates: Intent) {
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                appContext.startActivity(intent)
                return
            } catch (error: ActivityNotFoundException) {
                log.w(error) { "settings intent not resolvable: ${intent.action}" }
            }
        }
        log.w { "no settings activity resolved for any candidate" }
    }
}
