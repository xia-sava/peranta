package to.sava.peranta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

/** 共有画面の送信ボタンのタグ。 */
const val TAG_SHARE_SEND: String = "share-send"

/** 共有画面のキャプション入力のタグ。 */
const val TAG_SHARE_CAPTION: String = "share-caption"

/**
 * 共有シートから渡されたファイル（画像を含む）を転送する前に、キャプションを入力して送信する小さな画面（§4.3）。
 * 宛先はペアリング済みの全端末（`to: "*"`）で、[itemCount] 件のファイルをまとめて送る。
 */
@Composable
fun ShareScreen(
    itemCount: Int,
    onSend: (caption: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var caption by remember { mutableStateOf("") }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "ファイルを送信", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "$itemCount 件のファイルをペアリング済みの端末へ送ります。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text("キャプション（任意）") },
                modifier = Modifier.fillMaxWidth().testTag(TAG_SHARE_CAPTION),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) { Text("キャンセル") }
                Button(
                    onClick = { onSend(caption.ifBlank { null }) },
                    modifier = Modifier.testTag(TAG_SHARE_SEND),
                ) { Text("送信") }
            }
        }
    }
}
