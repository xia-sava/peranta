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

    /** 保存ボタン押下で入力値が ConfigRepository に反映される。 */
    @Test
    fun saveButtonPersistsInputToRepository() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_HOST).performTextReplacement("example.test")
        onNodeWithTag(TAG_TOKEN).performTextReplacement("tk")
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-1")
        onNodeWithTag(TAG_SAVE).performClick()

        val loaded = repo.load()
        assertEquals("example.test", loaded.host)
        assertEquals("tk", loaded.accessToken)
        assertEquals("desktop-1", loaded.deviceName)
    }

    /** 鍵未設定なら「鍵を作る」で警告なしに鍵が作られ keyId が確定する。 */
    @Test
    fun rotateWithoutExistingKeyCreatesKeyDirectly() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_ROTATE).performClick()

        val loaded = repo.load()
        assertNotNull(loaded.sharedKeyBase64)
        assertEquals("1", loaded.keyId)
    }

    /** 既存鍵があると「鍵を作る」で警告ダイアログが出て、確認後に鍵が作り直される。 */
    @Test
    fun rotateWithExistingKeyShowsWarningThenReplaces() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(sharedKeyBase64 = Base64.encode(ByteArray(32)), keyId = "1"))
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
        repo.save(
            PerantaConfig(
                host = "peranta.sava.to",
                accessToken = "tk",
                sharedKeyBase64 = Base64.encode(ByteArray(32)),
                keyId = "1",
            ),
        )
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

    /** devMode でないときは TLS 切替チェックボックスを表示しない（§16）。 */
    @Test
    fun tlsCheckboxHiddenWhenNotDevMode() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))

        setContent { SettingsScreen(controller, devMode = false) }

        onNodeWithTag(TAG_TLS).assertDoesNotExist()
    }

    /** devMode のときは TLS 切替チェックボックスを表示する。 */
    @Test
    fun tlsCheckboxShownWhenDevMode() = runComposeUiTest {
        val controller = SettingsController(ConfigRepository(MapSettings()))

        setContent { SettingsScreen(controller, devMode = true) }

        onNodeWithTag(TAG_TLS).assertIsDisplayed()
    }

    /** devMode でなければ、保存済み設定が TLS 無効でも保存時に TLS 有効を書き込む（§16）。 */
    @Test
    fun saveForcesTlsWhenNotDevMode() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(useTls = false))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller, devMode = false) }

        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-1")
        onNodeWithTag(TAG_SAVE).performClick()

        assertEquals(true, repo.load().useTls)
    }

    /** センシティブ履歴保存・全文添付のチェックボックスは既定値どおりに初期表示される（§11）。 */
    @Test
    fun sensitiveHistoryAndFullTextCheckboxesShowDefaults() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).assertIsOff()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).assertIsOn()
    }

    /** 保存済み設定の値がチェックボックスの初期状態に反映される。 */
    @Test
    fun sensitiveHistoryAndFullTextCheckboxesReflectSavedConfig() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(persistSensitiveHistory = true, attachFullTextWhenTruncated = false))
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).assertIsOn()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).assertIsOff()
    }

    /** チェックボックスをトグルして保存すると、値が ConfigRepository に反映される。 */
    @Test
    fun togglingSensitiveHistoryAndFullTextCheckboxesPersistsOnSave() = runComposeUiTest {
        val repo = ConfigRepository(MapSettings())
        val controller = SettingsController(repo)

        setContent { SettingsScreen(controller) }

        onNodeWithTag(TAG_PERSIST_SENSITIVE).performClick()
        onNodeWithTag(TAG_ATTACH_FULL_TEXT).performClick()
        onNodeWithTag(TAG_DEVICE_NAME).performTextReplacement("desktop-1")
        onNodeWithTag(TAG_SAVE).performClick()

        val loaded = repo.load()
        assertEquals(true, loaded.persistSensitiveHistory)
        assertEquals(false, loaded.attachFullTextWhenTruncated)
    }

    private companion object {
        const val QR_SLOT_TAG = "qr-slot"
    }
}
