package to.sava.peranta.android

import android.content.Context
import to.sava.peranta.ui.setup.SetupItemUi
import to.sava.peranta.ui.setup.SetupItemsProvider
import to.sava.peranta.ui.setup.receiveSetupItems

/**
 * 受信のセットアップ常設画面へ渡す [SetupItemUi] 列を組む供給元。
 * 実状態の判定・素材は [AndroidSetupProbe] に、UI モデルへの写し取りは commonMain の
 * [receiveSetupItems] に委ね、ここは現在の設定を読み込んで両者をつなぐだけに留める。
 */
class AndroidReceiveSetupProvider(context: Context) : SetupItemsProvider {

    private val appContext = context.applicationContext
    private val probe = AndroidSetupProbe(appContext)

    override suspend fun items(): List<SetupItemUi> {
        val config = androidConfigRepository(appContext).load()
        return receiveSetupItems(
            ntfyInstalled = probe.ntfyInstalled(),
            otherDistributors = probe.otherDistributors(),
            endpointMatch = probe.endpointMatch(config),
            upRegistered = probe.upRegistered(config),
            ntfyBatteryIgnored = probe.ntfyBatteryIgnored(),
            selfTestStatus = PerantaSelfTest.status.value,
            selfTestRunnable = config.unifiedPushEndpoint != null && !config.accessToken.isNullOrBlank(),
            ntfyServerAids = probe.ntfyServerAids(config),
            onInstallNtfy = probe::openNtfyInStore,
            onRegister = probe::register,
            onReregister = probe::reregister,
            onOpenNtfyBattery = probe::openBatteryOptimizationList,
            onRunSelfTest = probe::startSelfTest,
        )
    }
}
