package to.sava.peranta.ui.setup

import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.platform.PlatformCapabilities
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WizardFlowTest {

    /** Desktop 相当の caps（自動起動を持ち、通知捕捉・SMS・UnifiedPush・POST_NOTIFICATIONS は持たない）。 */
    private val desktopCaps = PlatformCapabilities(
        canCaptureNotifications = false,
        canReceiveSms = false,
        usesUnifiedPush = false,
        requiresPostNotificationsPermission = false,
        supportsAutoStart = true,
    )

    /** Android 相当の caps（通知捕捉・SMS・UnifiedPush・POST_NOTIFICATIONS を持ち、自動起動は持たない）。 */
    private val androidCaps = PlatformCapabilities(
        canCaptureNotifications = true,
        canReceiveSms = true,
        usesUnifiedPush = true,
        requiresPostNotificationsPermission = true,
        supportsAutoStart = false,
    )

    private fun paired(config: PerantaConfig = PerantaConfig()): PerantaConfig =
        config.copy(sharedKeyBase64 = Base64.encode(ByteArray(32)), keyId = "1", deviceName = "d")

    private fun item(id: String, status: SetupStatus): SetupItemUi =
        SetupItemUi(id = id, title = id, description = null, status = status, statusDetail = null)

    /** 各項目を DONE 扱いにした項目列（項目ページの完了判定を満たす）。 */
    private fun allDone(vararg ids: String): List<SetupItemUi> = ids.map { item(it, SetupStatus.DONE) }

    private fun pageIds(caps: PlatformCapabilities, config: PerantaConfig, answers: WizardAnswers): List<String> =
        WizardFlow.pages(caps, config, answers).map { it.id }

    /**
     * 健康診断（AndroidHealthChecker）が生成する診断項目 id を config から写す契約。
     * 送信ロールで nls/self-battery/(sms)、受信可能で受信手順 5 種、受信可能なら送信の可否を問わず
     * post-notifications。INFO の oem-power-save は合否を出さないため被覆対象に含めない。
     */
    private fun expectedHealthIds(config: PerantaConfig): Set<String> = buildSet {
        if (config.sendEnabled) {
            add(WizardFlow.ITEM_NLS)
            add(WizardFlow.ITEM_SELF_BATTERY)
            if (config.smsDirectReceive) add(WizardFlow.ITEM_SMS)
        }
        if (config.isReadyForUnifiedPushReceive) {
            addAll(ReceiveSetupSteps.orderedIds)
            add(WizardFlow.ITEM_POST_NOTIFICATIONS)
        }
    }

    // --- 単一ページ列 × capability フィルタ（caps × source × forward の直積） ---

    /** Desktop で「設定元にする」を選ぶと接続系 4 ページ→自動起動→完了で、能力の無い受信系ページは湧かない。 */
    @Test
    fun desktopBeSourceExpandsToConnectionThenAutostart() {
        assertEquals(
            listOf(
                WizardFlow.PAGE_SOURCE,
                WizardFlow.PAGE_CONNECTION,
                WizardFlow.PAGE_DEVICE_NAME,
                WizardFlow.PAGE_KEY,
                WizardFlow.PAGE_PAIRING,
                WizardFlow.PAGE_AUTOSTART,
                WizardFlow.PAGE_DONE,
            ),
            pageIds(desktopCaps, PerantaConfig(), WizardAnswers(source = WizardSourceChoice.BE_SOURCE)),
        )
    }

    /** Desktop でも「QR で参加」を選べる。取り込み→端末名→自動起動→完了の 5 ページになる。 */
    @Test
    fun desktopJoinExpandsToQrImportThenAutostart() {
        assertEquals(
            listOf(
                WizardFlow.PAGE_SOURCE,
                WizardFlow.PAGE_QR_IMPORT,
                WizardFlow.PAGE_DEVICE_NAME,
                WizardFlow.PAGE_AUTOSTART,
                WizardFlow.PAGE_DONE,
            ),
            pageIds(desktopCaps, PerantaConfig(), WizardAnswers(source = WizardSourceChoice.JOIN)),
        )
    }

    /** 全端末が冒頭で受け取り方（PAGE_SOURCE）を問う。Desktop も未選択なら参加（QR 取り込み）を既定にする。 */
    @Test
    fun sourcePageLeadsOnEveryPlatform() {
        assertEquals(WizardFlow.PAGE_SOURCE, pageIds(desktopCaps, PerantaConfig(), WizardAnswers()).first())
        assertEquals(WizardFlow.PAGE_SOURCE, pageIds(androidCaps, PerantaConfig(), WizardAnswers()).first())
        assertTrue(pageIds(desktopCaps, PerantaConfig(), WizardAnswers()).contains(WizardFlow.PAGE_QR_IMPORT))
    }

    /** QR 取り込み経路では端末名ページは取り込みの後に来る（両プラットフォーム共通）。 */
    @Test
    fun joinBranchPlacesDeviceNameAfterQrImport() {
        listOf(desktopCaps, androidCaps).forEach { caps ->
            val ids = pageIds(caps, PerantaConfig(), WizardAnswers(source = WizardSourceChoice.JOIN))
            assertTrue(
                ids.indexOf(WizardFlow.PAGE_QR_IMPORT) < ids.indexOf(WizardFlow.PAGE_DEVICE_NAME),
                "caps=$caps",
            )
        }
    }

    /** 通知の自動転送・捕捉側権限・自動起動は capability で出し分ける（能力の無い端末では湧かない）。 */
    @Test
    fun capabilityFiltersGovernPlatformSpecificPages() {
        val android = pageIds(
            androidCaps,
            paired(PerantaConfig(smsDirectReceive = true)),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
        assertTrue(
            android.containsAll(
                listOf(
                    WizardFlow.PAGE_ROLE,
                    WizardFlow.PAGE_PERM_NLS,
                    WizardFlow.PAGE_PERM_SELF_BATTERY,
                    WizardFlow.PAGE_PERM_SMS,
                    WizardFlow.PAGE_PERM_POST_NOTIFICATIONS,
                ),
            ),
        )
        assertFalse(android.contains(WizardFlow.PAGE_AUTOSTART))

        val desktop = pageIds(desktopCaps, paired(), WizardAnswers(source = WizardSourceChoice.BE_SOURCE))
        listOf(
            WizardFlow.PAGE_ROLE,
            WizardFlow.PAGE_PERM_NLS,
            WizardFlow.PAGE_PERM_SELF_BATTERY,
            WizardFlow.PAGE_PERM_SMS,
            WizardFlow.PAGE_PERM_POST_NOTIFICATIONS,
        ).forEach { assertFalse(desktop.contains(it), "Desktop に $it が湧いている") }
        assertFalse(desktop.any { it in ReceiveSetupSteps.orderedIds })
        assertTrue(desktop.contains(WizardFlow.PAGE_AUTOSTART))
    }

    /** 自動転送 ON は捕捉側権限 3 ページ＋通知表示＋逆方向チャネル（S4 集約）で終わる。 */
    @Test
    fun forwardOnShowsPermissionsPostNotificationsAndReverseChannel() {
        val ids = pageIds(
            androidCaps,
            paired(PerantaConfig(smsDirectReceive = true)),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
        assertTrue(
            ids.containsAll(
                listOf(
                    WizardFlow.PAGE_PERM_NLS,
                    WizardFlow.PAGE_PERM_SELF_BATTERY,
                    WizardFlow.PAGE_PERM_SMS,
                    WizardFlow.PAGE_PERM_POST_NOTIFICATIONS,
                    WizardFlow.PAGE_REVERSE_CHANNEL,
                ),
            ),
        )
        // 転送 ON では受信手順は逆方向チャネル 1 ページへ集約され、R2-R6 の個別ページには展開しない。
        assertFalse(ids.any { it in ReceiveSetupSteps.orderedIds })
    }

    /** SMS 直接受信が OFF なら自動転送 ON でも SMS ページは出ない。 */
    @Test
    fun forwardOnOmitsSmsPageWhenSmsDirectReceiveOff() {
        val ids = pageIds(
            androidCaps,
            paired(PerantaConfig(smsDirectReceive = false)),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
        assertFalse(ids.contains(WizardFlow.PAGE_PERM_SMS))
    }

    /** 自動転送 OFF は通知表示＋受信手順（R2-R6）で、逆方向チャネル（S4）は出ない。 */
    @Test
    fun forwardOffShowsPostNotificationsAndReceiveStepsWithoutReverseChannel() {
        val ids = pageIds(
            androidCaps,
            paired(),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
        )
        assertTrue(ids.contains(WizardFlow.PAGE_PERM_POST_NOTIFICATIONS))
        assertTrue(ids.containsAll(ReceiveSetupSteps.orderedIds))
        assertFalse(ids.contains(WizardFlow.PAGE_REVERSE_CHANNEL))
    }

    /** 通知表示権限は送信・受信を問わず出す（送信端末もエラー通知・受信通知を表示するため）。 */
    @Test
    fun postNotificationsAppearsRegardlessOfForward() {
        listOf(true, false).forEach { forward ->
            val ids = pageIds(
                androidCaps,
                paired(),
                WizardAnswers(source = WizardSourceChoice.JOIN, forward = forward),
            )
            assertTrue(ids.contains(WizardFlow.PAGE_PERM_POST_NOTIFICATIONS), "forward=$forward")
        }
    }

    // --- skippable フラグ ---

    /** 逆方向チャネル・ntfy 省電力・受信テスト・自動起動だけが skippable で、必須の権限・編集ページは skippable でない。 */
    @Test
    fun skippableFlagsMatchSpec() {
        val send = WizardFlow.pages(
            androidCaps,
            paired(PerantaConfig(smsDirectReceive = true)),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
        assertTrue(send.first { it.id == WizardFlow.PAGE_REVERSE_CHANNEL }.skippable)
        assertFalse(send.first { it.id == WizardFlow.PAGE_PERM_NLS }.skippable)

        val receive = WizardFlow.pages(
            androidCaps,
            paired(),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
        )
        assertTrue(receive.first { it.id == ReceiveSetupSteps.NTFY_BATTERY_ID }.skippable)
        assertTrue(receive.first { it.id == ReceiveSetupSteps.SELF_TEST_ID }.skippable)
        assertFalse(receive.first { it.id == ReceiveSetupSteps.UNIFIED_PUSH_ID }.skippable)

        val desktop = WizardFlow.pages(desktopCaps, PerantaConfig(), WizardAnswers(source = WizardSourceChoice.BE_SOURCE))
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
        val pages = WizardFlow.pages(androidCaps, config, answers)
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
        val pages = WizardFlow.pages(androidCaps, config, answers)
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
        val pages = WizardFlow.pages(androidCaps, config, answers)
        assertEquals(
            WizardFlow.PAGE_ROLE,
            WizardFlow.firstIncompletePage(pages, config, answers, emptyList())?.id,
        )
    }

    /** Desktop で取得済み・端末名未設定なら、最初の未完了は端末名ページ（初回判定を hasSharedKey に寄せた退行防止）。 */
    @Test
    fun firstIncompleteIsDeviceNameOnDesktopWhenPairedWithoutDeviceName() {
        val config = PerantaConfig(sharedKeyBase64 = Base64.encode(ByteArray(32)), keyId = "1")
        val answers = WizardAnswers(source = WizardSourceChoice.JOIN)
        val pages = WizardFlow.pages(desktopCaps, config, answers)
        assertEquals(
            WizardFlow.PAGE_DEVICE_NAME,
            WizardFlow.firstIncompletePage(pages, config, answers, emptyList())?.id,
        )
    }

    // --- §2.3 被覆検証（caps 直積） ---

    /**
     * ウィザードの全ページ itemIds が、その config で健康診断が出す全項目 id を覆う。
     * かつ、skippable でないページの完走だけで残り得る未達（✗）は、skippable ページ上の項目に限られる
     * （＝必須ページを完走すれば skippable を飛ばした分以外に✗が残らない構造）。
     */
    @Test
    fun wizardPagesCoverAllHealthItemsAndNonSkippableCompletionLeavesOnlySkippable() {
        data class Scenario(val caps: PlatformCapabilities, val config: PerantaConfig, val answers: WizardAnswers)
        val scenarios = listOf(
            Scenario(
                androidCaps,
                paired(PerantaConfig(sendEnabled = true, smsDirectReceive = true)),
                WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
            ),
            Scenario(
                androidCaps,
                paired(PerantaConfig(sendEnabled = false)),
                WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
            ),
            Scenario(
                androidCaps,
                paired(PerantaConfig(sendEnabled = true, smsDirectReceive = true)),
                WizardAnswers(source = WizardSourceChoice.BE_SOURCE, forward = true),
            ),
        )
        scenarios.forEach { (caps, config, answers) ->
            val pages = WizardFlow.pages(caps, config, answers)
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

    /** Desktop の自動起動（診断 id: autostart）は自動起動ページ（skippable）が覆う。 */
    @Test
    fun desktopAutostartIsCoveredBySkippablePage() {
        val autostartPage = WizardFlow.pages(desktopCaps, PerantaConfig(), WizardAnswers(source = WizardSourceChoice.JOIN))
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
    private fun assertWalkReachesDone(caps: PlatformCapabilities, config0: PerantaConfig, answers: WizardAnswers) {
        val pages = WizardFlow.pages(caps, config0, answers)
        var config = config0
        val doneIds = mutableSetOf<String>()
        pages.dropLast(1).forEach { page ->
            config = applyPageEffect(page.id, config)
            if (page.itemIds.isNotEmpty()) doneIds.addAll(page.itemIds)
            val items = doneIds.map { item(it, SetupStatus.DONE) }
            assertTrue(
                WizardFlow.isPageComplete(page, config, answers, items),
                "ページ ${page.id} が、そこまでの状態で完了できない (caps=$caps, source=${answers.source}, forward=${answers.forward})",
            )
        }
        val finalItems = doneIds.map { item(it, SetupStatus.DONE) }
        assertEquals(
            WizardFlow.PAGE_DONE,
            WizardFlow.firstIncompletePage(pages, config, answers, finalItems)?.id,
            "全ページ完了後の着地点が完了ページでない (caps=$caps, forward=${answers.forward})",
        )
    }

    @Test
    fun desktopBeSourceFlowWalksToDone() {
        assertWalkReachesDone(desktopCaps, PerantaConfig(), WizardAnswers(source = WizardSourceChoice.BE_SOURCE))
    }

    @Test
    fun desktopJoinFlowWalksToDone() {
        assertWalkReachesDone(desktopCaps, PerantaConfig(), WizardAnswers(source = WizardSourceChoice.JOIN))
    }

    @Test
    fun androidJoinForwardOnFlowWalksToDone() {
        assertWalkReachesDone(
            androidCaps,
            PerantaConfig(smsDirectReceive = true),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = true),
        )
    }

    @Test
    fun androidJoinForwardOffFlowWalksToDone() {
        assertWalkReachesDone(
            androidCaps,
            PerantaConfig(),
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
        )
    }

    @Test
    fun androidBeSourceForwardOnFlowWalksToDone() {
        assertWalkReachesDone(
            androidCaps,
            PerantaConfig(smsDirectReceive = true),
            WizardAnswers(source = WizardSourceChoice.BE_SOURCE, forward = true),
        )
    }

    @Test
    fun androidBeSourceForwardOffFlowWalksToDone() {
        assertWalkReachesDone(
            androidCaps,
            PerantaConfig(),
            WizardAnswers(source = WizardSourceChoice.BE_SOURCE, forward = false),
        )
    }
}
