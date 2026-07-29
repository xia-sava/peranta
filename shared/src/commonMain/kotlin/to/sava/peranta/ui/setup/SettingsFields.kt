package to.sava.peranta.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.sava.peranta.ui.TAG_COPY_PAIRING_URI
import to.sava.peranta.ui.TAG_DEVICE_NAME
import to.sava.peranta.ui.TAG_HIDE_QR
import to.sava.peranta.ui.TAG_HOST
import to.sava.peranta.ui.TAG_PORT
import to.sava.peranta.ui.TAG_TIMELINE_RETENTION_DAYS
import to.sava.peranta.ui.TAG_TOKEN

/** QR の自動非表示までの既定時間（§6: 表示は時間制限つき）。フラット画面とウィザードで共有する。 */
internal const val DEFAULT_QR_VISIBLE_MILLIS: Long = 60_000L

/** QR 表示ブロックの案内文（§10.3）。 */
private const val QR_HINT: String =
    "この QR を新しい端末のカメラで読み取ってください。読み取りにくいときは QR を押すと拡大します。" +
        "時間が経つと自動的に隠れます。"

/**
 * ペアリング文字列のコピーに添える注意（§10.3）。QR と違いクリップボードには時間制限が無く、
 * 貼り付け先・クリップボード履歴・クラウド同期に残りうるため、後始末を利用者に委ねる旨を明示する。
 */
internal const val PAIRING_COPY_CAUTION: String =
    "コピーする文字列には共有鍵とアクセストークンが入ります。ほかのアプリからも読めるので、" +
        "取り込みが済んだらクリップボードとその履歴を消してください。"

/** コピー完了メッセージ（§10.3）。[PAIRING_COPY_CAUTION] と同じ後始末を促す。 */
internal const val PAIRING_COPIED_MESSAGE: String =
    "ペアリング文字列をコピーしました。取り込みが済んだらクリップボードとその履歴を消してください。"

/**
 * 「SMS を直接受信して転送する」トグルの下に添える説明文（§3.1: SMS アプリの通知経由の弱点）。
 * ウィザードとフラット画面の両方から参照する。
 */
internal const val SMS_DIRECT_RECEIVE_DESCRIPTION: String =
    "ON にすると SMS を直接受信し、本文を全文確実に転送します。" +
        "OFF だと SMS アプリの通知経由になり、本文が省略されたり既読の SMS は転送されないことがあります。"

/** ポート欄の幅。5 桁の数字とラベルが収まるだけを取り、残りをホスト名へ回す。 */
private val PORT_FIELD_WIDTH = 112.dp

/** ホスト名とポートの間隔。 */
private val HOST_PORT_SPACING = 8.dp

/** ポート欄が空のときに薄く示す既定値。標準のポートを使うことが一目でわかる。 */
private const val PORT_PLACEHOLDER: String = "443"

/**
 * サーバのホスト名とポートの入力欄（§11）。ポートは標準のままで足りることが大半のため、
 * 独立した行を与えず幅を詰めて横に並べる。
 */
@Composable
internal fun HostPortFields(
    host: String,
    port: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    hostTag: String = TAG_HOST,
    portTag: String = TAG_PORT,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(HOST_PORT_SPACING)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text("サーバホスト名") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(hostTag),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { input -> onPortChange(input.filter { it.isDigit() }) },
            label = { Text("ポート") },
            placeholder = { Text(PORT_PLACEHOLDER) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(PORT_FIELD_WIDTH).testTag(portTag),
        )
    }
}

@Composable
internal fun TokenField(value: String, onValueChange: (String) -> Unit, tag: String = TAG_TOKEN) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("アクセストークン") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
internal fun DeviceNameField(value: String, onValueChange: (String) -> Unit, tag: String = TAG_DEVICE_NAME) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("端末名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** 「履歴の保持日数（任意）」欄の下に添える説明文（§11）。 */
internal const val TIMELINE_RETENTION_DAYS_DESCRIPTION: String =
    "入力した日数より古い履歴を起動時に削除します。空欄なら日数による削除は行いません。"

@Composable
internal fun TimelineRetentionDaysField(
    value: String,
    onValueChange: (String) -> Unit,
    tag: String = TAG_TIMELINE_RETENTION_DAYS,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }) },
        label = { Text("履歴の保持日数（任意）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
internal fun LabeledCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    tag: String,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
        Text(
            text = label,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Text(
            text = PAIRING_COPY_CAUTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(
            onClick = {
                onCopyPairingUri(uri)
                onCopied()
            },
            modifier = Modifier.testTag(TAG_COPY_PAIRING_URI),
        ) {
            Text(text = "QR文字列をコピー")
        }
    }
    TextButton(onClick = onHide, modifier = Modifier.testTag(TAG_HIDE_QR)) {
        Text(text = "QR を隠す")
    }
}
