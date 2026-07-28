package to.sava.peranta.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import to.sava.peranta.pairing.pairingQrMatrix
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class QrCodeCanvasTest {

    /** QR を押すと拡大表示が開き、「閉じる」で戻る。 */
    @Test
    fun tappingQrOpensAndClosesZoomedView() = runComposeUiTest {
        setContent {
            ZoomableQrCode(pairingQrMatrix("peranta://pair?x=1"), modifier = Modifier.size(240.dp))
        }

        onNodeWithTag(TAG_QR_ZOOMED).assertDoesNotExist()

        onNodeWithTag(TAG_QR_CODE).performClick()
        onNodeWithTag(TAG_QR_ZOOMED).assertIsDisplayed()

        onNodeWithTag(TAG_QR_ZOOM_CLOSE).performClick()
        onNodeWithTag(TAG_QR_ZOOMED).assertDoesNotExist()
    }
}
