package to.sava.peranta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 共有画面の送信ボタンのタグ。 */
const val TAG_SHARE_SEND: String = "share-send"

/** 共有画面のキャプション入力のタグ。 */
const val TAG_SHARE_CAPTION: String = "share-caption"

/** 共有画面の送信対象ファイル一覧のタグ。 */
const val TAG_SHARE_FILES: String = "share-files"

/**
 * 共有シートから渡されたファイル（画像を含む）を転送する前に、キャプションを入力して送信する小さな画面（§4.3）。
 * 宛先はペアリング済みの全端末（`to: "*"`）で、[fileNames] のファイルをまとめて送る。
 * **送るファイルの名前を必ず並べて出す。** 共有元は任意のアプリで、件数だけでは何を送るのか判断できない。
 * [fileNames] が空のとき（テキストのみの共有）はメッセージ送信の文言・挙動に切り替わる（§7.2）。
 * [initialText] は入力欄の初期値（ファイル共有に添えられた説明文、またはメッセージ本文）、
 * [sending] が真の間は送信ボタンを無効化する。
 */
@Composable
fun ShareScreen(
    fileNames: List<String>,
    onSend: (caption: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialText: String? = null,
    sending: Boolean = false,
) {
    var caption by remember { mutableStateOf(initialText.orEmpty()) }
    val isMessageMode = fileNames.isEmpty()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (isMessageMode) "メッセージを送信" else "ファイルを送信",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (isMessageMode) {
                    "ペアリング済みの端末へメッセージを送ります。"
                } else {
                    "${fileNames.size} 件のファイルをペアリング済みの端末へ送ります。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isMessageMode) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag(TAG_SHARE_FILES),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fileNames.forEach { fileName ->
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text(if (isMessageMode) "メッセージ" else "キャプション（任意）") },
                modifier = Modifier.fillMaxWidth().testTag(TAG_SHARE_CAPTION),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) { Text("キャンセル") }
                Button(
                    onClick = { onSend(caption.ifBlank { null }) },
                    enabled = !sending && !(isMessageMode && caption.isBlank()),
                    modifier = Modifier.testTag(TAG_SHARE_SEND),
                ) { Text("送信") }
            }
        }
    }
}
