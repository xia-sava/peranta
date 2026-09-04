package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.pairing.PairingData
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.pairing.PairingUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PairingScanScreenTest {

    private fun validUri(): String =
        PairingUri.encode(
            PairingData(
                host = "peranta.example.com",
                token = "tk",
                keyId = "k2",
                key = ByteArray(32) { it.toByte() },
            ),
        )

    /** 手動貼り付け欄に URI を入れて「取り込む」を押すと設定へ適用され、成功文言が出る。 */
    @Test
    fun manualPasteImportsPairingUri() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).performTextReplacement(validUri())
        onNodeWithTag(TAG_PAIRING_IMPORT).performClick()

        onNodeWithTag(TAG_PAIRING_STATUS).assertIsDisplayed()
        assertEquals("k2", repo.load().keyId)
    }

    /** 不正な文字列の取り込みは設定を変えず、失敗理由を表示する。 */
    @Test
    fun invalidManualInputShowsErrorWithoutApplying() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).performTextReplacement("nonsense")
        onNodeWithTag(TAG_PAIRING_IMPORT).performClick()

        onNodeWithTag(TAG_PAIRING_STATUS).assertIsDisplayed()
        assertNull(repo.load().sharedKeyBase64)
    }

    /** スキャンスロットが返した生文字列は手動貼り付けと同じ経路で取り込まれる。 */
    @Test
    fun injectedScanResultIsImported() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent {
            PairingScanScreen(
                controller = PairingImportController(repo),
                onRequestScan = { onResult -> onResult(validUri()) },
            )
        }

        onNodeWithTag(TAG_PAIRING_SCAN).performClick()

        onNodeWithTag(TAG_PAIRING_STATUS).assertIsDisplayed()
        assertEquals("k2", repo.load().keyId)
    }

    /** スキャンがキャンセル（null）されても設定は変わらず、状態表示も出ない。 */
    @Test
    fun cancelledScanDoesNothing() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent {
            PairingScanScreen(
                controller = PairingImportController(repo),
                onRequestScan = { onResult -> onResult(null) },
            )
        }

        onNodeWithTag(TAG_PAIRING_SCAN).performClick()

        onAllNodesWithTag(TAG_PAIRING_STATUS).assertCountEquals(0)
        assertNull(repo.load().sharedKeyBase64)
    }

    /** スキャンスロット未注入（カメラ非対応）ならスキャンボタンを出さない。 */
    @Test
    fun scanButtonHiddenWhenNoScannerInjected() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onAllNodesWithTag(TAG_PAIRING_SCAN).assertCountEquals(0)
        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).assertIsDisplayed()
    }

    /** 端末名を入力して取り込むと、その端末名が設定へ適用される。 */
    @Test
    fun deviceNameInputIsAppliedOnImport() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onNodeWithTag(TAG_PAIRING_DEVICE_NAME).performTextReplacement("居間のPC")
        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).performTextReplacement(validUri())
        onNodeWithTag(TAG_PAIRING_IMPORT).performClick()

        assertEquals("居間のPC", repo.load().deviceName)
        onNodeWithTag(TAG_PAIRING_STATUS).assert(
            hasText("端末名が未設定", substring = true).not(),
        )
    }

    /** 端末名を空白のまま取り込むと既存の端末名を引き継ぎ、未設定の警告は出さない。 */
    @Test
    fun blankDeviceNameKeepsExistingWithoutWarning() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(deviceName = "既存端末名"))

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).performTextReplacement(validUri())
        onNodeWithTag(TAG_PAIRING_IMPORT).performClick()

        assertEquals("既存端末名", repo.load().deviceName)
        onNodeWithTag(TAG_PAIRING_STATUS).assert(
            hasText("端末名が未設定", substring = true).not(),
        )
    }

    /** 端末名を持たない端末が空白のまま取り込むと、状態表示に未設定の警告が出る。 */
    @Test
    fun blankDeviceNameWithoutExistingWarns() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).performTextReplacement(validUri())
        onNodeWithTag(TAG_PAIRING_IMPORT).performClick()

        assertNull(repo.load().deviceName)
        onNodeWithTag(TAG_PAIRING_STATUS).assertTextContains(
            "端末名が未設定です。後で設定画面から入力してください。",
            substring = true,
        )
    }

    /** 設定画面への導線スロット未注入なら「設定元にする」ボタンを出さない。 */
    @Test
    fun openSettingsButtonHiddenWhenNoSlotInjected() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onAllNodesWithTag(TAG_PAIRING_OPEN_SETTINGS).assertCountEquals(0)
    }

    /** 設定画面への導線スロットを注入するとボタンが出て、クリックでコールバックが呼ばれる。 */
    @Test
    fun openSettingsButtonInvokesCallback() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        var opened = false

        setContent {
            PairingScanScreen(
                controller = PairingImportController(repo),
                onOpenSettings = { opened = true },
            )
        }

        onNodeWithTag(TAG_PAIRING_OPEN_SETTINGS).performClick()

        assertTrue(opened)
    }

    /** ウィザードへの導線スロット未注入なら「ウィザードで設定する」ボタンを出さない。 */
    @Test
    fun openWizardButtonHiddenWhenNoSlotInjected() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent { PairingScanScreen(PairingImportController(repo)) }

        onAllNodesWithTag(TAG_PAIRING_OPEN_WIZARD).assertCountEquals(0)
    }

    /** ウィザードへの導線スロットを注入するとボタンが出て、クリックでコールバックが呼ばれる。 */
    @Test
    fun openWizardButtonInvokesCallback() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        var opened = false

        setContent {
            PairingScanScreen(
                controller = PairingImportController(repo),
                onOpenWizard = { opened = true },
            )
        }

        onNodeWithTag(TAG_PAIRING_OPEN_WIZARD).performClick()

        assertTrue(opened)
    }

    /** 取り込み成功後は「ウィザードで設定する」導線を隠す（受信側になった後は意味を持たないため）。 */
    @Test
    fun openWizardButtonHidesAfterSuccessfulImport() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())

        setContent {
            PairingScanScreen(
                controller = PairingImportController(repo),
                onOpenWizard = { },
            )
        }

        onNodeWithTag(TAG_PAIRING_OPEN_WIZARD).assertIsDisplayed()

        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).performTextReplacement(validUri())
        onNodeWithTag(TAG_PAIRING_IMPORT).performClick()

        onAllNodesWithTag(TAG_PAIRING_OPEN_WIZARD).assertCountEquals(0)
    }

    /**
     * 取り込み成功後は「タイムラインへ」導線を出し、クリックでコールバックを呼ぶ。
     * あわせて「設定元にする」導線は取り込み成功後に隠す（受信側になった後は意味を持たないため）。
     */
    @Test
    fun importedSlotAppearsOnlyAfterSuccessAndHidesOpenSettings() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        var imported = false

        setContent {
            PairingScanScreen(
                controller = PairingImportController(repo),
                onOpenSettings = { },
                onImported = { imported = true },
            )
        }

        onAllNodesWithTag(TAG_PAIRING_IMPORTED).assertCountEquals(0)
        onNodeWithTag(TAG_PAIRING_OPEN_SETTINGS).assertIsDisplayed()

        onNodeWithTag(TAG_PAIRING_MANUAL_INPUT).performTextReplacement(validUri())
        onNodeWithTag(TAG_PAIRING_IMPORT).performClick()

        onAllNodesWithTag(TAG_PAIRING_OPEN_SETTINGS).assertCountEquals(0)
        onNodeWithTag(TAG_PAIRING_IMPORTED).performClick()

        assertTrue(imported)
    }
}
