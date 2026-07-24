package to.sava.peranta.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.send.MAX_MESSAGE_TEXT_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MessageComposerTest {

    private fun ui(send: suspend (String) -> Boolean): MessageComposerUi = MessageComposerUi(send = send)

    private fun attachmentsUi(pasteImage: () -> Boolean = { false }): ComposerAttachmentsUi = ComposerAttachmentsUi(
        staged = MutableStateFlow(emptyList()),
        uploadProgress = MutableStateFlow<TransferProgress?>(null),
        pickFiles = {},
        removeStaged = {},
        pasteImage = pasteImage,
    )

    /** 空文字では送信ボタンが無効化される。 */
    @Test
    fun blankTextDisablesSend() = runComposeUiTest {
        setContent { MessageComposer(ui(send = { true })) }
        onNodeWithTag(TAG_COMPOSER_SEND).assertIsNotEnabled()
    }

    /** 送信成功時は入力欄をクリアする。 */
    @Test
    fun successClearsInput() = runComposeUiTest {
        var sent: String? = null
        setContent { MessageComposer(ui(send = { text -> sent = text; true })) }

        onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput("こんにちは")
        onNodeWithTag(TAG_COMPOSER_SEND).performClick()

        assertEquals("こんにちは", sent)
        onNodeWithTag(TAG_COMPOSER_INPUT).assertTextEquals("")
    }

    /** 送信失敗時は入力欄のテキストを保持する。 */
    @Test
    fun failurePreservesInput() = runComposeUiTest {
        setContent { MessageComposer(ui(send = { false })) }

        onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput("失敗するはず")
        onNodeWithTag(TAG_COMPOSER_SEND).performClick()

        onNodeWithTag(TAG_COMPOSER_INPUT).assertTextEquals("失敗するはず")
    }

    /** sendOnEnter=true では Enter で送信する。 */
    @Test
    fun sendOnEnterSendsOnPlainEnter() = runComposeUiTest {
        var sendCount = 0
        setContent { MessageComposer(ui(send = { sendCount++; true }), sendOnEnter = true) }

        onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput("送信テスト")
        onNodeWithTag(TAG_COMPOSER_INPUT).performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, sendCount)
    }

    /** sendOnEnter=true でも Shift+Enter は送信せず改行として素通しする。 */
    @Test
    fun sendOnEnterInsertsNewlineOnShiftEnter() = runComposeUiTest {
        var sendCount = 0
        setContent { MessageComposer(ui(send = { sendCount++; true }), sendOnEnter = true) }

        onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput("1行目")
        onNodeWithTag(TAG_COMPOSER_INPUT).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) }
        }

        assertEquals(0, sendCount)
        onNodeWithTag(TAG_COMPOSER_INPUT).assertTextEquals("1行目\n")
    }

    /** Ctrl+V でクリップボードに画像が有れば pasteImage を呼び、イベントを消費して既定の貼り付けを行わない。 */
    @Test
    fun ctrlVWithClipboardImageStagesAndConsumesEvent() = runComposeUiTest {
        var calls = 0
        val attachments = attachmentsUi(pasteImage = { calls++; true })
        setContent {
            MessageComposer(MessageComposerUi(send = { true }, attachments = attachments), sendOnEnter = true)
        }

        onNodeWithTag(TAG_COMPOSER_INPUT).performClick()
        onNodeWithTag(TAG_COMPOSER_INPUT).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }

        assertEquals(1, calls)
        onNodeWithTag(TAG_COMPOSER_INPUT).assertTextEquals("")
    }

    /** クリップボードに画像が無ければ pasteImage は false を返し、貼り付けハンドラは通常の貼り付けに委ねる。 */
    @Test
    fun ctrlVWithoutClipboardImageFallsThroughToDefaultPaste() = runComposeUiTest {
        var calls = 0
        val attachments = attachmentsUi(pasteImage = { calls++; false })
        setContent {
            MessageComposer(MessageComposerUi(send = { true }, attachments = attachments), sendOnEnter = true)
        }

        onNodeWithTag(TAG_COMPOSER_INPUT).performClick()
        onNodeWithTag(TAG_COMPOSER_INPUT).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }

        assertEquals(1, calls)
    }

    /** attachments が無い（添付未対応）ときは Ctrl+V を横取りしない。 */
    @Test
    fun ctrlVWithoutAttachmentsDoesNothing() = runComposeUiTest {
        setContent { MessageComposer(ui(send = { true }), sendOnEnter = true) }

        onNodeWithTag(TAG_COMPOSER_INPUT).performClick()
        onNodeWithTag(TAG_COMPOSER_INPUT).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }

        onNodeWithTag(TAG_COMPOSER_INPUT).assertTextEquals("")
    }

    /** attachments が null のときは添付ボタンを出さない。 */
    @Test
    fun noAttachmentsHidesAttachButton() = runComposeUiTest {
        setContent { MessageComposer(ui(send = { true })) }
        onNodeWithTag(TAG_COMPOSER_ATTACH).assertDoesNotExist()
    }

    /** 上限バイト数以下の入力では警告を出さない。 */
    @Test
    fun withinLimitShowsNoWarning() = runComposeUiTest {
        setContent { MessageComposer(ui(send = { true })) }
        onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput("短い本文")
        onNodeWithTag(TAG_COMPOSER_LIMIT_WARNING).assertDoesNotExist()
    }

    /** 上限バイト数を超える入力では、送信前に切り詰め警告を表示する。 */
    @Test
    fun overLimitShowsTruncationWarning() = runComposeUiTest {
        setContent { MessageComposer(ui(send = { true })) }
        onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput("a".repeat(MAX_MESSAGE_TEXT_BYTES + 1))
        onNodeWithTag(TAG_COMPOSER_LIMIT_WARNING, useUnmergedTree = true).assertTextEquals(
            "本文が上限 $MAX_MESSAGE_TEXT_BYTES バイトを超えています。超過分は切り詰めて送信されます",
        )
    }

    /** 送信中はボタンが「キャンセル」表示になり、押下で送信 Job を cancel してテキストを保持する。 */
    @Test
    fun sendingShowsCancelAndCancellingPreservesInput() = runComposeUiTest {
        var started = false
        setContent {
            MessageComposer(ui(send = { started = true; awaitCancellation() }))
        }

        onNodeWithTag(TAG_COMPOSER_INPUT).performTextInput("キャンセルするはず")
        onNodeWithTag(TAG_COMPOSER_SEND).performClick()

        assertTrue(started)
        onNodeWithTag(TAG_COMPOSER_SEND).assertTextEquals("キャンセル")

        onNodeWithTag(TAG_COMPOSER_SEND).performClick()

        onNodeWithTag(TAG_COMPOSER_SEND).assertTextEquals("送信")
        onNodeWithTag(TAG_COMPOSER_INPUT).assertTextEquals("キャンセルするはず")
    }
}
