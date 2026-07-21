package to.sava.peranta.ui.setup

import to.sava.peranta.config.PerantaConfig
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WizardFlowTest {

    private fun paired(config: PerantaConfig = PerantaConfig()): PerantaConfig =
        config.copy(sharedKeyBase64 = Base64.encode(ByteArray(32)), keyId = "1", deviceName = "d")

    private fun item(id: String, status: SetupStatus): SetupItemUi =
        SetupItemUi(id = id, title = id, description = null, status = status, statusDetail = null)

    /** 各項目を DONE 扱いにした項目列（項目ページの完了判定を満たす）。 */
    private fun allDone(vararg ids: String): List<SetupItemUi> = ids.map { item(it, SetupStatus.DONE) }

    private fun pageIds(role: WizardRole, config: PerantaConfig, answers: WizardAnswers): List<String> =
        WizardFlow.pages(role, config, answers).map { it.id }

    /**
     * 健康診断（AndroidHealthChecker）が生成する診断項目 id を config から写す契約。
     * 送信ロールで nls/self-battery/(sms)、受信可能で受信手順 5 種、受信表示ロールで post-notifications。
     * INFO の oem-power-save は合否を出さないため被覆対象に含めない。
     */
    private fun expectedHealthIds(config: PerantaConfig): Set<String> = buildSet {
        if (config.sendEnabled) {
            add(WizardFlow.ITEM_NLS)
            add(WizardFlow.ITEM_SELF_BATTERY)
            if (config.smsDirectReceive) add(WizardFlow.ITEM_SMS)
        }
        if (config.isReadyForUnifiedPushReceive) addAll(ReceiveSetupSteps.orderedIds)
        if (config.isReadyForUnifiedPushReceive && !config.sendEnabled) add(WizardFlow.ITEM_POST_NOTIFICATIONS)
    }

    // --- ロール別ページ列 ---

    /** Desktop は接続→端末名→鍵→端末追加→自動起動→完了の 6 ページ。 */
    @Test
    fun desktopPagesCoverFullSequence() {
        assertEquals(
            listOf(
                WizardFlow.PAGE_CONNECTION,
                WizardFlow.PAGE_DEVICE_NAME,
                WizardFlow.PAGE_KEY,
                WizardFlow.PAGE_PAIRING,
                WizardFlow.PAGE_AUTOSTART,
                WizardFlow.PAGE_DONE,
            ),
            pageIds(WizardRole.DESKTOP_SOURCE, PerantaConfig(), WizardAnswers()),
        )
    }

    /** Android 冒頭は A1（設定の受け取り方）から始まり、既定/参加では QR 取り込みページを挟む。 */
    @Test
    fun androidStartsWithSourceThenQrImportByDefault() {
        val ids = pageIds(WizardRole.ANDROID, PerantaConfig(), WizardAnswers())
        assertEquals(WizardFlow.PAGE_SOURCE, ids.first())
        assertTrue(ids.contains(WizardFlow.PAGE_QR_IMPORT))
        assertFalse(ids.contains(WizardFlow.PAGE_CONNECTION))
    }

    /** A1 で「設定元にする」を選ぶと接続→端末名→鍵→端末追加の順（W-D と同順）に分岐する。 */
    @Test
    fun androidBeSourceBranchExpandsToConnectionDeviceNameKeyPairingInOrder() {
        val ids = pageIds(
            WizardRole.ANDROID,
            PerantaConfig(),
            WizardAnswers(source = WizardSourceChoice.BE_SOURCE),
        )
        assertFalse(ids.contains(WizardFlow.PAGE_QR_IMPORT))
        assertEquals(
            listOf(
                WizardFlow.PAGE_SOURCE,
                WizardFlow.PAGE_CONNECTION,
                WizardFlow.PAGE_DEVICE_NAME,
                WizardFlow.PAGE_KEY,
                WizardFlow.PAGE_PAIRING,
                WizardFlow.PAGE_ROLE,
            ),
            ids,
        )
    }

    /** QR 取り込み経路では端末名ページは取り込みの後に来る。 */
    @Test
    fun androidJoinBranchPlacesDeviceNameAfterQrImport() {
        val ids = pageIds(
            WizardRole.ANDROID,
            PerantaConfig(),
            WizardAnswers(source = WizardSourceChoice.JOIN),
        )
        assertTrue(ids.indexOf(WizardFlow.PAGE_QR_IMPORT) < ids.indexOf(WizardFlow.PAGE_DEVICE_NAME))
    }

    // --- A4 役割 3 択による分岐 ---

    /** 自動転送 ON は権限系 3 ページ＋逆方向チャネル（S4）で終わる。 */
    @Test
    fun forwardOnShowsPermissionsAndReverseChannel() {
        val ids = pageIds(
            WizardRole.ANDROID,
            paired(PerantaConfig(smsDirectReceive = true)),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
        assertTrue(
            ids.containsAll(
                listOf(
                    WizardFlow.PAGE_PERM_NLS,
                    WizardFlow.PAGE_PERM_SELF_BATTERY,
                    WizardFlow.PAGE_PERM_SMS,
                    WizardFlow.PAGE_REVERSE_CHANNEL,
                ),
            ),
        )
    }

    /** SMS 直接受信が OFF なら自動転送 ON でも SMS ページは出ない。 */
    @Test
    fun forwardOnOmitsSmsPageWhenSmsDirectReceiveOff() {
        val ids = pageIds(
            WizardRole.ANDROID,
            paired(PerantaConfig(smsDirectReceive = false)),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
        assertFalse(ids.contains(WizardFlow.PAGE_PERM_SMS))
    }

    /** 自動転送 OFF は通知表示＋受信手順で、S4 は出ない。 */
    @Test
    fun forwardOffShowsPostNotificationsAndReceiveStepsWithoutReverseChannel() {
        val ids = pageIds(
            WizardRole.ANDROID,
            paired(),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
        )
        assertTrue(ids.contains(WizardFlow.PAGE_PERM_POST_NOTIFICATIONS))
        assertTrue(ids.containsAll(ReceiveSetupSteps.orderedIds))
        assertFalse(ids.contains(WizardFlow.PAGE_REVERSE_CHANNEL))
    }

    // --- skippable フラグ ---

    /** 逆方向チャネル・ntfy 省電力・受信テスト・自動起動だけが skippable で、必須の権限・編集ページは skippable でない。 */
    @Test
    fun skippableFlagsMatchSpec() {
        val send = WizardFlow.pages(
            WizardRole.ANDROID,
            paired(PerantaConfig(smsDirectReceive = true)),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
        assertTrue(send.first { it.id == WizardFlow.PAGE_REVERSE_CHANNEL }.skippable)
        assertFalse(send.first { it.id == WizardFlow.PAGE_PERM_NLS }.skippable)

        val receive = WizardFlow.pages(
            WizardRole.ANDROID,
            paired(),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
        )
        assertTrue(receive.first { it.id == ReceiveSetupSteps.NTFY_BATTERY_ID }.skippable)
        assertTrue(receive.first { it.id == ReceiveSetupSteps.SELF_TEST_ID }.skippable)
        assertFalse(receive.first { it.id == ReceiveSetupSteps.UNIFIED_PUSH_ID }.skippable)

        val desktop = WizardFlow.pages(WizardRole.DESKTOP_SOURCE, PerantaConfig(), WizardAnswers())
        assertTrue(desktop.first { it.id == WizardFlow.PAGE_AUTOSTART }.skippable)
        assertFalse(desktop.first { it.id == WizardFlow.PAGE_CONNECTION }.skippable)
    }

    // --- isPageComplete の config / items 両依存 ---

    /** 編集ページの完了は config の述語に依る（接続は host＋token）。 */
    @Test
    fun editPageCompletionDependsOnConfig() {
        val page = WizardPage(WizardFlow.PAGE_CONNECTION, "接続")
        assertFalse(WizardFlow.isPageComplete(page, PerantaConfig(accessToken = null), WizardAnswers(), emptyList()))
        assertTrue(
            WizardFlow.isPageComplete(
                page,
                PerantaConfig(host = "h", accessToken = "tk"),
                WizardAnswers(),
                emptyList(),
            ),
        )
    }

    /** 項目ページの完了は渡された項目状態に依る（全項目 DONE で完了）。 */
    @Test
    fun itemPageCompletionDependsOnItems() {
        val page = WizardPage(WizardFlow.PAGE_PERM_NLS, "通知へのアクセス", itemIds = listOf(WizardFlow.ITEM_NLS))
        assertFalse(
            WizardFlow.isPageComplete(page, PerantaConfig(), WizardAnswers(), allDone()),
        )
        assertFalse(
            WizardFlow.isPageComplete(
                page,
                PerantaConfig(),
                WizardAnswers(),
                listOf(item(WizardFlow.ITEM_NLS, SetupStatus.TODO)),
            ),
        )
        assertTrue(
            WizardFlow.isPageComplete(
                page,
                PerantaConfig(),
                WizardAnswers(),
                allDone(WizardFlow.ITEM_NLS),
            ),
        )
    }

    /** A1 は共有鍵取得済みか受け取り方を選べば完了、A4 は役割を回答すれば完了。 */
    @Test
    fun selectionPagesDependOnAnswersOrConfig() {
        val source = WizardPage(WizardFlow.PAGE_SOURCE, "設定の受け取り方")
        assertFalse(WizardFlow.isPageComplete(source, PerantaConfig(), WizardAnswers(), emptyList()))
        assertTrue(
            WizardFlow.isPageComplete(
                source,
                PerantaConfig(),
                WizardAnswers(source = WizardSourceChoice.JOIN),
                emptyList(),
            ),
        )
        assertTrue(WizardFlow.isPageComplete(source, paired(), WizardAnswers(), emptyList()))

        val rolePage = WizardPage(WizardFlow.PAGE_ROLE, "通知の自動転送")
        assertFalse(WizardFlow.isPageComplete(rolePage, paired(), WizardAnswers(), emptyList()))
        assertTrue(
            WizardFlow.isPageComplete(rolePage, paired(), WizardAnswers(forward = true), emptyList()),
        )
    }

    /**
     * R3（ntfy にサーバを設定）は初回の未払い出し状態（UNKNOWN）でも通過でき、
     * 照合不一致（TODO）になると未完了へ戻る。BLOCKED も未完了。
     */
    @Test
    fun serverConfigPageProceedsOnUnknownButNotOnTodo() {
        val page = WizardPage(
            ReceiveSetupSteps.SERVER_CONFIG_ID,
            "ntfy にサーバを設定",
            itemIds = listOf(ReceiveSetupSteps.SERVER_CONFIG_ID),
        )
        val unknown = listOf(item(ReceiveSetupSteps.SERVER_CONFIG_ID, SetupStatus.UNKNOWN))
        val mismatch = listOf(item(ReceiveSetupSteps.SERVER_CONFIG_ID, SetupStatus.TODO))
        val matched = listOf(item(ReceiveSetupSteps.SERVER_CONFIG_ID, SetupStatus.DONE))
        assertTrue(WizardFlow.isPageComplete(page, PerantaConfig(), WizardAnswers(), unknown))
        assertFalse(WizardFlow.isPageComplete(page, PerantaConfig(), WizardAnswers(), mismatch))
        assertTrue(WizardFlow.isPageComplete(page, PerantaConfig(), WizardAnswers(), matched))
    }

    /** UNKNOWN の緩和は up-server-config 限定で、他の項目（例: unifiedpush）は UNKNOWN では通過しない。 */
    @Test
    fun unknownPassIsLimitedToServerConfig() {
        val page = WizardPage(
            ReceiveSetupSteps.UNIFIED_PUSH_ID,
            "UnifiedPush の登録",
            itemIds = listOf(ReceiveSetupSteps.UNIFIED_PUSH_ID),
        )
        assertFalse(
            WizardFlow.isPageComplete(
                page,
                PerantaConfig(),
                WizardAnswers(),
                listOf(item(ReceiveSetupSteps.UNIFIED_PUSH_ID, SetupStatus.UNKNOWN)),
            ),
        )
    }

    /**
     * 編集ページの否定側エッジ: 端末名が空白のみなら未完了、鍵なしなら鍵ページは未完了、
     * 端末追加ページは鍵があっても controlTopic 未採番（isReadyForSend=false）なら未完了で、採番後に完了する。
     */
    @Test
    fun editPageCompletionRejectsBlankDeviceNameAndMissingKeyAndUnsentPairing() {
        val deviceNamePage = WizardPage(WizardFlow.PAGE_DEVICE_NAME, "端末名")
        assertFalse(
            WizardFlow.isPageComplete(deviceNamePage, PerantaConfig(deviceName = "  "), WizardAnswers(), emptyList()),
        )
        assertTrue(
            WizardFlow.isPageComplete(deviceNamePage, PerantaConfig(deviceName = "d"), WizardAnswers(), emptyList()),
        )

        val keyPage = WizardPage(WizardFlow.PAGE_KEY, "共有鍵")
        assertFalse(WizardFlow.isPageComplete(keyPage, PerantaConfig(), WizardAnswers(), emptyList()))
        assertTrue(WizardFlow.isPageComplete(keyPage, paired(), WizardAnswers(), emptyList()))

        val pairingPage = WizardPage(WizardFlow.PAGE_PAIRING, "端末の追加")
        val keyOnly = paired(PerantaConfig(host = "h", accessToken = "tk"))
        assertFalse(WizardFlow.isPageComplete(pairingPage, keyOnly, WizardAnswers(), emptyList()))
        assertTrue(
            WizardFlow.isPageComplete(
                pairingPage,
                keyOnly.copy(controlTopic = "control-topic"),
                WizardAnswers(),
                emptyList(),
            ),
        )
    }

    // --- firstIncompletePage ---

    /** BE_SOURCE 経路の中間再開: 接続だけ完了・端末名未設定なら、最初の未完了は端末名ページ。 */
    @Test
    fun firstIncompleteIsDeviceNameWhenBeSourceOnlyConnectionComplete() {
        val config = PerantaConfig(host = "h", accessToken = "tk")
        val answers = WizardAnswers(source = WizardSourceChoice.BE_SOURCE)
        val pages = WizardFlow.pages(WizardRole.ANDROID, config, answers)
        assertEquals(
            WizardFlow.PAGE_DEVICE_NAME,
            WizardFlow.firstIncompletePage(pages, config, answers, emptyList())?.id,
        )
    }

    /** 未取得・未回答なら最初の未完了は A1（設定の受け取り方）。 */
    @Test
    fun firstIncompleteIsSourceWhenNothingAnswered() {
        val config = PerantaConfig()
        val answers = WizardAnswers()
        val pages = WizardFlow.pages(WizardRole.ANDROID, config, answers)
        assertEquals(
            WizardFlow.PAGE_SOURCE,
            WizardFlow.firstIncompletePage(pages, config, answers, emptyList())?.id,
        )
    }

    /** 取得済み（hasSharedKey）で役割未回答なら、A1〜A3 を飛ばして A4（役割）が最初の未完了。 */
    @Test
    fun firstIncompleteIsRoleWhenPairedButRoleUnanswered() {
        val config = paired()
        val answers = WizardAnswers()
        val pages = WizardFlow.pages(WizardRole.ANDROID, config, answers)
        assertEquals(
            WizardFlow.PAGE_ROLE,
            WizardFlow.firstIncompletePage(pages, config, answers, emptyList())?.id,
        )
    }

    // --- §2.3 被覆検証 ---

    /**
     * ウィザードの全ページ itemIds が、その config で健康診断が出す全項目 id を覆う。
     * かつ、skippable でないページの完走だけで残り得る未達（✗）は、skippable ページ上の項目に限られる
     * （＝必須ページを完走すれば skippable を飛ばした分以外に✗が残らない構造）。
     */
    @Test
    fun wizardPagesCoverAllHealthItemsAndNonSkippableCompletionLeavesOnlySkippable() {
        data class Scenario(val config: PerantaConfig, val answers: WizardAnswers)
        val scenarios = listOf(
            Scenario(
                paired(PerantaConfig(sendEnabled = true, smsDirectReceive = true)),
                WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
            ),
            Scenario(
                paired(PerantaConfig(sendEnabled = false)),
                WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
            ),
        )
        scenarios.forEach { (config, answers) ->
            val pages = WizardFlow.pages(WizardRole.ANDROID, config, answers)
            val healthIds = expectedHealthIds(config)
            val allItemIds = pages.flatMap { it.itemIds }.toSet()
            assertTrue(allItemIds.containsAll(healthIds), "被覆漏れ: ${healthIds - allItemIds} (forward=${answers.forward})")

            val nonSkippableIds = pages.filterNot { it.skippable }.flatMap { it.itemIds }.toSet()
            val skippableIds = pages.filter { it.skippable }.flatMap { it.itemIds }.toSet()
            val remainingAfterRequired = healthIds - nonSkippableIds
            assertTrue(
                skippableIds.containsAll(remainingAfterRequired),
                "必須ページ完走後の残り $remainingAfterRequired が skippable 上に無い (forward=${answers.forward})",
            )
        }
    }

    /** Desktop の自動起動（診断 id: autostart）は D5（skippable）が覆う。 */
    @Test
    fun desktopAutostartIsCoveredBySkippablePage() {
        val autostartPage = WizardFlow.pages(WizardRole.DESKTOP_SOURCE, PerantaConfig(), WizardAnswers())
            .first { it.itemIds.contains(WizardFlow.ITEM_AUTOSTART) }
        assertTrue(autostartPage.skippable)
    }

    // --- 完走シミュレーション（各ページの完了条件を順に満たして done まで到達できる） ---

    private val key32: String = Base64.encode(ByteArray(32))

    /** 編集ページの完了に必要な config 変化を、そのページに到達した時点で適用する。 */
    private fun applyPageEffect(pageId: String, config: PerantaConfig): PerantaConfig =
        when (pageId) {
            WizardFlow.PAGE_CONNECTION -> config.copy(host = "h", accessToken = "tk")
            WizardFlow.PAGE_DEVICE_NAME -> config.copy(deviceName = "d")
            WizardFlow.PAGE_KEY -> config.copy(sharedKeyBase64 = key32, keyId = "1")
            WizardFlow.PAGE_PAIRING -> config.copy(controlTopic = "c")
            WizardFlow.PAGE_QR_IMPORT ->
                config.copy(host = "h", accessToken = "tk", sharedKeyBase64 = key32, keyId = "1", controlTopic = "c")

            else -> config
        }

    /**
     * 先頭ページから順に「各ページの完了条件を、そのページに来た時点で満たせる」ことを検証する。
     * 前ページで得た状態だけで完了できないページ（完了条件が後続ページの入力を要求する等）があれば、
     * ここで完了不能として検出する（id 集合の被覆検証だけでは順序の詰みを検出できないため）。
     */
    private fun assertWalkReachesDone(role: WizardRole, config0: PerantaConfig, answers: WizardAnswers) {
        val pages = WizardFlow.pages(role, config0, answers)
        var config = config0
        val doneIds = mutableSetOf<String>()
        pages.dropLast(1).forEach { page ->
            config = applyPageEffect(page.id, config)
            if (page.itemIds.isNotEmpty()) doneIds.addAll(page.itemIds)
            val items = doneIds.map { item(it, SetupStatus.DONE) }
            assertTrue(
                WizardFlow.isPageComplete(page, config, answers, items),
                "ページ ${page.id} が、そこまでの状態で完了できない (role=$role, source=${answers.source}, forward=${answers.forward})",
            )
        }
        val finalItems = doneIds.map { item(it, SetupStatus.DONE) }
        assertEquals(
            WizardFlow.PAGE_DONE,
            WizardFlow.firstIncompletePage(pages, config, answers, finalItems)?.id,
            "全ページ完了後の着地点が完了ページでない (role=$role, forward=${answers.forward})",
        )
    }

    @Test
    fun desktopFlowWalksToDone() {
        assertWalkReachesDone(WizardRole.DESKTOP_SOURCE, PerantaConfig(), WizardAnswers())
    }

    @Test
    fun androidJoinForwardOnFlowWalksToDone() {
        assertWalkReachesDone(
            WizardRole.ANDROID,
            PerantaConfig(smsDirectReceive = true),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
    }

    @Test
    fun androidJoinForwardOffFlowWalksToDone() {
        assertWalkReachesDone(
            WizardRole.ANDROID,
            PerantaConfig(),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
        )
    }

    @Test
    fun androidBeSourceFlowWalksToDone() {
        assertWalkReachesDone(
            WizardRole.ANDROID,
            PerantaConfig(smsDirectReceive = true),
            WizardAnswers(source = WizardSourceChoice.BE_SOURCE, forward = true),
        )
    }
}
