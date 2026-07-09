package to.sava.peranta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/** 画面冒頭の説明文（強制ブロックせず、後でも直せる旨を明示する、§10.5）。 */
private const val HEALTH_DESCRIPTION: String =
    "通知の受信・転送に必要な権限や設定を点検します。未達の項目は「直す」から設定できます。" +
        "そのまま利用を続けることもできます。"

/** 全項目が合格・対象外だったときの案内文。 */
private const val HEALTH_ALL_CLEAR: String = "点検した項目はすべて問題ありません。"

/** 「直す」操作が例外を送出したが、メッセージを持たない場合の既定文。 */
private const val HEALTH_FIX_FAILED_DEFAULT: String = "操作に失敗しました。"

/**
 * 健康診断画面（§10.5）。[checker] が返す項目を合格 ✓ / 不合格 ✗ / 情報 ⓘ で描画し、
 * 「直す」導線と「今すぐ再チェック」を提供する。対象外（[HealthCheckState.NOT_APPLICABLE]）項目は出さない。
 *
 * [externalRefreshKey] が変わると再チェックする。Android は画面復帰（ON_RESUME）のたびにこの値を進め、
 * システム設定から戻った直後の状態を反映する。この画面は致命的でない未達でも操作を妨げないため、
 * [onBack] は常に有効にし、そのままメイン画面へ戻れるようにする。
 */
@Composable
fun HealthCheckScreen(
    checker: HealthChecker,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    externalRefreshKey: Int = 0,
) {
    var manualRefresh by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<HealthCheckItem>?>(null) }

    LaunchedEffect(externalRefreshKey, manualRefresh) {
        items = checker.check()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(onBack = onBack)
            Text(
                text = HEALTH_DESCRIPTION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val loaded = items
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag(TAG_HEALTH_LOADING))
                }
            } else {
                val visible = loaded.filterNot { it.state == HealthCheckState.NOT_APPLICABLE }
                if (visible.none { it.state == HealthCheckState.FAILING || it.state == HealthCheckState.INFO }) {
                    Text(
                        text = HEALTH_ALL_CLEAR,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(TAG_HEALTH_ALL_CLEAR),
                    )
                }
                visible.forEach { item ->
                    HealthItemRow(item = item, onFixed = { manualRefresh++ })
                }
            }

            OutlinedButton(
                onClick = { manualRefresh++ },
                modifier = Modifier.testTag(TAG_HEALTH_RECHECK),
            ) {
                Text(text = "今すぐ再チェック")
            }
        }
    }
}

@Composable
private fun Header(onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "健康診断",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(TAG_HEALTH_TITLE),
        )
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(TAG_HEALTH_BACK)) {
                Text(text = "戻る")
            }
        }
    }
}

@Composable
private fun HealthItemRow(item: HealthCheckItem, onFixed: () -> Unit) {
    var fixError by remember(item.id) { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = markerFor(item.state),
            color = markerColorFor(item.state),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .testTag("$TAG_HEALTH_STATE_PREFIX${item.id}")
                .padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text = item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            item.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val fixLabel = item.fixLabel
        val onFix = item.onFix
        if (fixLabel != null && onFix != null) {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = {
                        fixError = runFix(onFix)
                        if (fixError == null) onFixed()
                    },
                    modifier = Modifier.testTag("$TAG_HEALTH_FIX_PREFIX${item.id}"),
                ) {
                    Text(text = fixLabel)
                }
                fixError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("$TAG_HEALTH_FIX_ERROR_PREFIX${item.id}"),
                    )
                }
            }
        }
    }
}

/** [onFix] を実行し、失敗すれば表示用のエラー文を、成功すれば null を返す。 */
private fun runFix(onFix: () -> Unit): String? =
    try {
        onFix()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        error.message ?: HEALTH_FIX_FAILED_DEFAULT
    }

/** 状態を表すマーカー記号。合格 ✓ / 不合格 ✗ / 情報 ⓘ。 */
private fun markerFor(state: HealthCheckState): String = when (state) {
    HealthCheckState.PASS -> "✓"
    HealthCheckState.FAILING -> "✗"
    HealthCheckState.INFO -> "ⓘ"
    HealthCheckState.NOT_APPLICABLE -> ""
}

@Composable
private fun markerColorFor(state: HealthCheckState): Color = when (state) {
    HealthCheckState.PASS -> MaterialTheme.colorScheme.primary
    HealthCheckState.FAILING -> MaterialTheme.colorScheme.error
    HealthCheckState.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    HealthCheckState.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
}
