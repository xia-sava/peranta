package to.sava.peranta.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.pairing.SettingsController
import to.sava.peranta.ui.setup.OVERVIEW_ROW_CONNECTION
import to.sava.peranta.ui.setup.OVERVIEW_ROW_FORWARD
import to.sava.peranta.ui.setup.OVERVIEW_ROW_RECEIVE
import to.sava.peranta.ui.setup.PAIRING_COPIED_MESSAGE
import to.sava.peranta.ui.setup.PAIRING_COPY_CAUTION
import to.sava.peranta.ui.setup.SMS_DIRECT_RECEIVE_DESCRIPTION
import to.sava.peranta.update.PLATFORM_DESKTOP
import to.sava.peranta.update.TestSigningKey
import to.sava.peranta.update.UpdateChecker
import to.sava.peranta.update.UpdateController
import to.sava.peranta.update.UpdateInstallState
import to.sava.peranta.update.UpdateStatus
import to.sava.peranta.update.releaseAssetUrl
import to.sava.peranta.update.signedManifestEngine
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    // --- 自動起動（§3.3） ---

    /** 自動起動が渡されないプラットフォームでは項目自体を出さない。 */
    @Test
    fun autoStartRowIsAbsentWithoutAutoStartUi() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())

        setContent { SettingsScreen(SettingsController(repo)) }

        onNodeWithTag(TAG_AUTO_START).assertDoesNotExist()
    }

    /** 自動起動のトグルは現在の登録状態を初期表示し、操作で登録・解除を呼ぶ。 */
    @Test
    fun autoStartToggleReflectsAndUpdatesRegistration() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val changes = mutableListOf<Boolean>()

        setContent {
            SettingsScreen(
                SettingsController(repo),
                autoStart = AutoStartUi(
                    isEnabled = { true },
                    editable = true,
                    onChange = { changes.add(it) },
                ),
            )
        }

        onNodeWithTag(TAG_AUTO_START).performScrollTo().assertIsOn()
        onNodeWithTag(TAG_AUTO_START).performClick()

        assertEquals(listOf(false), changes)
    }

    /** 登録できない環境（開発実行）では項目を出したまま操作を受け付けない。 */
    @Test
    fun autoStartToggleIsShownButDisabledWhenNotEditable() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val changes = mutableListOf<Boolean>()

        setContent {
            SettingsScreen(
                SettingsController(repo),
                autoStart = AutoStartUi(
                    isEnabled = { false },
                    editable = false,
                    unavailableInDevBuild = true,
                    onChange = { changes.add(it) },
                ),
            )
        }

        onNodeWithTag(TAG_AUTO_START).performScrollTo().assertExists()
        onNodeWithTag(TAG_AUTO_START).performClick()

        assertEquals(emptyList(), changes)
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

    /** 画像の自動表示チェックボックスは既定 ON で初期表示される（§4.3）。 */
    @Test
    fun autoDisplayImagesCheckboxShowsDefaultOn() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_AUTO_DISPLAY_IMAGES).performScrollTo().assertIsOn()
    }

    /** 保存済みの画像自動表示設定（OFF）がチェックボックスの初期状態に反映される。 */
    @Test
    fun autoDisplayImagesCheckboxReflectsSavedConfig() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig().copy(autoDisplayImages = false))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_AUTO_DISPLAY_IMAGES).performScrollTo().assertIsOff()
    }

    /** 画像の自動表示チェックボックスをトグルすると、即座に値が ConfigRepository に反映される。 */
    @Test
    fun togglingAutoDisplayImagesCheckboxPersistsImmediately() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_AUTO_DISPLAY_IMAGES).performScrollTo().performClick()

        assertEquals(false, repo.load().autoDisplayImages)
    }

    /** 詳細な記録チェックボックスは既定 OFF で初期表示される（§11）。 */
    @Test
    fun verboseLoggingCheckboxShowsDefaultOff() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_VERBOSE_LOGGING).performScrollTo().assertIsOff()
    }

    /** 詳細な記録チェックボックスをトグルすると、即座に値が ConfigRepository に反映される。 */
    @Test
    fun togglingVerboseLoggingCheckboxPersistsImmediately() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_VERBOSE_LOGGING).performScrollTo().performClick()

        assertEquals(true, repo.load().verboseLogging)
    }

    /** 履歴の保持日数欄は既定（未設定）では空欄で表示される（§11: 既定は無制限）。 */
    @Test
    fun timelineRetentionDaysFieldShowsEmptyByDefault() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_TIMELINE_RETENTION_DAYS).performScrollTo().assertIsDisplayed()
    }

    /** 保存済みの保持日数が欄の初期表示に反映される。 */
    @Test
    fun timelineRetentionDaysFieldReflectsSavedConfig() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig().copy(timelineRetentionDays = 30))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_TIMELINE_RETENTION_DAYS).performScrollTo().assert(hasText("30"))
    }

    /** 保持日数欄への入力は即座に ConfigRepository に反映される。 */
    @Test
    fun editingTimelineRetentionDaysFieldPersistsImmediately() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_TIMELINE_RETENTION_DAYS).performScrollTo().performTextReplacement("14")

        assertEquals(14, repo.load().timelineRetentionDays)
    }

    /** 保持日数欄は数字以外の入力を無視する（ポート欄と同じ数値専用フィールドの実装パターン）。 */
    @Test
    fun editingTimelineRetentionDaysFieldFiltersNonDigits() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_TIMELINE_RETENTION_DAYS).performScrollTo().performTextReplacement("3a0")

        assertEquals(30, repo.load().timelineRetentionDays)
    }

    /** 保持日数欄を空欄に戻すと、日数による削除を行わない設定（null）に戻る。 */
    @Test
    fun clearingTimelineRetentionDaysFieldPersistsNull() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig().copy(timelineRetentionDays = 30))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_TIMELINE_RETENTION_DAYS).performScrollTo().performTextReplacement("")

        assertEquals(null, repo.load().timelineRetentionDays)
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

    /** onOpenWizard が指定されていれば「ウィザードで最初からやり直す」導線が出て、押すとコールバックが呼ばれる。 */
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

    /** onOpenWizard が未指定（既定）なら「ウィザードで最初からやり直す」導線は出ない。 */
    @Test
    fun openWizardButtonHiddenByDefault() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_OPEN_WIZARD).assertDoesNotExist()
    }

    // --- セットアップ状況 ---

    /** loadHealthItems 未指定（既定）ならセットアップ状況セクションは出ない。 */
    @Test
    fun setupOverviewSectionHiddenByDefault() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())

        setContent { SettingsScreen(SettingsController(repo)) }

        onNodeWithTag("$TAG_OVERVIEW_STATE_PREFIX$OVERVIEW_ROW_CONNECTION").assertDoesNotExist()
    }

    /** loadHealthItems を渡すと接続とペアリング・権限と常駐の行が出る。共有鍵ありなら接続は達成表示。 */
    @Test
    fun setupOverviewShowsConnectionAndForwardRows() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())

        setContent {
            SettingsScreen(
                controller = SettingsController(repo),
                loadHealthItems = { emptyList() },
            )
        }

        onNodeWithTag("$TAG_OVERVIEW_STATE_PREFIX$OVERVIEW_ROW_CONNECTION").assertIsDisplayed()
        onNodeWithText("接続先と共有鍵設定済み").assertExists()
        onNodeWithTag("$TAG_OVERVIEW_STATE_PREFIX$OVERVIEW_ROW_FORWARD").assertExists()
        // 受信経路の行は hasReceiveSetup 既定 false のとき出ない。
        onNodeWithTag("$TAG_OVERVIEW_STATE_PREFIX$OVERVIEW_ROW_RECEIVE").assertDoesNotExist()
    }

    /** 権限と常駐の行の[開く]で onOpenHealthCheck が呼ばれる。 */
    @Test
    fun overviewForwardOpenInvokesHealthCheckCallback() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        var opened = false

        setContent {
            SettingsScreen(
                controller = SettingsController(repo),
                loadHealthItems = { emptyList() },
                onOpenHealthCheck = { opened = true },
            )
        }

        onNodeWithTag("$TAG_OVERVIEW_OPEN_PREFIX$OVERVIEW_ROW_FORWARD").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    /** 接続とペアリングの行の導線で onOpenPairingImport が呼ばれる（達成状態でも導線は常設）。 */
    @Test
    fun overviewConnectionOpenInvokesPairingImportCallback() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        var opened = false

        setContent {
            SettingsScreen(
                controller = SettingsController(repo),
                loadHealthItems = { emptyList() },
                onOpenPairingImport = { opened = true },
            )
        }

        onNodeWithTag("$TAG_OVERVIEW_OPEN_PREFIX$OVERVIEW_ROW_CONNECTION").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    /** onOpenPairingImport が未指定（既定）なら接続とペアリングの行に導線は出ない。 */
    @Test
    fun overviewConnectionOpenHiddenWhenCallbackNotProvided() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())

        setContent {
            SettingsScreen(
                controller = SettingsController(repo),
                loadHealthItems = { emptyList() },
            )
        }

        onNodeWithTag("$TAG_OVERVIEW_OPEN_PREFIX$OVERVIEW_ROW_CONNECTION").assertDoesNotExist()
    }

    /** hasReceiveSetup=true なら受信経路の行が出て、[開く]で onOpenReceiveSetup が呼ばれる。 */
    @Test
    fun overviewReceiveRowShownAndOpenInvokesCallback() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        var opened = false

        setContent {
            SettingsScreen(
                controller = SettingsController(repo),
                loadHealthItems = { emptyList() },
                hasReceiveSetup = true,
                loadReceiveSetupItems = { emptyList() },
                onOpenReceiveSetup = { opened = true },
            )
        }

        onNodeWithTag("$TAG_OVERVIEW_STATE_PREFIX$OVERVIEW_ROW_RECEIVE").assertExists()
        onNodeWithTag("$TAG_OVERVIEW_OPEN_PREFIX$OVERVIEW_ROW_RECEIVE").performScrollTo().performClick()
        assertEquals(true, opened)
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
        onNodeWithText(PAIRING_COPIED_MESSAGE).assertExists()
    }

    /** コピー導線があるときは、押す前から「秘密が入る・後始末が要る」注意が添えられている。 */
    @Test
    fun copyButtonIsAccompaniedByCaution() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent {
            SettingsScreen(
                controller = controller,
                onCopyPairingUri = {},
            )
        }

        onNodeWithTag(TAG_ADD_DEVICE).performScrollTo().performClick()

        onNodeWithText(PAIRING_COPY_CAUTION).assertExists()
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

    // --- 危険な操作: すべての情報の消去 ---

    /** onResetAll を渡さないプラットフォームでは消去ボタンを出さない。 */
    @Test
    fun resetButtonHiddenWhenCallbackAbsent() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_DANGER_TOGGLE).performScrollTo().performClick()

        onNodeWithTag(TAG_RESET).assertDoesNotExist()
    }

    /** 鍵が無くても消去はできるため、onResetAll があれば危険な操作の見出しごと出る。 */
    @Test
    fun resetButtonShownWithoutKeyWhenCallbackPresent() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(host = "h", accessToken = "tk", deviceName = "d"))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller, onResetAll = {}) }

        onNodeWithTag(TAG_DANGER_TOGGLE).performScrollTo().performClick()

        onNodeWithTag(TAG_RESET).performScrollTo().assertIsDisplayed()
        onNodeWithTag(TAG_ROTATE).assertDoesNotExist()
    }

    /** 消去ボタンは確認を挟み、承認して初めて onResetAll を呼ぶ。 */
    @Test
    fun resetRunsOnlyAfterConfirmation() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)
        var resetCount = 0

        setContent { SettingsScreen(controller, onResetAll = { resetCount++ }) }

        onNodeWithTag(TAG_DANGER_TOGGLE).performScrollTo().performClick()
        onNodeWithTag(TAG_RESET).performScrollTo().performClick()
        assertEquals(0, resetCount)

        onNodeWithTag(TAG_RESET_CONFIRM).performClick()

        assertEquals(1, resetCount)
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
        onNodeWithTag(TAG_FORWARD_WORK_PROFILE).assertDoesNotExist()
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

    /** SMS 直接受信トグルの下に、理由を説明する文が表示される。 */
    @Test
    fun smsDirectReceiveDescriptionIsDisplayedWhenSendRoleOptionsShown() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller, showSendRoleOptions = true) }

        onNodeWithText(SMS_DIRECT_RECEIVE_DESCRIPTION).assertIsDisplayed()
    }

    /**
     * 仕事用プロファイルの転送は既定で OFF のまま出し、ON にできることと
     * 既定では転送しないことを説明文で示す（§3.1）。
     */
    @Test
    fun workProfileForwardingIsOffAndExplainedWhenSendRoleOptionsShown() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller, showSendRoleOptions = true) }

        onNodeWithTag(TAG_FORWARD_WORK_PROFILE).assertIsOff()
        onNodeWithText(FORWARD_WORK_PROFILE_DESCRIPTION).assertIsDisplayed()
    }

    /** 仕事用プロファイルのトグルは即座に保存され、他の送信ロール設定を巻き添えにしない。 */
    @Test
    fun workProfileForwardingIsPersistedWithoutDisturbingOtherSendRoleSettings() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig(sendEnabled = true, smsDirectReceive = true))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller, showSendRoleOptions = true) }

        onNodeWithTag(TAG_FORWARD_WORK_PROFILE).performClick()

        val loaded = repo.load()
        assertEquals(true, loaded.forwardWorkProfileNotifications)
        assertEquals(true, loaded.sendEnabled)
        assertEquals(true, loaded.smsDirectReceive)
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

    /** currentVersionName を渡すと「アプリの更新」セクションに動作中の版が出る。 */
    @Test
    fun updateSectionShowsCurrentVersionName() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP), scope)

        try {
            setContent {
                SettingsScreen(
                    settingsController,
                    update = UpdateUi(updateController, currentVersionName = "0.1.2"),
                )
            }

            onNodeWithTag(TAG_CURRENT_VERSION).performScrollTo().assertExists()
            onNodeWithText("現在のバージョン 0.1.2").assertExists()
        } finally {
            scope.cancel()
        }
    }

    /** 配布物として動いていなければ更新確認は行えず、配布版のみである旨の注記が出る。 */
    @Test
    fun updateCheckDisabledWithNoteWhenNotDistributed() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP), scope)

        try {
            setContent {
                SettingsScreen(settingsController, update = UpdateUi(updateController, canUpdate = false))
            }

            onNodeWithTag(TAG_UPDATE_CHECK).performScrollTo().assertIsNotEnabled()
            onNodeWithTag(TAG_UPDATE_DEV_BUILD_NOTE).assertExists()
        } finally {
            scope.cancel()
        }
    }

    /** currentVersionName 未指定なら版の行は出さない（版数を解決できない実行経路のため）。 */
    @Test
    fun updateSectionHidesVersionWhenNameNotProvided() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP), scope)

        try {
            setContent { SettingsScreen(settingsController, update = UpdateUi(updateController)) }

            onNodeWithTag(TAG_CURRENT_VERSION).assertDoesNotExist()
        } finally {
            scope.cancel()
        }
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
            UpdateController(UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP), scope)

        try {
            setContent { SettingsScreen(settingsController, update = UpdateUi(updateController)) }

            onNodeWithTag(TAG_UPDATE_CHECK).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(TAG_UPDATE_STATUS).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("更新確認に失敗しました: latest.json の取得に失敗しました (HTTP 404)").assertExists()
        } finally {
            scope.cancel()
        }
    }

    /** Available のとき適用ボタンが出て、押すと onInstallUpdate に配布物の情報が渡る。 */
    @Test
    fun updateAvailableShowsInstallButtonAndInvokesOnInstallUpdate() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manifestJson = """
            { "desktop": { "versionCode": 20, "versionName": "2.0.0", "sha256": "abc" } }
        """.trimIndent()
        val key = TestSigningKey()
        val engine = signedManifestEngine(manifestJson, key.sign(manifestJson))
        val checker = UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP, publicKey = key.publicKey)
        val updateController = UpdateController(checker, scope)
        var installed: UpdateStatus.Available? = null

        try {
            setContent {
                SettingsScreen(
                    settingsController,
                    update = UpdateUi(
                        updateController,
                        onInstall = { available -> installed = available },
                    ),
                )
            }

            onNodeWithTag(TAG_UPDATE_CHECK).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(TAG_UPDATE_INSTALL).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("新しいバージョン 2.0.0").assertExists()
            onNodeWithTag(TAG_UPDATE_INSTALL).performScrollTo().performClick()

            assertEquals(UpdateStatus.Available("2.0.0", releaseAssetUrl("peranta.msi"), "abc"), installed)
        } finally {
            scope.cancel()
        }
    }

    /** 適用が進行中のあいだは進捗を出し、適用ボタンを押せなくする。 */
    @Test
    fun updateInstallStateShowsProgressAndDisablesButton() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manifestJson = """
            { "desktop": { "versionCode": 20, "versionName": "2.0.0", "sha256": "abc" } }
        """.trimIndent()
        val key = TestSigningKey()
        val engine = signedManifestEngine(manifestJson, key.sign(manifestJson))
        val checker = UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP, publicKey = key.publicKey)
        val updateController = UpdateController(checker, scope)

        try {
            setContent {
                SettingsScreen(
                    settingsController,
                    update = UpdateUi(
                        updateController,
                        installState = UpdateInstallState.Downloading(32_000_000, 68_000_000),
                        onInstall = {},
                    ),
                )
            }

            onNodeWithTag(TAG_UPDATE_CHECK).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(TAG_UPDATE_INSTALL).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag(TAG_UPDATE_INSTALL_STATE).performScrollTo().assertExists()
            onNodeWithText("ダウンロード中... 30.5 MB / 64.8 MB").assertExists()
            onNodeWithTag(TAG_UPDATE_PROGRESS).performScrollTo().assertExists()
            onNodeWithTag(TAG_UPDATE_INSTALL).performScrollTo().assertIsNotEnabled()
        } finally {
            scope.cancel()
        }
    }

    /** 照合まで済むと適用の確認を出し、承諾すると onApply が呼ばれる。 */
    @Test
    fun readyToApplyShowsConfirmDialogAndInvokesOnApply() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP), scope)
        var applied = false

        try {
            setContent {
                SettingsScreen(
                    settingsController,
                    update = UpdateUi(
                        updateController,
                        installState = UpdateInstallState.ReadyToApply,
                        onInstall = {},
                        onApply = { applied = true },
                    ),
                )
            }

            onNodeWithTag(TAG_UPDATE_APPLY_DIALOG).assertExists()
            onNodeWithTag(TAG_UPDATE_APPLY_CONFIRM).performClick()

            assertTrue(applied)
        } finally {
            scope.cancel()
        }
    }

    /** 適用の確認を取りやめると onCancelApply が呼ばれる（ダウンロード済みの配布物を捨てる）。 */
    @Test
    fun readyToApplyCancelInvokesOnCancelApply() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP), scope)
        var cancelled = false

        try {
            setContent {
                SettingsScreen(
                    settingsController,
                    update = UpdateUi(
                        updateController,
                        installState = UpdateInstallState.ReadyToApply,
                        onInstall = {},
                        onApply = {},
                        onCancelApply = { cancelled = true },
                    ),
                )
            }

            onNodeWithTag(TAG_UPDATE_APPLY_CANCEL).performClick()

            assertTrue(cancelled)
        } finally {
            scope.cancel()
        }
    }

    /** 適用の確認を持たないプラットフォーム（onApply なし）では確認を出さない。 */
    @Test
    fun readyToApplyWithoutOnApplyShowsNoDialog() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(readyConfig())
        val settingsController = SettingsController(repo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val updateController =
            UpdateController(UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP), scope)

        try {
            setContent {
                SettingsScreen(
                    settingsController,
                    update = UpdateUi(
                        updateController,
                        installState = UpdateInstallState.ReadyToApply,
                        onInstall = {},
                    ),
                )
            }

            onNodeWithTag(TAG_UPDATE_APPLY_DIALOG).assertDoesNotExist()
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
            host = "peranta.example.com",
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
