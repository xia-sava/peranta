package to.sava.peranta.ui.setup

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.ui.HealthCheckItem
import to.sava.peranta.ui.HealthChecker
import to.sava.peranta.ui.TAG_PAIRING_MANUAL_INPUT
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WizardScreenTest {

    private class FakeProvider(private val result: List<SetupItemUi> = emptyList()) : SetupItemsProvider {
        override suspend fun items(): List<SetupItemUi> = result
    }

    private val emptyHealthChecker = HealthChecker { emptyList<HealthCheckItem>() }

    private fun autostartItem(status: SetupStatus): SetupItemUi =
        SetupItemUi(
            id = WizardFlow.ITEM_AUTOSTART,
            title = "ログオン時の自動起動",
            description = null,
            status = status,
            statusDetail = null,
            action = SetupAction(label = "登録する", run = {}),
        )

    /** hasSharedKey・端末名まで揃った設定。Android の再入で役割ページから始まる。 */
    private fun pairedConfig(): PerantaConfig = PerantaConfig(
        host = "peranta.sava.to",
        accessToken = "tk",
        deviceName = "phone-1",
        sharedKeyBase64 = Base64.encode(ByteArray(32)),
        keyId = "1",
    )

    /** isReadyForSend まで揃った設定。Desktop の再入で自動起動ページから始まる。 */
    private fun readyForSendConfig(): PerantaConfig = pairedConfig().copy(controlTopic = "control-topic")

    // --- ページ遷移（次へ / 戻る） ---

    /** Desktop の接続ページで入力して「次へ」で端末名ページへ進み、「戻る」で接続ページへ戻る。 */
    @Test
    fun desktopNextAndBackNavigateBetweenPages() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))
        setContent {
            WizardScreen(
                role = WizardRole.DESKTOP_SOURCE,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
            )
        }

        onNodeWithTag("wizard-settings-token").performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag("wizard-settings-deviceName").assertIsDisplayed()

        onNodeWithTag(TAG_WIZARD_BACK).performClick()
        onNodeWithTag("wizard-settings-host").assertIsDisplayed()
    }

    /** 完了条件を満たすまで「次へ」は無効で、条件を満たすと有効になる。 */
    @Test
    fun nextDisabledUntilPageComplete() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))
        setContent {
            WizardScreen(
                role = WizardRole.DESKTOP_SOURCE,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
            )
        }

        onNodeWithTag(TAG_WIZARD_NEXT).assertIsNotEnabled()
        onNodeWithTag("wizard-settings-token").performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).assertIsEnabled()
    }

    // --- 選択ページの即時保存 ---

    /** 自動転送 2 択の「自動転送する」で sendEnabled=true、「転送しない」で sendEnabled=false が即座に保存される。 */
    @Test
    fun choosingForwardPersistsSendEnabledImmediately() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(pairedConfig())
        val controller = SettingsController(repo)
        setContent {
            WizardScreen(
                role = WizardRole.ANDROID,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
            )
        }

        onNodeWithTag(TAG_WIZARD_ROLE_SEND).performClick()
        assertEquals(true, repo.load().sendEnabled)

        onNodeWithTag(TAG_WIZARD_ROLE_RECEIVE).performClick()
        assertEquals(false, repo.load().sendEnabled)
    }

    /**
     * A2a（QR 取り込み）ページを実際に合成しても落ちない（外側スクロール内へ埋め込む中身が
     * 二重スクロールにならないことの回帰）。JOIN を選んで次へ進み、取り込み欄が表示される。
     */
    @Test
    fun qrImportPageComposesWithoutNestedScrollCrash() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)
        setContent {
            WizardScreen(
                role = WizardRole.ANDROID,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
                importController = PairingImportController(repo),
                onRequestScan = { onResult -> onResult(null) },
            )
        }

        onNodeWithTag(TAG_WIZARD_SOURCE_JOIN).performClick()
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).assertIsDisplayed()
    }

    /** A1 で「設定元にする」を選ぶと分岐が切り替わり、「次へ」で接続入力ページに入る。 */
    @Test
    fun choosingBeSourceBranchesToConnectionPage() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))
        setContent {
            WizardScreen(
                role = WizardRole.ANDROID,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
            )
        }

        onNodeWithTag(TAG_WIZARD_SOURCE_BE).performClick()
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag("wizard-settings-host").assertIsDisplayed()
    }

    // --- 再入時 firstIncompletePage ---

    /** 取得済みで自動転送が未回答なら、再入時は自動転送ページ（2 択）から始まる。 */
    @Test
    fun reentryStartsAtForwardPageWhenPairedButForwardUnanswered() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(pairedConfig())
        val controller = SettingsController(repo)
        setContent {
            WizardScreen(
                role = WizardRole.ANDROID,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
            )
        }

        onNodeWithTag(TAG_WIZARD_ROLE_SEND).assertIsDisplayed()
        onNodeWithTag(TAG_WIZARD_ROLE_RECEIVE).assertIsDisplayed()
    }

    // --- skippable ---

    /** skippable ページは「あとで設定する」で機能説明つきの確認を挟み、確認で完了ページへ進む。 */
    @Test
    fun skippablePageConfirmsWithFunctionalWordingThenAdvances() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyForSendConfig())
        val controller = SettingsController(repo)
        setContent {
            WizardScreen(
                role = WizardRole.DESKTOP_SOURCE,
                controller = controller,
                provider = FakeProvider(listOf(autostartItem(SetupStatus.TODO))),
                healthChecker = emptyHealthChecker,
                onClose = {},
            )
        }

        // 自動起動ページ（D5, skippable）に着地している。
        onNodeWithTag(TAG_WIZARD_SKIP).assertIsDisplayed()
        onNodeWithTag(TAG_WIZARD_SKIP).performClick()

        // スキップ確認は「何ができなくなるか」を機能で説明する（内部状態ベースの説明はしない）。
        onNodeWithText("この設定を飛ばすと、サインイン後すぐには受信を始められないことがあります。").assertIsDisplayed()

        onNodeWithTag(TAG_WIZARD_SKIP_CONFIRM).performClick()
        onNodeWithTag(TAG_WIZARD_TIMELINE).assertIsDisplayed()
    }

    // --- onSaved 規律 ---

    /** 編集ページの入力→「次へ」（dirty 時の保存）と、鍵作成の各操作で onSaved が呼ばれる。 */
    @Test
    fun editingNextAndKeyCreationInvokeOnSaved() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))
        var savedCount = 0
        setContent {
            WizardScreen(
                role = WizardRole.DESKTOP_SOURCE,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
                onSaved = { savedCount++ },
            )
        }

        // 接続ページ: 入力して「次へ」で保存契機が 1 回。
        onNodeWithTag("wizard-settings-token").performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        assertEquals(1, savedCount)

        // 端末名ページ: 入力して「次へ」で保存契機が 1 回。
        onNodeWithTag("wizard-settings-deviceName").performTextReplacement("desktop-1")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        assertEquals(2, savedCount)

        // 共有鍵ページ: 「鍵を作る」で保存契機が 1 回。
        onNodeWithTag(TAG_WIZARD_ROTATE).performClick()
        assertEquals(3, savedCount)
    }

    /** ページを表示しただけ（編集・操作なし）では onSaved は呼ばれない。 */
    @Test
    fun displayingPageWithoutActionDoesNotInvokeOnSaved() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))
        var savedCount = 0
        setContent {
            WizardScreen(
                role = WizardRole.DESKTOP_SOURCE,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
                onSaved = { savedCount++ },
            )
        }

        onNodeWithTag("wizard-settings-host").assertIsDisplayed()
        assertEquals(0, savedCount)
    }

    // --- 閉じる ---

    /** ヘッダの「閉じる」で onClose が呼ばれる。 */
    @Test
    fun closeInvokesOnClose() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))
        var closed = false
        setContent {
            WizardScreen(
                role = WizardRole.DESKTOP_SOURCE,
                controller = controller,
                provider = FakeProvider(),
                healthChecker = emptyHealthChecker,
                onClose = { closed = true },
            )
        }

        onNodeWithTag(TAG_WIZARD_CLOSE).performClick()
        assertTrue(closed)
    }
}
