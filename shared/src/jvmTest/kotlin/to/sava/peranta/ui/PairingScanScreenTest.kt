package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.pairing.PairingData
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.pairing.PairingUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class PairingScanScreenTest {

    private fun validUri(): String =
        PairingUri.encode(
            PairingData(
                host = "peranta.sava.to",
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
}
