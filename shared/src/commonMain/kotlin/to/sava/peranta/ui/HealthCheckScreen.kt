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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 画面冒頭の説明文（強制ブロックせず、後でも直せる旨を明示する、§10.5）。 */
private const val HEALTH_DESCRIPTION: String =
    "通知の受信・転送に必要な権限や設定を点検します。未達の項目は「直す」から設定できます。" +
        "そのまま利用を続けることもできます。"

/** 全項目が合格・対象外だったときの案内文。 */
private const val HEALTH_ALL_CLEAR: String = "点検した項目はすべて問題ありません。"

/** 「直す」操作が例外を送出したが、メッセージを持たない場合の既定文。 */
private const val HEALTH_FIX_FAILED_DEFAULT: String = "操作に失敗しました。"

/** 「直す」操作が成功した直後に項目へ添える案内文。 */
private const val HEALTH_FIX_DONE: String = "操作を実行しました。"

/** 案内ダイアログの補助コピー操作を押した直後に添える案内文。 */
private const val HEALTH_FIX_AID_COPIED: String = "コピーしました"

/** 「直す」実行後に反映を追いかける自動再チェックの回数と間隔。 */
private const val FIX_RECHECK_COUNT: Int = 3
private const val FIX_RECHECK_INTERVAL_MILLIS: Long = 2_000L

/** オールグリーン表示の背景・文字色（ライト/ダーク共通で「緑=正常」を示す固定色）。 */
private val ALL_CLEAR_CONTAINER: Color = Color(0xFFC8E6C9)
private val ALL_CLEAR_CONTENT: Color = Color(0xFF1B5E20)

/**
 * 健康診断画面（§10.5）。[checker] が返す項目を合格 ✓ / 不合格 ✗ / 情報 ⓘ で描画し、
 * 「直す」導線と「今すぐ再チェック」を提供する。対象外（[HealthCheckState.NOT_APPLICABLE]）項目は出さない。
 *
 * [externalRefreshKey] が変わると再チェックする。Android は画面復帰（ON_RESUME）のたびにこの値を進め、
 * システム設定から戻った直後の状態を反映する。この画面は致命的でない未達でも操作を妨げないため、
 * [onBack] は常に有効にし、そのままメイン画面へ戻れるようにする。
 * [onCopyText] は案内ダイアログの [FixAid.Copy] ボタンで使うコピー処理。null なら
 * [LocalClipboardManager] へフォールバックする。
 */
@Composable
fun HealthCheckScreen(
    checker: HealthChecker,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    externalRefreshKey: Int = 0,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)? = null,
) {
    var manualRefresh by remember { mutableStateOf(0) }
    var followUpRechecks by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<HealthCheckItem>?>(null) }

    LaunchedEffect(externalRefreshKey, manualRefresh) {
        items = checker.check()
    }

    // 「直す」の結果が非同期に反映される項目（UnifiedPush 登録など）を追いかけて数回だけ再チェックする。
    LaunchedEffect(followUpRechecks) {
        if (followUpRechecks <= 0) return@LaunchedEffect
        delay(FIX_RECHECK_INTERVAL_MILLIS)
        manualRefresh++
        followUpRechecks--
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
                    AllClearBanner()
                }
                visible.forEach { item ->
                    // 再チェックで項目の増減・並び替えが起きても行の状態（開いたダイアログ等）を保つ。
                    key(item.id) {
                        HealthItemRow(
                            item = item,
                            onFixed = {
                                manualRefresh++
                                followUpRechecks = FIX_RECHECK_COUNT
                            },
                            onCopyText = onCopyText,
                        )
                    }
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

/** 全項目が合格・対象外のときに出す緑のバナー。合格が一目で判るよう面で塗る。 */
@Composable
private fun AllClearBanner() {
    Surface(
        color = ALL_CLEAR_CONTAINER,
        contentColor = ALL_CLEAR_CONTENT,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(TAG_HEALTH_ALL_CLEAR),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "✓", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = HEALTH_ALL_CLEAR, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HealthItemRow(
    item: HealthCheckItem,
    onFixed: () -> Unit,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)?,
) {
    var fixError by remember(item.id) { mutableStateOf<String?>(null) }
    var fixRequested by remember(item.id, item.state) { mutableStateOf(false) }

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
            // 案内ダイアログは他アプリへの往復（Activity 再生成を含む）をまたいで開いたままにする。
            var guidanceOpen by rememberSaveable(item.id) { mutableStateOf(false) }
            fun executeFix() {
                fixError = runFix(onFix)
                fixRequested = fixError == null
                if (fixError == null) onFixed()
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = {
                        if (item.fixGuidance != null) guidanceOpen = true else executeFix()
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
                if (fixRequested && fixError == null) {
                    Text(
                        text = HEALTH_FIX_DONE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("$TAG_HEALTH_FIX_PENDING_PREFIX${item.id}"),
                    )
                }
            }
            if (guidanceOpen) {
                AlertDialog(
                    onDismissRequest = { guidanceOpen = false },
                    title = { Text(text = item.label) },
                    text = { FixGuidanceContent(item = item, onCopyText = onCopyText) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                guidanceOpen = false
                                executeFix()
                            },
                            modifier = Modifier.testTag("$TAG_HEALTH_FIX_GUIDANCE_OK_PREFIX${item.id}"),
                        ) {
                            Text(text = "続ける")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { guidanceOpen = false }) {
                            Text(text = "やめる")
                        }
                    },
                )
            }
        }
        // 誘導リンクは修復手段を持つ別画面へ渡すだけで、done 表示・自動再チェックは伴わない（onFix と排他）。
        item.link?.let { link ->
            TextButton(
                onClick = link.onOpen,
                modifier = Modifier.testTag("$TAG_HEALTH_LINK_PREFIX${item.id}"),
            ) {
                Text(text = link.label)
            }
        }
    }
}

/**
 * 案内ダイアログの本文。案内文の下に [HealthCheckItem.fixAids] を補助ボタンとして並べる。
 * コピー実行後の「コピーしました」は最後にコピーした行にだけ出す（他の行を押すと表示が移動する）。
 */
@Composable
private fun FixGuidanceContent(
    item: HealthCheckItem,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    var copiedIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = item.fixGuidance.orEmpty())
        item.fixAids.forEachIndexed { index, aid ->
            when (aid) {
                is FixAid.Copy -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = aid.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (copiedIndex == index) {
                        Text(
                            text = HEALTH_FIX_AID_COPIED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            if (onCopyText != null) {
                                onCopyText(aid.value, aid.sensitive)
                            } else {
                                clipboard.setText(AnnotatedString(aid.value))
                            }
                            copiedIndex = index
                        },
                        modifier = Modifier.testTag("$TAG_HEALTH_FIX_AID_PREFIX${item.id}-$index"),
                    ) {
                        Text(text = "コピー")
                    }
                }

                is FixAid.Action -> TextButton(
                    onClick = aid.onRun,
                    modifier = Modifier.testTag("$TAG_HEALTH_FIX_AID_PREFIX${item.id}-$index"),
                ) {
                    Text(text = aid.label)
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
