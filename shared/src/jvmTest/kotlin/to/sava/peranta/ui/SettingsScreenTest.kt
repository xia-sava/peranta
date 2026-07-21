package to.sava.peranta.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.update.PLATFORM_DESKTOP
import to.sava.peranta.update.UpdateChecker
import to.sava.peranta.update.UpdateController
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {

    // --- 入力の即時保存 ---

    /** テキスト欄への入力がそのまま ConfigRepository に即時反映される（保存ボタンなしの自動保存）。 */
    @Test
    fun editingTextFieldsPersistsImmediately() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-2")

        val loaded = repo.load()
        assertEquals("example.test", loaded.host)
        assertEquals("tk", loaded.accessToken)
        assertEquals("desktop-2", loaded.deviceName)
    }

    /** 保存済み設定が TLS 無効でも、自動保存時は常に TLS 有効を書き込む。 */
    @Test
    fun autoSaveAlwaysPersistsTlsEnabled() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig().copy(useTls = false))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-2")

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

    /** チェックボックスをトグルすると、即座に値が ConfigRepository に反映される。 */
    @Test
    fun togglingSensitiveHistoryAndFullTextCheckboxesPersistsImmediately() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).performClick()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).performClick()

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

    /** フラット画面の見出し直下に自動保存の説明文が表示される。 */
    @Test
    fun autosaveNoteIsDisplayed() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_AUTOSAVE_NOTE).assertIsDisplayed()
    }

    // --- ウィザード導線 ---

    /** onOpenWizard が指定されていれば「ウィザードで設定する」導線が出て、押すとコールバックが呼ばれる。 */
    @Test
    fun openWizardButtonInvokesCallbackWhenProvided() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var wizardOpened = false

        setContent { SettingsScreen(controller, onOpenWizard = { wizardOpened = true }) }

        onNodeWithTag(TAG_OPEN_WIZARD).performClick()
        assertEquals(true, wizardOpened)
    }

    /** onOpenWizard が未指定（既定）なら「ウィザードで設定する」導線は出ない。 */
    @Test
    fun openWizardButtonHiddenByDefault() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_OPEN_WIZARD).assertDoesNotExist()
    }

    // --- 端末の追加（鍵あり） ---

    /** 鍵が設定済みなら「端末追加用のQRを表示」が出て、押すと QR スロットに URI が渡って表示される。 */
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

        onNodeWithTag(TAG_CREATE_KEY).assertDoesNotExist()
        onNodeWithTag(TAG_ADD_DEVICE).performScrollTo().performClick()
        onNodeWithTag(QR_SLOT_TAG).performScrollTo().assertIsDisplayed()
    }

    /** onCopyPairingUri が未指定なら、QR 表示中でもコピーボタンは出ない。 */
    @Test
    fun copyButtonHiddenWhenOnCopyPairingUriNotProvided() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_ADD_DEVICE).performScrollTo().performClick()

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

        onNodeWithTag(TAG_ADD_DEVICE).performScrollTo().performClick()
        onNodeWithTag(TAG_COPY_PAIRING_URI).performScrollTo().performClick()

        assertEquals(controller.buildPairingUri(), copiedUri)
        onNodeWithText("ペアリング文字列をコピーしました。").assertExists()
    }

    // --- 端末の追加（鍵なし）: 作成と QR 表示の一気通貫 ---

    /** 鍵未設定なら「共有鍵を作成して QR を表示」が出て、押すと警告なしに鍵が作られ同時に QR が出る。 */
    @Test
    fun createKeyButtonCreatesKeyAndShowsQrInOneStep() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(host = "h", accessToken = "tk", deviceName = "d"))
        val controller = SettingsController(repo)

        setContent {
            SettingsScreen(
                controller = controller,
                qrContent = { uri -> Text(text = uri, modifier = Modifier.testTag(QR_SLOT_TAG)) },
            )
        }

        onNodeWithTag(TAG_ADD_DEVICE).assertDoesNotExist()
        onNodeWithTag(TAG_CREATE_KEY).performScrollTo().performClick()

        val loaded = repo.load()
        assertNotNull(loaded.sharedKeyBase64)
        assertEquals("1", loaded.keyId)
        onNodeWithTag(QR_SLOT_TAG).performScrollTo().assertIsDisplayed()
    }

    /**
     * トークン未入力で「共有鍵を作成して QR を表示」を押すと、鍵は作られるが QR は作れず、
     * 鍵に触れず接続設定の不足だけを指す案内文が出る（鍵作成済みなのに「鍵を設定して」とは言わない）。
     */
    @Test
    fun createKeyWithoutTokenCreatesKeyButShowsConnectionPrerequisiteNotice() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(host = "h", deviceName = "d"))
        val controller = SettingsController(repo)

        setContent {
            SettingsScreen(
                controller = controller,
                qrContent = { uri -> Text(text = uri, modifier = Modifier.testTag(QR_SLOT_TAG)) },
            )
        }

        onNodeWithTag(TAG_CREATE_KEY).performScrollTo().performClick()

        assertNotNull(repo.load().sharedKeyBase64)
        onNodeWithTag(QR_SLOT_TAG).assertDoesNotExist()
        onNodeWithText("QR の表示には接続設定が必要です。先にサーバホスト名とアクセストークンを設定してください。")
            .assertExists()
    }

    // --- 危険な操作: 折り畳み ---

    /** 危険な操作セクションは既定で折り畳まれており、「共有鍵を作り直す」は見出しをクリックするまで出ない。 */
    @Test
    fun dangerSectionCollapsedByDefaultAndExpandsOnHeaderClick() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_ROTATE).assertDoesNotExist()

        onNodeWithTag(TAG_DANGER_TOGGLE).performScrollTo().performClick()

        onNodeWithTag(TAG_ROTATE).performScrollTo().assertIsDisplayed()
    }

    // --- 危険な操作: 共有鍵の作り直し ---

    /** 鍵未設定のときは危険な操作の見出し自体が出ない（作成は端末の追加が担う）。 */
    @Test
    fun rotateButtonHiddenWhenNoKey() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(host = "h", accessToken = "tk", deviceName = "d"))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_DANGER_TOGGLE).assertDoesNotExist()
        onNodeWithTag(TAG_ROTATE).assertDoesNotExist()
    }

    /** 既存鍵があると、危険な操作を展開して「共有鍵を作り直す」で警告ダイアログが出て、確認後に鍵が作り直され QR が自動表示され案内文が出る。 */
    @Test
    fun rotateWithExistingKeyShowsWarningThenReplacesAndShowsQr() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent {
            SettingsScreen(
                controller = controller,
                qrContent = { uri -> Text(text = uri, modifier = Modifier.testTag(QR_SLOT_TAG)) },
            )
        }

        onNodeWithTag(TAG_DANGER_TOGGLE).performScrollTo().performClick()
        onNodeWithTag(TAG_ROTATE).performScrollTo().performClick()
        onNodeWithText("鍵を作り直しますか？").assertIsDisplayed()

        onNodeWithTag(TAG_ROTATE_CONFIRM).performClick()

        assertEquals("2", repo.load().keyId)
        onNodeWithTag(QR_SLOT_TAG).performScrollTo().assertIsDisplayed()
        onNodeWithText("新しい鍵を作成しました。下の QR を各端末で読み取ってください。").assertExists()
    }

    /** 鍵の作り直しは即座に onSaved を呼ぶ。 */
    @Test
    fun onSavedInvokedOnRotate() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var savedCount = 0

        setContent { SettingsScreen(controller, onSaved = { savedCount++ }) }

        onNodeWithTag(TAG_DANGER_TOGGLE).performScrollTo().performClick()
        onNodeWithTag(TAG_ROTATE).performScrollTo().performClick()
        onNodeWithTag(TAG_ROTATE_CONFIRM).performClick()
        assertEquals(1, savedCount)
    }

    // --- 保存契機（onSaved） ---

    /** 入力欄・チェックボックスを編集しただけでは onSaved は呼ばれない。 */
    @Test
    fun editingAloneDoesNotInvokeOnSaved() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var savedCount = 0

        setContent { SettingsScreen(controller, onSaved = { savedCount++ }) }

        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-2")
        onNodeWithTag(TAG_PERSIST_SENSITIVE).performClick()

        assertEquals(0, savedCount)
    }

    /** 編集後に「タイムラインへ」を押すと onSaved が1回呼ばれてから onOpenTimeline が呼ばれる。 */
    @Test
    fun openTimelineAfterEditingInvokesOnSavedThenOpensTimeline() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var savedCount = 0
        var timelineOpened = false

        setContent {
            SettingsScreen(
                controller,
                onOpenTimeline = { timelineOpened = true },
                onSaved = { savedCount++ },
            )
        }

        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-2")
        onNodeWithText("タイムラインへ").performClick()

        assertEquals(1, savedCount)
        assertEquals(true, timelineOpened)
    }

    /** 編集せずに「タイムラインへ」を押しても onSaved は呼ばれない。 */
    @Test
    fun openTimelineWithoutEditingDoesNotInvokeOnSaved() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var savedCount = 0
        var timelineOpened = false

        setContent {
            SettingsScreen(
                controller,
                onOpenTimeline = { timelineOpened = true },
                onSaved = { savedCount++ },
            )
        }

        onNodeWithText("タイムラインへ").performClick()

        assertEquals(0, savedCount)
        assertEquals(true, timelineOpened)
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

    /** showSendRoleOptions=true なら送信ロールのトグルが出て、トグル時に即座に sendEnabled/smsDirectReceive へ反映される。 */
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

        val loaded = repo.load()
        assertEquals(true, loaded.sendEnabled)
        assertEquals(false, loaded.smsDirectReceive)
    }

    // --- アプリの更新 ---

    /** updateController 未指定なら「アプリの更新」セクションごと非表示になる。 */
    @Test
    fun updateSectionHiddenWhenControllerNotProvided() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_UPDATE_CHECK).assertDoesNotExist()
    }

    /** ボタン押下で checkNow が実行され、失敗結果はボタンの下に理由付きで表示される。 */
    @Test
    fun updateCheckButtonRunsCheckNowAndShowsFailedReason() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), repo.load(), 1, PLATFORM_DESKTOP), scope)

        try {
            setContent { SettingsScreen(settingsController, updateController = updateController) }

            onNodeWithTag(TAG_UPDATE_CHECK).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(TAG_UPDATE_STATUS).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("更新確認に失敗しました: latest.json の取得に失敗しました (HTTP 404)").assertExists()
        } finally {
            scope.cancel()
        }
    }

    /** Available のとき「更新」ボタンが出て、押すと onInstallUpdate に配布 URL が渡る。 */
    @Test
    fun updateAvailableShowsInstallButtonAndInvokesOnInstallUpdate() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manifestJson = """
            { "desktop": { "versionCode": 20, "versionName": "2.0.0", "url": "http://h/d.msi" } }
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = manifestJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), repo.load(), 1, PLATFORM_DESKTOP), scope)
        var installedUrl: String? = null

        try {
            setContent {
                SettingsScreen(
                    settingsController,
                    updateController = updateController,
                    onInstallUpdate = { url -> installedUrl = url },
                )
            }

            onNodeWithTag(TAG_UPDATE_CHECK).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(TAG_UPDATE_INSTALL).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("新しいバージョン 2.0.0").assertExists()
            onNodeWithTag(TAG_UPDATE_INSTALL).performScrollTo().performClick()

            assertEquals("http://h/d.msi", installedUrl)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val QR_SLOT_TAG = "qr-slot"
        const val SCROLLBAR_SLOT_TAG = "scrollbar-slot"

        /** 鍵まで揃った設定。 */
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
