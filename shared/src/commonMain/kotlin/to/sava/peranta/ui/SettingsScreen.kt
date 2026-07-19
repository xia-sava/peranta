package to.sava.peranta.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import to.sava.peranta.pairing.SettingsController

/** QR の自動非表示までの既定時間（§6: 表示は時間制限つき）。 */
private const val DEFAULT_QR_VISIBLE_MILLIS: Long = 60_000L

/** 鍵を作り直すと全端末で QR の読み直しが必要になる旨の警告文（§6）。 */
private const val ROTATE_WARNING_BODY: String =
    "前の鍵は破棄され、全端末で QR の読み直しが必要になります。続けますか？"

/** 保存後に再起動を促す文言（ホットリロードはしない）。 */
private const val RESTART_NOTICE: String =
    "設定を保存しました。変更を反映するにはアプリを再起動してください。"

/** センシティブ通知の履歴保存トグルの説明文（§11: 既定 OFF が安全側）。 */
private const val PERSIST_SENSITIVE_HISTORY_DESCRIPTION: String =
    "OFF のままだと OTP 等の本文はタイムラインに残しません。"

/**
 * 設定画面（§10.2）とペアリング（§10.3）を 1 画面にまとめたもの。
 * 接続情報の入力・保存、共有鍵の作成、QR による新端末追加を行う。
 * QR の描画・スクロールバー・ペアリング文字列コピーはプラットフォーム依存のため
 * [qrContent] / [scrollbarContent] / [onCopyPairingUri] スロットで注入する。
 */
