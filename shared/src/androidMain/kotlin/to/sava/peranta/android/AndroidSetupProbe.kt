package to.sava.peranta.android

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import co.touchlab.kermit.Logger
import org.unifiedpush.android.connector.UnifiedPush
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.net.EndpointServerMatch
import to.sava.peranta.net.httpBaseUrl
import to.sava.peranta.net.matchEndpointServer
import to.sava.peranta.ui.FixAid

/** ntfy アプリのパッケージ名。ディストリビュータ導入・省電力除外の点検対象。 */
private const val NTFY_PACKAGE = "io.heckel.ntfy"

/**
 * 受信・送信のセットアップに要する Android 実環境の判定・操作・素材を一手に持つ。
 * PackageManager / UnifiedPush / PowerManager / NotificationManager への実アクセスと、
 * 「直す」で起動するシステム設定 Intent の段階フォールバックをここへ集約し、上位の画面や
 * 動作チェックはこの結果と操作だけを使う。権限系の案内手順文もここが所有する。
 */
class AndroidSetupProbe(context: Context) {

    private val appContext = context.applicationContext
    private val log = Logger.withTag("SetupProbe")
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    /** ntfy（ディストリビュータ）が導入され既定に設定し得る状態か。 */
    fun ntfyInstalled(): Boolean =
        UnifiedPush.getDistributors(appContext).contains(NTFY_PACKAGE)

    /**
     * ntfy 以外に導入されているディストリビュータの表示名。
     * これらは自動で採用しないため（[distributorSelection]）、居ることを手順1の事実として示す。
     */
    fun otherDistributors(): List<String> =
        UnifiedPush.getDistributors(appContext)
            .filterNot { it == NTFY_PACKAGE }
            .map(::applicationLabelOf)

    /** UnifiedPush へ登録済みで受信エンドポイントの払い出しを受けているか。 */
    fun upRegistered(config: PerantaConfig): Boolean =
        UnifiedPush.getAckDistributor(appContext) != null && config.unifiedPushEndpoint != null

    /**
     * UnifiedPush へ登録済みか（ディストリビュータを ack 済みか）。
     * config の受信要件が欠けていても受信のセットアップ画面へ入れるよう、登録の有無だけを見る。
     */
    fun unifiedPushRegistered(): Boolean =
        UnifiedPush.getAckDistributor(appContext) != null

    /** 払い出しエンドポイントと設定サーバの照合結果。endpoint 未払い出しなら null。 */
    fun endpointMatch(config: PerantaConfig): EndpointServerMatch? =
        config.unifiedPushEndpoint?.let { matchEndpointServer(it, config) }

    /** 通知へのアクセス（NotificationListener）が許可されているか。 */
    fun nlsGranted(): Boolean {
        val component = ComponentName(appContext, PerantaNotificationListenerService::class.java)
        return notificationManager.isNotificationListenerAccessGranted(component)
    }

    /** このアプリがバッテリー最適化から除外されているか。 */
    fun selfBatteryIgnored(): Boolean =
        powerManager.isIgnoringBatteryOptimizations(appContext.packageName)

    /** ntfy アプリがバッテリー最適化から除外されているか。 */
    fun ntfyBatteryIgnored(): Boolean =
        powerManager.isIgnoringBatteryOptimizations(NTFY_PACKAGE)

    /** SMS 受信権限が許可されているか。 */
    fun smsGranted(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** このアプリの通知表示が有効か。 */
    fun notificationsEnabled(): Boolean =
        notificationManager.areNotificationsEnabled()

    /** ntfy 側のサーバ設定へ貼り付ける値と ntfy 起動をまとめた補助操作。貼り先は ntfy アプリの表記に合わせる。 */
    fun ntfyServerAids(config: PerantaConfig): List<FixAid> =
        listOf(
            FixAid.Copy(label = "デフォルトのサーバーに貼るサーバーURL", value = config.httpBaseUrl()),
            FixAid.Copy(label = "カスタムヘッダーのサービスURL", value = config.httpBaseUrl()),
            FixAid.Copy(label = "カスタムヘッダーのヘッダ名", value = "Authorization"),
            FixAid.Copy(label = "カスタムヘッダーのヘッダ値", value = "Bearer ${config.accessToken.orEmpty()}", sensitive = true),
            FixAid.Action(label = "ntfy を開く", onRun = ::openNtfyApp),
        )

    /** UnifiedPush へ登録し、受信エンドポイントの払い出しを促す。 */
    fun register() {
        PerantaUnifiedPush.register(appContext)
    }

    /** エンドポイントを取り直す（ntfy の既定サーバー変更後は再登録でしか新サーバーへ移れない）。 */
    fun reregister() {
        PerantaUnifiedPush.reregister(appContext)
    }

    /** 自己疎通テストを開始する。 */
    fun startSelfTest() {
        PerantaSelfTest.start(appContext)
    }

    /** 通知へのアクセスの設定画面を開く。 */
    fun openNls() {
        val component = ComponentName(appContext, PerantaNotificationListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        startFirstResolvable(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetailsIntent(), genericSettingsIntent())
    }

    /** このアプリのバッテリー最適化除外を要求する。 */
    fun requestIgnoreSelfBattery() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
        startFirstResolvable(request, batteryOptimizationListIntent(), appDetailsIntent(), genericSettingsIntent())
    }

    /** バッテリー最適化の一覧を開く（ntfy を除外させる）。 */
    fun openBatteryOptimizationList() {
        startFirstResolvable(batteryOptimizationListIntent(), genericSettingsIntent())
    }

    /** ストアで ntfy を開く。 */
    fun openNtfyInStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$NTFY_PACKAGE"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$NTFY_PACKAGE"))
        startFirstResolvable(market, web)
    }

    /** ntfy アプリを起動する。導入済み前提の操作で、解決できなければストア導線へ降格する。 */
    fun openNtfyApp() {
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

    /** このアプリの通知設定を開く。 */
    fun openAppNotificationSettings() {
        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        startFirstResolvable(settings, appDetailsIntent(), genericSettingsIntent())
    }

    /** このアプリのアプリ情報画面を開く（権限変更へ誘導）。 */
    fun openAppDetailsSettings() {
        startFirstResolvable(appDetailsIntent(), genericSettingsIntent())
    }

    /** インストール済みパッケージの表示名。解決できなければパッケージ名のまま示す。 */
    private fun applicationLabelOf(packageName: String): String {
        val packageManager = appContext.packageManager
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (error: PackageManager.NameNotFoundException) {
            log.w(error) { "application label not resolvable: $packageName" }
            packageName
        }
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

    companion object {
        /** SMS 権限を許可へ変更する手順（アプリ情報画面へ飛ばすだけでは分かりにくいため案内する）。 */
        const val SMS_FIX_GUIDANCE: String =
            "アプリ情報画面が開きます。「権限」から「SMS」を選んで許可に変更し、この画面に戻ってください。"

        /**
         * 通知へのアクセスが選べない状態の解き方。Android 13 以降、ストア以外から入れたアプリには
         * この権限の付与自体が伏せられ、設定画面へ飛ばしても操作できないまま戻ることになる。
         */
        const val RESTRICTED_SETTINGS_GUIDANCE: String =
            "選択できない状態（グレー表示）のときは、アプリ情報画面のメニューから" +
                "「制限付き設定を許可」を選んでから、もう一度お試しください。"
    }
}
