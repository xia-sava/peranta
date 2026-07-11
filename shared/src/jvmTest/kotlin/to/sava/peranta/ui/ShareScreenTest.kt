package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShareScreenTest {

    /** 送信ボタンは入力したキャプションを渡す。 */
    @Test
    fun sendPassesCaption() = runComposeUiTest {
        var sent: String? = "unset"
        setContent {
            ShareScreen(imageCount = 2, onSend = { sent = it }, onCancel = {})
        }
        onNodeWithTag(TAG_SHARE_CAPTION).performTextInput("旅行")
        onNodeWithTag(TAG_SHARE_SEND).performClick()
        assertEquals("旅行", sent)
    }

    /** キャプション未入力なら null を渡す（空文字は載せない）。 */
    @Test
    fun blankCaptionSendsNull() = runComposeUiTest {
        var sent: String? = "unset"
        setContent {
            ShareScreen(imageCount = 1, onSend = { sent = it }, onCancel = {})
        }
        onNodeWithTag(TAG_SHARE_SEND).performClick()
        assertEquals(null, sent)
    }
}
