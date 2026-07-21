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
import to.sava.peranta.pairing.SetupRole
import to.sava.peranta.pairing.SetupStep
import to.sava.peranta.pairing.SetupWizard

/** QR の自動非表示までの既定時間（§6: 表示は時間制限つき）。 */
private const val DEFAULT_QR_VISIBLE_MILLIS: Long = 60_000L

/** 鍵を作り直すと全端末で QR の読み直しが必要になる旨の警告文（§6）。 */
private const val ROTATE_WARNING_BODY: String =
    "前の鍵は破棄され、全端末で QR の読み直しが必要になります。続けますか？"

/** 保存完了を知らせる文言（設定変更は自動反映される）。 */
private const val SAVE_NOTICE: String = "設定を保存しました。"

/** センシティブ通知の履歴保存トグルの説明文（§11: 既定 OFF が安全側）。 */
private const val PERSIST_SENSITIVE_HISTORY_DESCRIPTION: String =
    "OFF のままだと OTP 等の本文はタイムラインに残しません。"

/** QR 表示ブロックの案内文（§10.3）。 */
private const val QR_HINT: String =
    "この QR を新しい端末のカメラで読み取ってください。時間が経つと自動的に隠れます。"

/** 共有鍵・トークン未設定で QR を作れないときの案内文。 */
private const val PAIRING_PREREQUISITE_NOTICE: String = "先にトークンと共有鍵を設定してください。"

