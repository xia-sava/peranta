package to.sava.peranta.ui.setup

import to.sava.peranta.config.PerantaConfig

/**
 * ウィザードの系統。Desktop は設定元・受信の 1 系統、Android は冒頭の選択で
 * 送信/受信/両方・設定元/QR 取り込みに分岐するため単一の [ANDROID] にまとめ、分岐は [WizardAnswers] が担う。
 */
enum class WizardRole {
    DESKTOP_SOURCE,
    ANDROID,
}

/** 設定の受け取り方（Android 冒頭の選択）。[JOIN] は他端末の QR で参加、[BE_SOURCE] はこの端末を設定元にする。 */
enum class WizardSourceChoice {
    JOIN,
    BE_SOURCE,
}

/**
 * ウィザード内の回答。実設定へ一意に写せない選択だけを保持する。
 * [source] は設定の受け取り方（QR 参加／設定元）の経路、[forward] は通知の自動転送の可否
 * （未回答なら null）。転送の可否は選択時に sendEnabled として直ちに config へ書くが、未回答か否かの
 * 区別（再入時の再質問要否）は config から復元できないためここに保持する。
 * 送信機能そのものは全端末が持つ前提で、ウィザードが問うのは通知の自動転送だけ。
 */
data class WizardAnswers(
    val source: WizardSourceChoice? = null,
    val forward: Boolean? = null,
)

/**
 * ウィザードの 1 ページ。
 * [itemIds] が空でないページは項目ページで、対応する [SetupItemUi] を [SetupChecklist] で描き、
 * 全項目 DONE で完了とみなす。空のページは編集ページ・選択ページ・完了ページのいずれかで、
 * 完了判定は [WizardFlow.isPageComplete] が [id] ごとに config・answers から行う。
 * [skippable] のページは「あとで設定する」で飛ばせる。
 */
data class WizardPage(
    val id: String,
    val title: String,
    val skippable: Boolean = false,
    val itemIds: List<String> = emptyList(),
)

/**
 * ロール別のページ列と、config・answers・項目状態に基づく完了判定を提供する純関数群。
 * 永続化には関与せず、渡された [PerantaConfig] / [WizardAnswers] / [SetupItemUi] 列だけで判定する。
 */
object WizardFlow {

    const val PAGE_SOURCE: String = "role-source"
    const val PAGE_QR_IMPORT: String = "qr-import"
    const val PAGE_CONNECTION: String = "connection"
    const val PAGE_DEVICE_NAME: String = "device-name"
    const val PAGE_KEY: String = "key"
    const val PAGE_PAIRING: String = "pairing"
    const val PAGE_ROLE: String = "role"
    const val PAGE_AUTOSTART: String = "autostart"
    const val PAGE_PERM_NLS: String = "perm-nls"
    const val PAGE_PERM_SELF_BATTERY: String = "perm-self-battery"
    const val PAGE_PERM_SMS: String = "perm-sms"
    const val PAGE_PERM_POST_NOTIFICATIONS: String = "perm-post-notifications"
    const val PAGE_REVERSE_CHANNEL: String = "reverse-channel"
    const val PAGE_DONE: String = "done"

    /** 権限系項目の id（[SetupItemUi.id]・健康診断 id と同一体系）。 */
    const val ITEM_NLS: String = "nls"
    const val ITEM_SELF_BATTERY: String = "self-battery"
    const val ITEM_SMS: String = "sms"
    const val ITEM_POST_NOTIFICATIONS: String = "post-notifications"

    /** 自動起動項目の id（Desktop）。 */
    const val ITEM_AUTOSTART: String = "autostart"

    /** [role] と回答済み [config] / [answers] から現在のページ列を算出する。 */
    fun pages(role: WizardRole, config: PerantaConfig, answers: WizardAnswers): List<WizardPage> =
        when (role) {
            WizardRole.DESKTOP_SOURCE -> desktopPages()
            WizardRole.ANDROID -> androidPages(config, answers)
        }

    private fun desktopPages(): List<WizardPage> =
        listOf(
            WizardPage(PAGE_CONNECTION, "接続"),
            WizardPage(PAGE_DEVICE_NAME, "端末名"),
            WizardPage(PAGE_KEY, "共有鍵"),
            WizardPage(PAGE_PAIRING, "端末の追加"),
            WizardPage(PAGE_AUTOSTART, "自動起動", skippable = true, itemIds = listOf(ITEM_AUTOSTART)),
            WizardPage(PAGE_DONE, "完了"),
        )

