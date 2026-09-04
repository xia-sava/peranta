package to.sava.peranta.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShareScreenTest {

    /** 送信ボタンは入力したキャプションを渡す。 */
    @Test
    fun sendPassesCaption() = runComposeUiTest {
        var sent: String? = "unset"
        setContent {
            ShareScreen(fileNames = listOf("旅行.jpg", "領収書.pdf"), onSend = { sent = it }, onCancel = {})
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
            ShareScreen(fileNames = listOf("旅行.jpg"), onSend = { sent = it }, onCancel = {})
        }
        onNodeWithTag(TAG_SHARE_SEND).performClick()
        assertEquals(null, sent)
    }

    /** 共有シートからのファイル送信は、件数だけでなく送るファイル名を全件並べて出す。 */
    @Test
    fun listsEveryFileName() = runComposeUiTest {
        setContent {
            ShareScreen(fileNames = listOf("旅行.jpg", "領収書.pdf", "memo.txt"), onSend = {}, onCancel = {})
        }
        onNodeWithText("3 件のファイルをペアリング済みの端末へ送ります。").assertExists()
        onNodeWithTag(TAG_SHARE_FILES).assertExists()
        onNodeWithText("旅行.jpg").assertExists()
        onNodeWithText("領収書.pdf").assertExists()
        onNodeWithText("memo.txt").assertExists()
    }

    /** ファイルが無い（テキストのみ共有）ときメッセージ送信の文言へ切り替わり、一覧は出さない。 */
    @Test
    fun messageModeShowsMessageWording() = runComposeUiTest {
        setContent {
            ShareScreen(fileNames = emptyList(), onSend = {}, onCancel = {})
        }
        onNodeWithText("メッセージを送信").assertExists()
        onNodeWithText("ペアリング済みの端末へメッセージを送ります。").assertExists()
        onNodeWithText("メッセージ").assertExists()
        onNodeWithTag(TAG_SHARE_FILES).assertDoesNotExist()
    }

    /** ファイルが無く本文が空白のとき送信ボタンは無効。 */
    @Test
    fun messageModeDisablesSendWhenBlank() = runComposeUiTest {
        setContent {
            ShareScreen(fileNames = emptyList(), onSend = {}, onCancel = {})
        }
        onNodeWithTag(TAG_SHARE_SEND).assertIsNotEnabled()
    }

    /** initialText がキャプション/メッセージ入力欄へプレフィルされる。 */
    @Test
    fun initialTextPrefillsInput() = runComposeUiTest {
        setContent {
            ShareScreen(fileNames = emptyList(), onSend = {}, onCancel = {}, initialText = "こんにちは")
        }
        onNodeWithTag(TAG_SHARE_CAPTION).assertTextContains("こんにちは")
    }

    /** sending=true の間は送信ボタンが無効。 */
    @Test
    fun sendingDisablesSendButton() = runComposeUiTest {
        setContent {
            ShareScreen(fileNames = listOf("旅行.jpg", "領収書.pdf"), onSend = {}, onCancel = {}, sending = true)
        }
        onNodeWithTag(TAG_SHARE_SEND).assertIsNotEnabled()
    }
}