/**
 * 設定画面（§10.2）とペアリング（§10.3）を 1 画面にまとめたもの。
 * 初期設定が未完了なら [SetupRole.SENDER] のステップ形式ウィザードを、完了していれば全項目を
 * 一度に編集できるフラット画面を自動選択する。接続情報の入力・保存、共有鍵の作成、
 * QR による新端末追加を行う。
 *
 * QR の描画・スクロールバー・ペアリング文字列コピーはプラットフォーム依存のため
 * [qrContent] / [scrollbarContent] / [onCopyPairingUri] スロットで注入する。
 * [showSendRoleOptions] が真のときだけ送信ロール（[to.sava.peranta.config.PerantaConfig.sendEnabled] /
 * [to.sava.peranta.config.PerantaConfig.smsDirectReceive]）のトグルをフラット画面に表示する。
 * [onSaved] は設定の保存・鍵生成が成功した直後に呼ぶ（受信パイプラインの再構築契機に使う）。
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
    showSendRoleOptions: Boolean = false,
    onSaved: (() -> Unit)? = null,
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
    var sendEnabled by remember { mutableStateOf(initial.sendEnabled) }
    var smsDirectReceive by remember { mutableStateOf(initial.smsDirectReceive) }

    var setupComplete by remember { mutableStateOf(controller.isSetupComplete()) }
    var currentStep by remember {
        mutableStateOf(SetupWizard.firstIncompleteStep(initial, SetupRole.SENDER) ?: SetupStep.CONNECTION)
    }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showRotateWarning by remember { mutableStateOf(false) }
    var pairingUri by remember { mutableStateOf<String?>(null) }

    /** ウィザードの表示ステップを最新の保存状態で更新し、SENDER の全ステップ完了ならフラット画面へ移る。 */
    fun refreshWizard() {
        SetupWizard.firstIncompleteStep(controller.load(), SetupRole.SENDER)
            ?.let { currentStep = it }
            ?: run { setupComplete = true }
    }

    fun persistConnection() {
        controller.saveConnectionSettings(
            host = host,
            accessToken = accessToken,
            deviceName = deviceName,
            port = port.toIntOrNull(),
            persistSensitiveHistory = persistSensitiveHistory,
            attachFullTextWhenTruncated = attachFullTextWhenTruncated,
        )
    }

    fun saveConnection() {
        persistConnection()
        onSaved?.invoke()
    }

    fun rotateKey() {
        val updated = controller.rotateSharedKey()
        keyId = updated.keyId
        hasKey = true
        pairingUri = null
        onSaved?.invoke()
        statusMessage = "新しい共有鍵を作成しました（keyId=${updated.keyId}）。全端末で QR を読み直してください。"
        if (!setupComplete) refreshWizard()
    }

    /** ペアリング URI を採番して QR 表示状態にする。作れないときは案内文だけ出す。 */
    fun showPairingQr() {
        val uri = controller.buildPairingUri()
        pairingUri = uri
        if (uri == null) {
            statusMessage = PAIRING_PREREQUISITE_NOTICE
        } else if (!setupComplete) {
            refreshWizard()
        }
    }

    LaunchedEffect(pairingUri) {
        if (pairingUri == null) return@LaunchedEffect
        delay(qrVisibleMillis)
        pairingUri = null
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val scrollState = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (setupComplete) {
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

                        HostField(value = host, onValueChange = { host = it })
                        TokenField(value = accessToken, onValueChange = { accessToken = it })
                        DeviceNameField(value = deviceName, onValueChange = { deviceName = it })
                        PortField(value = port, onValueChange = { port = it })

                        LabeledCheckbox(
                            checked = persistSensitiveHistory,
                            onCheckedChange = { persistSensitiveHistory = it },
                            label = "センシティブな通知の本文を履歴に保存する",
                            tag = TAG_PERSIST_SENSITIVE,
                        )
                        Text(
                            text = PERSIST_SENSITIVE_HISTORY_DESCRIPTION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LabeledCheckbox(
                            checked = attachFullTextWhenTruncated,
                            onCheckedChange = { attachFullTextWhenTruncated = it },
                            label = "長文本文の全文をシームレスに添付・展開する",
                            tag = TAG_ATTACH_FULL_TEXT,
                        )

                        if (showSendRoleOptions) {
                            LabeledCheckbox(
                                checked = sendEnabled,
                                onCheckedChange = { sendEnabled = it },
                                label = "この端末から通知・SMS を送信する",
                                tag = TAG_SEND_ENABLED,
                            )
                            LabeledCheckbox(
                                checked = smsDirectReceive,
                                onCheckedChange = { smsDirectReceive = it },
                                label = "SMS を直接受信して転送する",
                                tag = TAG_SMS_DIRECT_RECEIVE,
                            )
                        }

                        Button(
                            onClick = {
                                persistConnection()
                                if (showSendRoleOptions) {
                                    controller.saveSendRoleSettings(sendEnabled, smsDirectReceive)
                                }
                                onSaved?.invoke()
                                statusMessage = SAVE_NOTICE
                            },
                            modifier = Modifier.testTag(TAG_SAVE),
                        ) {
                            Text(text = "保存")
                        }

                        KeyStatusText(hasKey = hasKey, keyId = keyId)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { if (hasKey) showRotateWarning = true else rotateKey() },
                                modifier = Modifier.testTag(TAG_ROTATE),
                            ) {
                                Text(text = "鍵を作る")
                            }
                            OutlinedButton(
                                onClick = { showPairingQr() },
                                modifier = Modifier.testTag(TAG_ADD_DEVICE),
                            ) {
                                Text(text = "新しい端末を追加")
                            }
                        }

                        pairingUri?.let { uri ->
                            PairingQrSection(
                                uri = uri,
                                qrContent = qrContent,
                                onCopyPairingUri = onCopyPairingUri,
                                onCopied = { statusMessage = "ペアリング文字列をコピーしました。" },
                                onHide = { pairingUri = null },
                            )
                        }
                    } else {
                        val senderSteps = SetupWizard.steps(SetupRole.SENDER)
                        val stepIndex = senderSteps.indexOf(currentStep)
                        Text(text = "初期設定", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "ステップ ${stepIndex + 1}/${senderSteps.size}: ${stepTitle(currentStep)}",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        when (currentStep) {
                            SetupStep.CONNECTION -> {
                                Text(
                                    text = "接続先のサーバとアクセストークンを入力します。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                HostField(value = host, onValueChange = { host = it })
                                TokenField(value = accessToken, onValueChange = { accessToken = it })
                                PortField(value = port, onValueChange = { port = it })
                            }

                            SetupStep.DEVICE -> {
                                Text(
                                    text = "この端末の表示名を入力します。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                DeviceNameField(value = deviceName, onValueChange = { deviceName = it })
                            }

                            SetupStep.KEY -> {
                                Text(
                                    text = "全端末で共有する暗号鍵を作成します。作成した鍵は次のステップで QR として配布します。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                KeyStatusText(hasKey = hasKey, keyId = keyId)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = { if (hasKey) showRotateWarning = true else rotateKey() },
                                        modifier = Modifier.testTag(TAG_ROTATE),
                                    ) {
                                        Text(text = "鍵を作る")
                                    }
                                }
                            }

                            SetupStep.PAIRING -> {
                                Text(
                                    text = "QR を表示し、他の端末のカメラで読み取ってペアリングします。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                OutlinedButton(
                                    onClick = { showPairingQr() },
                                    modifier = Modifier.testTag(TAG_ADD_DEVICE),
                                ) {
                                    Text(text = "QR を表示する")
                                }
                                pairingUri?.let { uri ->
                                    PairingQrSection(
                                        uri = uri,
                                        qrContent = qrContent,
                                        onCopyPairingUri = onCopyPairingUri,
                                        onCopied = { statusMessage = "ペアリング文字列をコピーしました。" },
                                        onHide = { pairingUri = null },
                                    )
                                }
                            }
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

            if (!setupComplete) {
                val wizardBack: (() -> Unit)? = when (currentStep) {
                    SetupStep.CONNECTION -> null
                    else -> ({ currentStep = SetupWizard.previous(currentStep, SetupRole.SENDER)!! })
                }
                val wizardNext: (() -> Unit)? = when (currentStep) {
                    SetupStep.CONNECTION, SetupStep.DEVICE -> ({ saveConnection(); refreshWizard() })
                    SetupStep.KEY -> if (hasKey) {
                        { currentStep = SetupWizard.next(SetupStep.KEY, SetupRole.SENDER)!! }
                    } else {
                        null
                    }
                    SetupStep.PAIRING -> null
                }
                WizardNavigation(
                    onBack = wizardBack,
                    onNext = wizardNext,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
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

/** ウィザードのステップ見出しに使う日本語ラベル。 */
private fun stepTitle(step: SetupStep): String =
    when (step) {
        SetupStep.CONNECTION -> "接続設定"
        SetupStep.DEVICE -> "端末名"
        SetupStep.KEY -> "共有鍵の作成"
        SetupStep.PAIRING -> "端末の追加"
    }

@Composable
private fun HostField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("サーバホスト名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TAG_HOST),
    )
}

@Composable
private fun TokenField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("アクセストークン") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TAG_TOKEN),
    )
}

@Composable
private fun DeviceNameField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("端末名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TAG_DEVICE_NAME),
    )
}

@Composable
private fun PortField(value: String, onValueChange: (String) -> Unit) {
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
private fun LabeledCheckbox(
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
private fun KeyStatusText(hasKey: Boolean, keyId: String?) {
    Text(
        text = if (hasKey) "共有鍵: 設定済み（keyId=${keyId ?: "?"}）" else "共有鍵: 未設定",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

/** ウィザードの「戻る」「次へ」ボタン行（右寄せ）。ハンドラが null のボタンは表示しない。 */
@Composable
private fun WizardNavigation(
    onBack: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        if (onBack != null) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag(TAG_WIZARD_BACK)) {
                Text(text = "戻る")
            }
        }
        if (onNext != null) {
            Button(onClick = onNext, modifier = Modifier.testTag(TAG_WIZARD_NEXT)) {
                Text(text = "次へ")
            }
        }
    }
}

/** QR 表示ブロック（案内文・QR・コピー・非表示）。フラット画面とウィザード PAIRING で共有する。 */
@Composable
private fun PairingQrSection(
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

const val TAG_HOST: String = "settings-host"
const val TAG_TOKEN: String = "settings-token"
const val TAG_DEVICE_NAME: String = "settings-deviceName"
const val TAG_PORT: String = "settings-port"
const val TAG_PERSIST_SENSITIVE: String = "settings-persist-sensitive"
const val TAG_ATTACH_FULL_TEXT: String = "settings-attach-full-text"
const val TAG_SEND_ENABLED: String = "settings-send-enabled"
const val TAG_SMS_DIRECT_RECEIVE: String = "settings-sms-direct-receive"
const val TAG_SAVE: String = "settings-save"
const val TAG_ROTATE: String = "settings-rotate"
const val TAG_ROTATE_CONFIRM: String = "settings-rotate-confirm"
const val TAG_ADD_DEVICE: String = "settings-add-device"
const val TAG_HIDE_QR: String = "settings-hide-qr"
const val TAG_COPY_PAIRING_URI: String = "settings-copy-pairing-uri"
const val TAG_STATUS: String = "settings-status"
const val TAG_WIZARD_NEXT: String = "settings-wizard-next"
const val TAG_WIZARD_BACK: String = "settings-wizard-back"
