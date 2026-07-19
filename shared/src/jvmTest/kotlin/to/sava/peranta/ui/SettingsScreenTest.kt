package to.sava.peranta.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.pairing.SettingsController
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {

    // --- フラットモード（初期設定完了済み）の挙動 ---

    /** 保存ボタン押下で入力値が ConfigRepository に反映される。 */
    @Test
    fun saveButtonPersistsInputToRepository() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-2")
        onNodeWithTag(TAG_SAVE).performClick()

        val loaded = repo.load()
        assertEquals("example.test", loaded.host)
        assertEquals("tk", loaded.accessToken)
        assertEquals("desktop-2", loaded.deviceName)
    }

    /** 既存鍵があると「鍵を作る」で警告ダイアログが出て、確認後に鍵が作り直される。 */
    @Test
    fun rotateWithExistingKeyShowsWarningThenReplaces() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_ROTATE).performClick()
        onNodeWithText("鍵を作り直しますか？").assertIsDisplayed()

        onNodeWithTag(TAG_ROTATE_CONFIRM).performClick()

        assertEquals("2", repo.load().keyId)
    }

    /** 設定が揃った状態で「新しい端末を追加」を押すと QR スロットに URI が渡って表示される。 */
    @Test
    fun addDeviceRendersQrSlotWithPairingUri() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent {
            SettingsScreen(
                controller = controller,
                qrContent = { uri -> Text(text = uri, modifier = Modifier.testTag(QR_SLOT_TAG)) },
            )
        }

        onNodeWithTag(TAG_ADD_DEVICE).performClick()
        onNodeWithTag(QR_SLOT_TAG).assertIsDisplayed()
    }

    /** 保存済み設定が TLS 無効でも、保存時は常に TLS 有効を書き込む。 */
    @Test
    fun saveAlwaysPersistsTlsEnabled() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig().copy(useTls = false))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-2")
        onNodeWithTag(TAG_SAVE).performClick()

        assertEquals(true, repo.load().useTls)
    }

    /** センシティブ履歴保存・全文添付のチェックボックスは既定値どおりに初期表示される（§11）。 */
    @Test
    fun sensitiveHistoryAndFullTextCheckboxesShowDefaults() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).assertIsOff()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).assertIsOn()
    }

    /** 保存済み設定の値がチェックボックスの初期状態に反映される。 */
    @Test
    fun sensitiveHistoryAndFullTextCheckboxesReflectSavedConfig() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig(persistSensitiveHistory = true, attachFullTextWhenTruncated = false))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).assertIsOn()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).assertIsOff()
    }

    /** チェックボックスをトグルして保存すると、値が ConfigRepository に反映される。 */
    @Test
    fun togglingSensitiveHistoryAndFullTextCheckboxesPersistsOnSave() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).performClick()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).performClick()
        onNodeWithTag(TAG_SAVE).performClick()

        val loaded = repo.load()
        assertEquals(true, loaded.persistSensitiveHistory)
        assertEquals(false, loaded.attachFullTextWhenTruncated)
    }

    /** チェックボックスのラベル文字列をクリックしてもオンオフが切り替わる。 */
    @Test
    fun clickingCheckboxLabelTogglesCheckbox() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).assertIsOff()
        onNodeWithText("センシティブな通知の本文を履歴に保存する").performClick()
        onNodeWithTag(TAG_PERSIST_SENSITIVE).assertIsOn()

        onNodeWithTag(TAG_ATTACH_FULL_TEXT).assertIsOn()
        onNodeWithText("長文本文の全文をシームレスに添付・展開する").performClick()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).assertIsOff()
    }

    /** scrollbarContent スロットに現在のスクロール状態が渡され、描画される（Desktop 用スクロールバーの注入経路）。 */
    @Test
    fun scrollbarContentSlotIsInvoked() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))

        setContent {
            SettingsScreen(
                controller = controller,
                scrollbarContent = { Text(text = "scrollbar", modifier = Modifier.testTag(SCROLLBAR_SLOT_TAG)) },
            )
        }

        onNodeWithTag(SCROLLBAR_SLOT_TAG).assertIsDisplayed()
    }

    /** onCopyPairingUri が未指定なら、QR 表示中でもコピーボタンは出ない。 */
    @Test
    fun copyButtonHiddenWhenOnCopyPairingUriNotProvided() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_ADD_DEVICE).performClick()

        onNodeWithTag(TAG_COPY_PAIRING_URI).assertDoesNotExist()
    }

    /** onCopyPairingUri が指定されていれば QR 表示中にコピーボタンが出て、押すとペアリング文字列がコールバックへ渡り完了メッセージが出る。 */
    @Test
    fun copyButtonInvokesCallbackWithPairingUri() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var copiedUri: String? = null

        setContent {
            SettingsScreen(
                controller = controller,
                onCopyPairingUri = { uri -> copiedUri = uri },
            )
        }

        onNodeWithTag(TAG_ADD_DEVICE).performClick()
        onNodeWithTag(TAG_COPY_PAIRING_URI).performClick()

        assertEquals(controller.buildPairingUri(), copiedUri)
        onNodeWithText("ペアリング文字列をコピーしました。").assertExists()
    }

    /** フラットモードの保存・鍵ローテーションで onSaved コールバックが呼ばれる。 */
    @Test
    fun onSavedInvokedOnFlatSaveAndRotate() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var savedCount = 0

        setContent { SettingsScreen(controller, onSaved = { savedCount++ }) }

        onNodeWithTag(TAG_SAVE).performClick()
        assertEquals(1, savedCount)

        onNodeWithTag(TAG_ROTATE).performClick()
        onNodeWithTag(TAG_ROTATE_CONFIRM).performClick()
        assertEquals(2, savedCount)
    }

    /** showSendRoleOptions=true のフラット保存でも、両保存の後に onSaved は1回だけ呼ばれる。 */
    @Test
    fun flatSaveInvokesOnSavedOnceWithSendRoleOptions() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var savedCount = 0

        setContent {
            SettingsScreen(controller, showSendRoleOptions = true, onSaved = { savedCount++ })
        }

        onNodeWithTag(TAG_SAVE).performClick()
        assertEquals(1, savedCount)
    }

    // --- 送信ロールトグル（showSendRoleOptions） ---

    /** showSendRoleOptions が既定（false）なら送信ロールのトグルは表示されない。 */
    @Test
    fun sendRoleOptionsHiddenByDefault() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_SEND_ENABLED).assertDoesNotExist()
        onNodeWithTag(TAG_SMS_DIRECT_RECEIVE).assertDoesNotExist()
    }

    /** showSendRoleOptions=true なら送信ロールのトグルが出て、保存時に sendEnabled/smsDirectReceive へ反映される。 */
    @Test
    fun sendRoleOptionsShownAndPersistedWhenEnabled() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig(sendEnabled = false, smsDirectReceive = true))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller, showSendRoleOptions = true) }

        onNodeWithTag(TAG_SEND_ENABLED).assertIsOff()
        onNodeWithTag(TAG_SMS_DIRECT_RECEIVE).assertIsOn()

        onNodeWithTag(TAG_SEND_ENABLED).performClick()
        onNodeWithTag(TAG_SMS_DIRECT_RECEIVE).performClick()
        onNodeWithTag(TAG_SAVE).performClick()

        val loaded = repo.load()
        assertEquals(true, loaded.sendEnabled)
        assertEquals(false, loaded.smsDirectReceive)
    }

    // --- ウィザードモード（初期設定未完了） ---

    /** 空設定から CONNECTION→DEVICE→KEY→PAIRING を順に進め、各「次へ」で該当項目が保存される。 */
    @Test
    fun wizardWalksThroughSenderStepsPersistingEachStep() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)

        setContent {
            SettingsScreen(
                controller = controller,
                qrContent = { uri -> Text(text = uri, modifier = Modifier.testTag(QR_SLOT_TAG)) },
            )
        }

        // CONNECTION: host/token を入力して「次へ」
        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        assertEquals("example.test", repo.load().host)
        assertEquals("tk", repo.load().accessToken)

        // DEVICE: 端末名を入力して「次へ」
        onNodeWithTag(TAG_DEVICE_NAME).assertIsDisplayed()
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-1")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        assertEquals("desktop-1", repo.load().deviceName)

        // KEY: 鍵を作る
        onNodeWithTag(TAG_ROTATE).assertIsDisplayed()
        onNodeWithTag(TAG_ROTATE).performClick()
        assertNotNull(repo.load().sharedKeyBase64)

        // PAIRING: QR を表示 → 完了でフラットモードへ自動遷移
        onNodeWithTag(TAG_ADD_DEVICE).assertIsDisplayed()
        onNodeWithTag(TAG_ADD_DEVICE).performClick()
        onNodeWithTag(QR_SLOT_TAG).assertIsDisplayed()
        onNodeWithTag(TAG_SAVE).assertIsDisplayed()
        onNodeWithTag(TAG_DEVICE_NAME).assertIsDisplayed()
    }

    /** 鍵未設定のまま KEY ステップに入ると「鍵を作る」で警告なしに鍵が作られ keyId が確定する。 */
    @Test
    fun rotateWithoutExistingKeyCreatesKeyDirectly() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(host = "h", accessToken = "tk", deviceName = "d"))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_ROTATE).performClick()

        val loaded = repo.load()
        assertNotNull(loaded.sharedKeyBase64)
        assertEquals("1", loaded.keyId)
    }

    /** ウィザードの「戻る」で前のステップに戻れる。 */
    @Test
    fun wizardBackButtonReturnsToPreviousStep() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()

        // DEVICE ステップに居る
        onNodeWithTag(TAG_DEVICE_NAME).assertIsDisplayed()

        // 戻ると CONNECTION ステップ（host 入力欄）に戻る
        onNodeWithTag(TAG_WIZARD_BACK).performClick()
        onNodeWithTag(TAG_HOST).assertIsDisplayed()
    }

    /** ウィザードの CONNECTION/DEVICE の「次へ」と KEY の「鍵を作る」で onSaved が呼ばれる。 */
    @Test
    fun wizardStepActionsInvokeOnSaved() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)
        var savedCount = 0

        setContent { SettingsScreen(controller, onSaved = { savedCount++ }) }

        // CONNECTION の「次へ」
        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        assertEquals(1, savedCount)

        // DEVICE の「次へ」
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-1")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        assertEquals(2, savedCount)

        // KEY の「鍵を作る」
        onNodeWithTag(TAG_ROTATE).performClick()
        assertEquals(3, savedCount)
    }

    /** ウィザードの PAIRING で「QR を表示する」を押しても onSaved は呼ばれない。 */
    @Test
    fun wizardShowingPairingQrDoesNotInvokeOnSaved() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)
        var savedCount = 0

        setContent {
            SettingsScreen(
                controller = controller,
                qrContent = { uri -> Text(text = uri, modifier = Modifier.testTag(QR_SLOT_TAG)) },
                onSaved = { savedCount++ },
            )
        }

        // PAIRING ステップまで進める
        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-1")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag(TAG_ROTATE).performClick()

        // QR 表示は保存契機ではない
        onNodeWithTag(TAG_ADD_DEVICE).assertIsDisplayed()
        val before = savedCount
        onNodeWithTag(TAG_ADD_DEVICE).performClick()
        onNodeWithTag(QR_SLOT_TAG).assertIsDisplayed()
        assertEquals(before, savedCount)
    }

    /** 既存鍵があるとき KEY ステップに「次へ」が出て、鍵を作り直さず PAIRING へ進める。 */
    @Test
    fun wizardKeyStepProceedsWithoutRotatingWhenKeyExists() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)

        setContent {
            SettingsScreen(
                controller = controller,
                qrContent = { uri -> Text(text = uri, modifier = Modifier.testTag(QR_SLOT_TAG)) },
            )
        }

        // KEY で鍵を作り PAIRING へ
        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-1")
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag(TAG_ROTATE).performClick()
        onNodeWithTag(TAG_ADD_DEVICE).assertIsDisplayed()

        // PAIRING から「戻る」で KEY ステップへ
        onNodeWithTag(TAG_WIZARD_BACK).performClick()
        onNodeWithTag(TAG_ROTATE).assertIsDisplayed()

        // 既存鍵があるので「次へ」で作り直さず PAIRING へ進む
        val keyIdBefore = repo.load().keyId
        onNodeWithTag(TAG_WIZARD_NEXT).performClick()
        onNodeWithTag(TAG_ADD_DEVICE).assertIsDisplayed()
        assertEquals(keyIdBefore, repo.load().keyId)
    }

    private companion object {
        const val QR_SLOT_TAG = "qr-slot"
        const val SCROLLBAR_SLOT_TAG = "scrollbar-slot"

        /** isReadyForUnifiedPushReceive を満たす（＝isSetupComplete が true になる）設定。フラットモードを直接表示させる。 */
        fun readyConfig(
            persistSensitiveHistory: Boolean = false,
            attachFullTextWhenTruncated: Boolean = true,
            sendEnabled: Boolean = false,
            smsDirectReceive: Boolean = true,
        ): PerantaConfig = PerantaConfig(
            host = "peranta.sava.to",
            accessToken = "tk",
            deviceName = "desktop-1",
            sharedKeyBase64 = Base64.encode(ByteArray(32)),
            keyId = "1",
            persistSensitiveHistory = persistSensitiveHistory,
            attachFullTextWhenTruncated = attachFullTextWhenTruncated,
            sendEnabled = sendEnabled,
            smsDirectReceive = smsDirectReceive,
        )
    }
}
