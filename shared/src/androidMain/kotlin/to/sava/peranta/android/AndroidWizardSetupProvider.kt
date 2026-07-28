package to.sava.peranta.android

import android.content.Context
import to.sava.peranta.ui.setup.SetupItemUi
import to.sava.peranta.ui.setup.SetupItemsProvider
import to.sava.peranta.ui.setup.WizardFlow
import to.sava.peranta.ui.setup.permissionSetupItem

/** 権限系項目の案内文（機能で説明する。手段の詳細は各設定画面へ委ねる）。 */
private const val NLS_DESCRIPTION: String =
    "この端末の通知を他の端末へ送るために、通知へのアクセスを許可します。" +
        AndroidSetupProbe.RESTRICTED_SETTINGS_GUIDANCE
private const val SELF_BATTERY_DESCRIPTION: String =
    "バックグラウンドでも通知を取りこぼさないよう、この端末を最適化から除外します。"
private const val POST_NOTIFICATIONS_DESCRIPTION: String =
    "受け取った通知をこの端末に表示するために、通知を有効にします。"
private const val COMPANION_DESCRIPTION: String =
    "メッセージの本文も転送できるようにするため、PC とセットで使う機器としてこの端末を登録します。" +
        "一覧から PC を選んでください。"

/**
 * ウィザードの項目ページへ渡す [SetupItemUi] 列を組む供給元。
 * 権限系（通知アクセス・省電力除外・SMS・通知表示）は [AndroidSetupProbe] の判定・操作から commonMain の
 * [permissionSetupItem] で組み立て、受信系は M2 の [AndroidReceiveSetupProvider] へ委譲して二重配線を避ける。
 * ウィザードは id で必要な項目だけ拾う。
 * コンパニオン機器の登録（[CompanionAssociation]）はシステムのダイアログを Activity から起こす必要があるため、
 * 操作だけ [onRequestCompanionAssociation] で注入する。登録が要らない端末では項目自体を出さない。
 */
class AndroidWizardSetupProvider(
    context: Context,
    private val onRequestCompanionAssociation: () -> Unit = {},
) : SetupItemsProvider {

    private val appContext = context.applicationContext
    private val probe = AndroidSetupProbe(appContext)
    private val receiveSetupProvider = AndroidReceiveSetupProvider(appContext)

    override suspend fun items(): List<SetupItemUi> {
        val permissionItems = listOfNotNull(
            permissionSetupItem(
                id = WizardFlow.ITEM_NLS,
                title = "通知へのアクセス",
                description = NLS_DESCRIPTION,
                granted = probe.nlsGranted(),
                actionLabel = "権限を許可",
                onFix = probe::openNls,
            ),
            companionItem(),
            permissionSetupItem(
                id = WizardFlow.ITEM_SELF_BATTERY,
                title = "バッテリー最適化の除外",
                description = SELF_BATTERY_DESCRIPTION,
                granted = probe.selfBatteryIgnored(),
                actionLabel = "設定を開く",
                onFix = probe::requestIgnoreSelfBattery,
            ),
            permissionSetupItem(
                id = WizardFlow.ITEM_SMS,
                title = "SMS の受信",
                description = AndroidSetupProbe.SMS_FIX_GUIDANCE,
                granted = probe.smsGranted(),
                actionLabel = "設定を開く",
                onFix = probe::openAppDetailsSettings,
            ),
            permissionSetupItem(
                id = WizardFlow.ITEM_POST_NOTIFICATIONS,
                title = "通知の表示",
                description = POST_NOTIFICATIONS_DESCRIPTION,
                granted = probe.notificationsEnabled(),
                actionLabel = "設定を開く",
                onFix = probe::openAppNotificationSettings,
            ),
        )
        return permissionItems + receiveSetupProvider.items()
    }

    /** コンパニオン機器の登録項目。登録が要らない Android バージョンでは null（項目を出さない）。 */
    private fun companionItem(): SetupItemUi? {
        if (!CompanionAssociation.isRequired()) return null
        return permissionSetupItem(
            id = WizardFlow.ITEM_COMPANION,
            title = "PC とのペア登録",
            description = COMPANION_DESCRIPTION,
            granted = CompanionAssociation.isAssociated(appContext),
            actionLabel = "登録する",
            onFix = onRequestCompanionAssociation,
        )
    }
}
