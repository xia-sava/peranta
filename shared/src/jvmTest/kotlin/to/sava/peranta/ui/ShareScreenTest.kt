package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
            ShareScreen(itemCount = 2, onSend = { sent = it }, onCancel = {})
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
            ShareScreen(itemCount = 1, onSend = { sent = it }, onCancel = {})
        }
        onNodeWithTag(TAG_SHARE_SEND).performClick()
        assertEquals(null, sent)
    }

    /** itemCount=0（テキストのみ共有）のときメッセージ送信の文言に切り替わる。 */
    @Test
    fun messageModeShowsMessageWording() = runComposeUiTest {
        setContent {
            ShareScreen(itemCount = 0, onSend = {}, onCancel = {})
        }
        onNodeWithText("メッセージを送信").assertExists()
        onNodeWithText("ペアリング済みの端末へメッセージを送ります。").assertExists()
        onNodeWithText("メッセージ").assertExists()
    }

    /** itemCount=0 で本文が空白のとき送信ボタンは無効。 */
    @Test
    fun messageModeDisablesSendWhenBlank() = runComposeUiTest {
        setContent {
            ShareScreen(itemCount = 0, onSend = {}, onCancel = {})
        }
        onNodeWithTag(TAG_SHARE_SEND).assertIsNotEnabled()
    }

    /** initialText がキャプション/メッセージ入力欄へプレフィルされる。 */
    @Test
    fun initialTextPrefillsInput() = runComposeUiTest {
        setContent {
            ShareScreen(itemCount = 0, onSend = {}, onCancel = {}, initialText = "こんにちは")
        }
        onNodeWithTag(TAG_SHARE_CAPTION).assertTextContains("こんにちは")
    }

    /** sending=true の間は送信ボタンが無効。 */
    @Test
    fun sendingDisablesSendButton() = runComposeUiTest {
        setContent {
            ShareScreen(itemCount = 2, onSend = {}, onCancel = {}, sending = true)
        }
        onNodeWithTag(TAG_SHARE_SEND).assertIsNotEnabled()
    }
}