    private fun androidPages(config: PerantaConfig, answers: WizardAnswers): List<WizardPage> =
        buildList {
            add(WizardPage(PAGE_SOURCE, "設定の受け取り方"))
            when (answers.source) {
                // 設定元にする経路は W-D と同順（接続→端末名→鍵→端末の追加）。端末の追加の完了判定
                // isReadyForSend が deviceName を要求するため、端末名を鍵・QR より前に置く。
                WizardSourceChoice.BE_SOURCE -> {
                    add(WizardPage(PAGE_CONNECTION, "接続"))
                    add(WizardPage(PAGE_DEVICE_NAME, "端末名"))
                    add(WizardPage(PAGE_KEY, "共有鍵"))
                    add(WizardPage(PAGE_PAIRING, "端末の追加"))
                }

                // QR 取り込み経路は取り込み後に端末名を入力する（分岐内では入力欄を持たないため）。
                WizardSourceChoice.JOIN, null -> {
                    add(WizardPage(PAGE_QR_IMPORT, "QR で設定を取り込む"))
                    add(WizardPage(PAGE_DEVICE_NAME, "端末名"))
                }
            }
            add(WizardPage(PAGE_ROLE, "通知の自動転送"))
            when (answers.forward) {
                true -> addAll(sendPages(config))
                false -> addAll(receivePages())
                null -> Unit
            }
        }

    private fun sendPages(config: PerantaConfig): List<WizardPage> =
        buildList {
            add(WizardPage(PAGE_PERM_NLS, "通知へのアクセス", itemIds = listOf(ITEM_NLS)))
            add(WizardPage(PAGE_PERM_SELF_BATTERY, "バッテリー最適化の除外", itemIds = listOf(ITEM_SELF_BATTERY)))
            if (config.smsDirectReceive) {
                add(WizardPage(PAGE_PERM_SMS, "SMS の受信", itemIds = listOf(ITEM_SMS)))
            }
            add(
                WizardPage(
                    PAGE_REVERSE_CHANNEL,
                    "逆方向チャネルの受信経路",
                    skippable = true,
                    itemIds = ReceiveSetupSteps.orderedIds,
                ),
            )
            add(WizardPage(PAGE_DONE, "完了"))
        }

    private fun receivePages(): List<WizardPage> =
        buildList {
            add(WizardPage(PAGE_PERM_POST_NOTIFICATIONS, "通知の表示", itemIds = listOf(ITEM_POST_NOTIFICATIONS)))
            addAll(receiveStepPages())
            add(WizardPage(PAGE_DONE, "完了"))
        }

    /**
     * 受信手順（ntfy 導入〜受信テスト）を 1 手順 1 ページで展開する。
     * 省電力除外・受信テストは飛ばせる（サーバ ACL 不足で完走不能になるのを防ぐため）。
     */
    private fun receiveStepPages(): List<WizardPage> =
        ReceiveSetupSteps.orderedIds.map { id ->
            WizardPage(
                id = id,
                title = ReceiveSetupSteps.titleOf(id),
                skippable = id == ReceiveSetupSteps.NTFY_BATTERY_ID || id == ReceiveSetupSteps.SELF_TEST_ID,
                itemIds = listOf(id),
            )
        }

    /**
     * [page] の完了条件を満たしているか。
     * 項目ページは対応する全 [SetupItemUi] が [SetupStatus.DONE]、編集ページは config の述語、
     * 選択ページ（[PAGE_SOURCE]/[PAGE_ROLE]）は回答の有無で判定する。
     * 完了ページ（[PAGE_DONE]）は常に未完了扱いにして、全手順完了時の着地点にする。
     */
    fun isPageComplete(
        page: WizardPage,
        config: PerantaConfig,
        answers: WizardAnswers,
        items: List<SetupItemUi>,
    ): Boolean =
        when {
            page.id == PAGE_SOURCE -> config.hasSharedKey || answers.source != null
            page.id == PAGE_ROLE -> answers.forward != null
            page.id == PAGE_DONE -> false
            page.itemIds.isNotEmpty() ->
                page.itemIds.all { id -> items.firstOrNull { it.id == id }?.let(::itemPasses) ?: false }

            page.id == PAGE_CONNECTION -> config.host.isNotBlank() && !config.accessToken.isNullOrBlank()
            page.id == PAGE_DEVICE_NAME -> !config.deviceName.isNullOrBlank()
            page.id == PAGE_KEY -> config.hasSharedKey
            page.id == PAGE_PAIRING -> config.isReadyForSend
            page.id == PAGE_QR_IMPORT -> config.hasSharedKey
            else -> false
        }

    /**
     * 項目 1 件が完了ページ通過条件を満たすか。既定は [SetupStatus.DONE]。
     * ntfy サーバ設定（[ReceiveSetupSteps.SERVER_CONFIG_ID]）だけは、エンドポイント払い出し前は
     * 直接検査できず [SetupStatus.UNKNOWN] にしかならないため未確認のまま通過を許す（検証は次手順の照合が担う）。
     */
    private fun itemPasses(item: SetupItemUi): Boolean =
        item.status == SetupStatus.DONE ||
            (item.id == ReceiveSetupSteps.SERVER_CONFIG_ID && item.status == SetupStatus.UNKNOWN)

    /** [pages] のうち最初の未完了ページ。全て完了なら null（実際には完了ページが常に未完了のため着地点になる）。 */
    fun firstIncompletePage(
        pages: List<WizardPage>,
        config: PerantaConfig,
        answers: WizardAnswers,
        items: List<SetupItemUi>,
    ): WizardPage? =
        pages.firstOrNull { !isPageComplete(it, config, answers, items) }
}