@Composable
fun SettingsScreen(
    controller: SettingsController,
    modifier: Modifier = Modifier,
    qrContent: @Composable (uri: String) -> Unit = {},
    onOpenTimeline: (() -> Unit)? = null,
    qrVisibleMillis: Long = DEFAULT_QR_VISIBLE_MILLIS,
    scrollbarContent: @Composable BoxScope.(scrollState: ScrollState) -> Unit = {},
    onCopyPairingUri: ((String) -> Unit)? = null,
) {
    val initial = remember { controller.load() }
    var host by remember { mutableStateOf(initial.host) }
    var accessToken by remember { mutableStateOf(initial.accessToken.orEmpty()) }
    var deviceName by remember { mutableStateOf(initial.deviceName.orEmpty()) }
    var port by remember { mutableStateOf(initial.port?.toString().orEmpty()) }
    var keyId by remember { mutableStateOf(initial.keyId) }
    var hasKey by remember { mutableStateOf(!initial.sharedKeyBase64.isNullOrBlank()) }
    var persistSensitiveHistory by remember { mutableStateOf(initial.persistSensitiveHistory) }
    var attachFullTextWhenTruncated by remember { mutableStateOf(initial.attachFullTextWhenTruncated) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showRotateWarning by remember { mutableStateOf(false) }
    var pairingUri by remember { mutableStateOf<String?>(null) }

    fun rotateKey() {
        val updated = controller.rotateSharedKey()
        keyId = updated.keyId
        hasKey = true
        pairingUri = null
        statusMessage = "新しい共有鍵を作成しました（keyId=${updated.keyId}）。全端末で QR を読み直してください。"
    }

    LaunchedEffect(pairingUri) {
        if (pairingUri == null) return@LaunchedEffect
        delay(qrVisibleMillis)
        pairingUri = null
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val scrollState = rememberScrollState()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "設定", style = MaterialTheme.typography.titleLarge)
                    if (onOpenTimeline != null) {
                        TextButton(onClick = onOpenTimeline) { Text(text = "タイムラインへ") }
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("サーバホスト名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_HOST),
                )
                OutlinedTextField(
                    value = accessToken,
                    onValueChange = { accessToken = it },
                    label = { Text("アクセストークン") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_TOKEN),
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("端末名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_DEVICE_NAME),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { input -> port = input.filter { it.isDigit() } },
                    label = { Text("ポート（任意）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(TAG_PORT),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { persistSensitiveHistory = !persistSensitiveHistory },
                ) {
                    Checkbox(
                        checked = persistSensitiveHistory,
                        onCheckedChange = { persistSensitiveHistory = it },
                        modifier = Modifier.testTag(TAG_PERSIST_SENSITIVE),
                    )
                    Text(text = "センシティブな通知の本文を履歴に保存する")
                }
                Text(
                    text = PERSIST_SENSITIVE_HISTORY_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { attachFullTextWhenTruncated = !attachFullTextWhenTruncated },
                ) {
                    Checkbox(
                        checked = attachFullTextWhenTruncated,
                        onCheckedChange = { attachFullTextWhenTruncated = it },
                        modifier = Modifier.testTag(TAG_ATTACH_FULL_TEXT),
                    )
                    Text(text = "長文本文の全文をシームレスに添付・展開する")
                }

                Button(
                    onClick = {
                        controller.saveConnectionSettings(
                            host = host,
                            accessToken = accessToken,
                            deviceName = deviceName,
                            useTls = true,
                            port = port.toIntOrNull(),
                            persistSensitiveHistory = persistSensitiveHistory,
                            attachFullTextWhenTruncated = attachFullTextWhenTruncated,
                        )
                        statusMessage = RESTART_NOTICE
                    },
                    modifier = Modifier.testTag(TAG_SAVE),
                ) {
                    Text(text = "保存")
                }

                Text(
                    text = if (hasKey) "共有鍵: 設定済み（keyId=${keyId ?: "?"}）" else "共有鍵: 未設定",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { if (hasKey) showRotateWarning = true else rotateKey() },
                        modifier = Modifier.testTag(TAG_ROTATE),
                    ) {
                        Text(text = "鍵を作る")
                    }
                    OutlinedButton(
                        onClick = {
                            pairingUri = controller.buildPairingUri()
                            if (pairingUri == null) {
                                statusMessage = "先にトークンと共有鍵を設定してください。"
                            }
                        },
                        modifier = Modifier.testTag(TAG_ADD_DEVICE),
                    ) {
                        Text(text = "新しい端末を追加")
                    }
                }

                pairingUri?.let { uri ->
                    Text(
                        text = "この QR を新しい端末のカメラで読み取ってください。時間が経つと自動的に隠れます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    qrContent(uri)
                    if (onCopyPairingUri != null) {
                        OutlinedButton(
                            onClick = {
                                onCopyPairingUri(uri)
                                statusMessage = "ペアリング文字列をコピーしました。"
                            },
                            modifier = Modifier.testTag(TAG_COPY_PAIRING_URI),
                        ) {
                            Text(text = "文字列をコピー")
                        }
                    }
                    TextButton(onClick = { pairingUri = null }, modifier = Modifier.testTag(TAG_HIDE_QR)) {
                        Text(text = "QR を隠す")
                    }
                }

                statusMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(TAG_STATUS),
                    )
                }
            }
            scrollbarContent(scrollState)
        }
    }

    if (showRotateWarning) {
        AlertDialog(
            onDismissRequest = { showRotateWarning = false },
            title = { Text(text = "鍵を作り直しますか？") },
            text = { Text(text = ROTATE_WARNING_BODY) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRotateWarning = false
                        rotateKey()
                    },
                    modifier = Modifier.testTag(TAG_ROTATE_CONFIRM),
                ) {
                    Text(text = "破棄して作成")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRotateWarning = false }) {
                    Text(text = "やめる")
                }
            },
        )
    }
}

const val TAG_HOST: String = "settings-host"
const val TAG_TOKEN: String = "settings-token"
const val TAG_DEVICE_NAME: String = "settings-deviceName"
const val TAG_PORT: String = "settings-port"
const val TAG_PERSIST_SENSITIVE: String = "settings-persist-sensitive"
const val TAG_ATTACH_FULL_TEXT: String = "settings-attach-full-text"
const val TAG_SAVE: String = "settings-save"
const val TAG_ROTATE: String = "settings-rotate"
const val TAG_ROTATE_CONFIRM: String = "settings-rotate-confirm"
const val TAG_ADD_DEVICE: String = "settings-add-device"
const val TAG_HIDE_QR: String = "settings-hide-qr"
const val TAG_COPY_PAIRING_URI: String = "settings-copy-pairing-uri"
const val TAG_STATUS: String = "settings-status"
