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

/** 取り込み成功後に再起動を促す文言（設定は次回起動時に反映される）。 */
private const val PAIRING_RESTART_NOTICE: String =
    "変更を反映するにはアプリを再起動してください。"

/**
 * QR ペアリング取り込み画面（§10.3）。設定元端末が表示した QR を読み取るか、
 * ペアリング文字列を手動で貼り付けて設定一式を取り込む。
 *
 * カメラ起動はプラットフォーム依存のため [onRequestScan] スロットで注入する。
 * 注入されたスキャナが読み取り結果（生文字列、キャンセル時は null）をコールバックへ返すと、
 * 手動貼り付けと同じ経路で復号・適用する。[onRequestScan] が null のときはスキャンボタンを出さず、
 * 手動貼り付けのみで動作する（カメラ非対応環境・ヘッドレステスト向け）。
 */
@Composable
fun PairingScanScreen(
    controller: PairingImportController,
    modifier: Modifier = Modifier,
    onRequestScan: ((onResult: (String?) -> Unit) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    var manualInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var succeeded by remember { mutableStateOf(false) }

    fun importRaw(raw: String) {
        when (val result = controller.import(raw)) {
            is PairingImportResult.Applied -> {
                succeeded = true
                manualInput = ""
                statusMessage = "設定を取り込みました（keyId=${result.keyId}）。$PAIRING_RESTART_NOTICE"
            }

            is PairingImportResult.Failed -> {
                succeeded = false
                statusMessage = result.reason
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "設定の取り込み", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "設定元の端末が表示した QR を読み取ると、サーバ・トークン・共有鍵をまとめて取り込みます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (onRequestScan != null) {
                Button(
                    onClick = {
                        onRequestScan { scanned -> scanned?.let(::importRaw) }
                    },
                    modifier = Modifier.testTag(TAG_PAIRING_SCAN),
                ) {
                    Text(text = "QR をスキャン")
                }
            }

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
                    color = if (succeeded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.testTag(TAG_PAIRING_STATUS),
                )
            }

            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.testTag(TAG_PAIRING_BACK)) {
                    Text(text = "戻る")
                }
            }
        }
    }
}

const val TAG_PAIRING_SCAN: String = "pairing-scan"
const val TAG_PAIRING_MANUAL_INPUT: String = "pairing-manual-input"
const val TAG_PAIRING_IMPORT: String = "pairing-import"
const val TAG_PAIRING_STATUS: String = "pairing-status"
const val TAG_PAIRING_BACK: String = "pairing-back"
