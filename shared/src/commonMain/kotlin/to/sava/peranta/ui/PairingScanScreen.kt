package to.sava.peranta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import to.sava.peranta.pairing.PairingImportController
import to.sava.peranta.pairing.PairingImportResult

/** 端末名を入れずに取り込んだときの警告文（設定画面で後から入力できる）。 */
private const val PAIRING_DEVICE_NAME_MISSING_NOTICE: String =
    "端末名が未設定です。後で設定画面から入力してください。"

/**
 * QR ペアリング取り込み画面（§10.3）。設定元端末が表示した QR を読み取るか、
 * ペアリング文字列を手動で貼り付けて設定一式を取り込む。
 *
 * カメラ起動はプラットフォーム依存のため [onRequestScan] スロットで注入する。
 * 注入されたスキャナが読み取り結果（生文字列、キャンセル時は null）をコールバックへ返すと、
 * 手動貼り付けと同じ経路で復号・適用する。[onRequestScan] が null のときはスキャンボタンを出さず、
 * 手動貼り付けのみで動作する（カメラ非対応環境・ヘッドレステスト向け）。
 * 端末名欄が空欄なら端末名は既存値を引き継ぐ（§6）。[onOpenSettings] が非 null のときは、
 * この端末自身を設定元にするための設定画面への導線を表示する（取り込み成功後は隠す）。
 * [onOpenWizard] が非 null のときは、セットアップをページ列で案内するウィザードへ戻る導線を表示する
 * （取り込み成功後は隠す）。取り込み成功後は [onImported] が非 null ならタイムラインへ進む導線を表示する。
 * [showHeader] が false のときは画面見出しと概要説明を出さない。外側（ウィザードのページ）が
 * 見出しを持つ埋め込み利用で使い、既定の true では従来どおり見出しつきの単独画面として振る舞う。
 */
@Composable
fun PairingScanScreen(
    controller: PairingImportController,
    modifier: Modifier = Modifier,
    onRequestScan: ((onResult: (String?) -> Unit) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenWizard: (() -> Unit)? = null,
    onImported: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    showHeader: Boolean = true,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PairingScanContent(
                controller = controller,
                onRequestScan = onRequestScan,
                onOpenSettings = onOpenSettings,
                onOpenWizard = onOpenWizard,
                onImported = onImported,
                onBack = onBack,
                showHeader = showHeader,
            )
        }
    }
}

/**
 * QR 取り込みの中身（見出し・スキャン・貼り付け・状態表示・各導線）だけを描く。
 * スクロールコンテナ・[Surface] を持たないため、外側にスクロール可能な [Column] を持つ画面
 * （ウィザードの QR 取り込みページなど）へそのまま埋め込める。単独画面は [PairingScanScreen] が包む。
 */
@Composable
internal fun PairingScanContent(
    controller: PairingImportController,
    onRequestScan: ((onResult: (String?) -> Unit) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenWizard: (() -> Unit)? = null,
    onImported: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    showHeader: Boolean = true,
    onApplied: (() -> Unit)? = null,
) {
    var manualInput by remember { mutableStateOf("") }
    var deviceNameInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var succeeded by remember { mutableStateOf(false) }

    fun importRaw(raw: String) {
        val deviceName = deviceNameInput.ifBlank { null }
        when (val result = controller.import(raw, deviceName)) {
            is PairingImportResult.Applied -> {
                succeeded = true
                manualInput = ""
                statusMessage = if (deviceName == null) {
                    "設定を取り込みました（keyId=${result.keyId}）。$PAIRING_DEVICE_NAME_MISSING_NOTICE"
                } else {
                    "設定を取り込みました（keyId=${result.keyId}）。"
                }
                onApplied?.invoke()
            }

            is PairingImportResult.Failed -> {
                succeeded = false
                statusMessage = result.reason
            }
        }
    }

    if (showHeader) {
        Text(text = "設定の取り込み", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "設定元の端末が表示した QR を読み取ると、サーバ・トークン・共有鍵をまとめて取り込みます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (onRequestScan != null) {
        Button(
            onClick = { onRequestScan { scanned -> scanned?.let(::importRaw) } },
            modifier = Modifier.testTag(TAG_PAIRING_SCAN),
        ) {
            Text(text = "QR をスキャン")
        }
    }

    OutlinedTextField(
        value = deviceNameInput,
        onValueChange = { deviceNameInput = it },
        label = { Text("端末名（任意）") },
        modifier = Modifier.fillMaxWidth().testTag(TAG_PAIRING_DEVICE_NAME),
    )

    Text(
        text = "カメラが使えないときは、ペアリング文字列を貼り付けて取り込めます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = manualInput,
        onValueChange = { manualInput = it },
        label = { Text("ペアリング文字列") },
        modifier = Modifier.fillMaxWidth().testTag(TAG_PAIRING_MANUAL_INPUT),
    )
    Button(
        onClick = { importRaw(manualInput) },
        modifier = Modifier.testTag(TAG_PAIRING_IMPORT),
    ) {
        Text(text = "取り込む")
    }

    statusMessage?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(TAG_PAIRING_STATUS),
        )
    }

    if (succeeded && onImported != null) {
        Button(onClick = onImported, modifier = Modifier.testTag(TAG_PAIRING_IMPORTED)) {
            Text(text = "タイムラインへ")
        }
    }

    if (!succeeded && onOpenWizard != null) {
        TextButton(onClick = onOpenWizard, modifier = Modifier.testTag(TAG_PAIRING_OPEN_WIZARD)) {
            Text(text = "ウィザードで設定する")
        }
    }

    if (!succeeded && onOpenSettings != null) {
        TextButton(onClick = onOpenSettings, modifier = Modifier.testTag(TAG_PAIRING_OPEN_SETTINGS)) {
            Text(text = "この端末を設定元にする")
        }
    }

    if (onBack != null) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(TAG_PAIRING_BACK)) {
            Text(text = "戻る")
        }
    }
}

const val TAG_PAIRING_SCAN: String = "pairing-scan"
const val TAG_PAIRING_MANUAL_INPUT: String = "pairing-manual-input"
const val TAG_PAIRING_DEVICE_NAME: String = "pairing-device-name"
const val TAG_PAIRING_IMPORT: String = "pairing-import"
const val TAG_PAIRING_STATUS: String = "pairing-status"
const val TAG_PAIRING_IMPORTED: String = "pairing-imported"
const val TAG_PAIRING_OPEN_SETTINGS: String = "pairing-open-settings"
const val TAG_PAIRING_OPEN_WIZARD: String = "pairing-open-wizard"
const val TAG_PAIRING_BACK: String = "pairing-back"
