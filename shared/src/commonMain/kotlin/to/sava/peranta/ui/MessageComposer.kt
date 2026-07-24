package to.sava.peranta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.send.MAX_MESSAGE_TEXT_BYTES

/** composer 入力欄のタグ。 */
const val TAG_COMPOSER_INPUT: String = "composer-input"

/** composer 送信ボタンのタグ。 */
const val TAG_COMPOSER_SEND: String = "composer-send"

/** composer 添付ボタンのタグ。 */
const val TAG_COMPOSER_ATTACH: String = "composer-attach"

/** ステージ済み添付チップのタグ接頭辞（末尾に index）。 */
const val TAG_COMPOSER_STAGED_PREFIX: String = "composer-staged-"

/** 本文が送信上限バイト数を超えているときの警告表示のタグ。 */
const val TAG_COMPOSER_LIMIT_WARNING: String = "composer-limit-warning"

/** 本文が上限バイト数を超えているときに composer へ出す警告文言。 */
private val MESSAGE_LIMIT_WARNING: String =
    "本文が上限 $MAX_MESSAGE_TEXT_BYTES バイトを超えています。超過分は切り詰めて送信されます"

/** ステージ済みの送信予定ファイル（表示用メタ）。 */
data class StagedFile(val name: String, val sizeBytes: Long)

/**
 * composer の添付操作束（§13 M9d）。Desktop だけが実装を渡し、null の端末では添付ボタンを出さない。
 */
class ComposerAttachmentsUi(
    val staged: StateFlow<List<StagedFile>>,
    /** null = アップロード中でない。 */
    val uploadProgress: StateFlow<TransferProgress?>,
    val pickFiles: () -> Unit,
    val removeStaged: (index: Int) -> Unit,
    /** クリップボードに画像が有ればステージへ追加して true を返す。無ければ何もせず false（通常の貼り付けに委ねる）。 */
    val pasteImage: () -> Boolean,
)

/**
 * composer の操作束。send はステージ済み添付が有れば FilePayload（caption=text）、
 * 無ければ MessagePayload を送り、成功で true を返す（成功時に入力欄・ステージをクリアする契約）。
 */
class MessageComposerUi(
    val send: suspend (text: String) -> Boolean,
    val attachments: ComposerAttachmentsUi? = null,
)

/** 入力欄 [text] の UTF-8 バイト長が送信上限を超えているか。 */
private fun exceedsMessageLimit(text: String): Boolean =
    text.encodeToByteArray().size > MAX_MESSAGE_TEXT_BYTES

/**
 * タイムライン下部の入力欄（§10.1）。テキスト入力・送信・（渡されれば）ファイル添付を担う。
 * [sendOnEnter] が真のとき Enter で送信・Shift+Enter で改行する（Desktop 用）。偽（Android 既定）では
 * キー操作を横取りせず IME の改行に任せる。添付が有るときは Ctrl+V でクリップボード画像もステージへ
 * 追加する（[ComposerAttachmentsUi.pasteImage]）。画像が無ければ通常のテキスト貼り付けに委ねる。
 */
@Composable
fun MessageComposer(
    ui: MessageComposerUi,
    modifier: Modifier = Modifier,
    sendOnEnter: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val attachments = ui.attachments
    val staged = attachments?.staged?.collectAsState()?.value.orEmpty()
    val uploadProgress = attachments?.uploadProgress?.collectAsState()?.value

    val hasContent = text.isNotBlank() || staged.isNotEmpty()
    val overLimit = exceedsMessageLimit(text)

    val onSendClick: () -> Unit = {
        if (sending) {
            sendJob?.cancel()
        } else {
            sendJob = scope.launch {
                sending = true
                try {
                    if (ui.send(text)) {
                        text = ""
                    }
                } finally {
                    sending = false
                    sendJob = null
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (attachments != null && staged.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                staged.forEachIndexed { index, file ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("$TAG_COMPOSER_STAGED_PREFIX$index"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${file.name} (${formatFileSize(file.sizeBytes)})",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { attachments.removeStaged(index) }) { Text("×") }
                        }
                    }
                }
            }
        }
        if (uploadProgress != null) {
            LinearProgressIndicator(
                progress = { uploadProgress.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (attachments != null) {
                TextButton(
                    onClick = attachments.pickFiles,
                    modifier = Modifier.testTag(TAG_COMPOSER_ATTACH),
                ) { Text("📎") }
            }
            val pasteImage = attachments?.pasteImage
            val textFieldModifier = Modifier.weight(1f).testTag(TAG_COMPOSER_INPUT).let { base ->
                if (sendOnEnter || pasteImage != null) {
                    base.onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            sendOnEnter && event.key == Key.Enter && !event.isShiftPressed -> {
                                if (!sending && hasContent) onSendClick()
                                true
                            }
                            pasteImage != null && event.isCtrlPressed && event.key == Key.V -> pasteImage()
                            else -> false
                        }
                    }
                } else {
                    base
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = textFieldModifier,
                maxLines = 4,
                isError = overLimit,
                supportingText = if (overLimit) {
                    {
                        Text(
                            text = MESSAGE_LIMIT_WARNING,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(TAG_COMPOSER_LIMIT_WARNING),
                        )
                    }
                } else {
                    null
                },
            )
            TextButton(
                onClick = onSendClick,
                enabled = sending || hasContent,
                modifier = Modifier.testTag(TAG_COMPOSER_SEND),
            ) { Text(if (sending) "キャンセル" else "送信") }
        }
    }
}
