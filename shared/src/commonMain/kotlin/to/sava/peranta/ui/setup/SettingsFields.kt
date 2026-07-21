package to.sava.peranta.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import to.sava.peranta.ui.TAG_COPY_PAIRING_URI
import to.sava.peranta.ui.TAG_DEVICE_NAME
import to.sava.peranta.ui.TAG_HIDE_QR
import to.sava.peranta.ui.TAG_HOST
import to.sava.peranta.ui.TAG_PORT
import to.sava.peranta.ui.TAG_TOKEN

/** QR 表示ブロックの案内文（§10.3）。 */
private const val QR_HINT: String =
    "この QR を新しい端末のカメラで読み取ってください。時間が経つと自動的に隠れます。"

@Composable
internal fun HostField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("サーバホスト名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TAG_HOST),
    )
}

@Composable
internal fun TokenField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("アクセストークン") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TAG_TOKEN),
    )
}

@Composable
internal fun DeviceNameField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("端末名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TAG_DEVICE_NAME),
    )
}

@Composable
internal fun PortField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }) },
        label = { Text("ポート（任意）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(TAG_PORT),
    )
}

@Composable
internal fun LabeledCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    tag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
        Text(text = label)
    }
}

@Composable
internal fun KeyStatusText(hasKey: Boolean, keyId: String?) {
    Text(
        text = if (hasKey) "共有鍵: 設定済み（keyId=${keyId ?: "?"}）" else "共有鍵: 未設定",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

/** QR 表示ブロック（案内文・QR・コピー・非表示）。フラット画面とウィザード PAIRING で共有する。 */
@Composable
internal fun PairingQrSection(
    uri: String,
    qrContent: @Composable (uri: String) -> Unit,
    onCopyPairingUri: ((String) -> Unit)?,
    onCopied: () -> Unit,
    onHide: () -> Unit,
) {
    Text(
        text = QR_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    qrContent(uri)
    if (onCopyPairingUri != null) {
        OutlinedButton(
            onClick = {
                onCopyPairingUri(uri)
                onCopied()
            },
            modifier = Modifier.testTag(TAG_COPY_PAIRING_URI),
        ) {
            Text(text = "文字列をコピー")
        }
    }
    TextButton(onClick = onHide, modifier = Modifier.testTag(TAG_HIDE_QR)) {
        Text(text = "QR を隠す")
    }
}
