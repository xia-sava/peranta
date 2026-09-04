package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import to.sava.peranta.pairing.pairingQrMatrix
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class QrCodeCanvasTest {

    /** QR を押すと拡大表示が開き、白い面を押すと戻る。 */
    @Test
    fun tappingQrOpensAndClosesZoomedView() = runComposeUiTest {
        setContent { ZoomableQrCode(pairingQrMatrix("peranta://pair?x=1")) }

        onNodeWithTag(TAG_QR_ZOOMED, useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag(TAG_QR_CODE).performClick()
        onNodeWithTag(TAG_QR_ZOOMED, useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag(TAG_QR_ZOOM_SURFACE).performClick()
        onNodeWithTag(TAG_QR_ZOOMED, useUnmergedTree = true).assertDoesNotExist()
    }
}
