package to.sava.peranta.ui.setup

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 画面冒頭の説明文（未達でも操作を妨げず、いつでも設定できる旨を示す）。 */
private const val RECEIVE_SETUP_DESCRIPTION: String =
    "他の端末から届く通知を受け取るための設定です。上から順に確認してください。" +
        "未達の手順もいつでも設定できます。"

/** 操作直後に反映を追いかける自動再チェックの回数と間隔（[HealthCheckScreen] と同じ型）。 */
private const val RECHECK_COUNT: Int = 3
private const val RECHECK_INTERVAL_MILLIS: Long = 2_000L

/**
 * 受信のセットアップ常設画面。[provider] が返す 5 手順を [SetupChecklist] の常設モードで描き、
 * 状態に依らず同じ位置に同じ道具（コピーチップ・主操作）を置く。
 *
 * [externalRefreshKey] が変わると再読込する。Android は画面復帰（ON_RESUME）ごとにこの値を進め、
 * ntfy やシステム設定から戻った直後の状態を反映する。手順内の操作を実行したあとは、非同期に反映される
 * 状態（UnifiedPush 登録・受信テスト）を追って数回だけ自動再チェックする。
 * [onCopyText] はコピーチップで使うコピー処理で、null なら端末のクリップボードへフォールバックする。
 */
@Composable
fun ReceiveSetupScreen(
    provider: SetupItemsProvider,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    externalRefreshKey: Int = 0,
    onCopyText: ((text: String, sensitive: Boolean) -> Unit)? = null,
) {
    var manualRefresh by remember { mutableStateOf(0) }
    var followUpRechecks by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<SetupItemUi>?>(null) }

    LaunchedEffect(externalRefreshKey, manualRefresh) {
        items = provider.items()
    }

    // 操作の結果が非同期に反映される手順（UnifiedPush 登録・受信テスト）を追いかけて数回だけ再チェックする。
    LaunchedEffect(followUpRechecks) {
        if (followUpRechecks <= 0) return@LaunchedEffect
        delay(RECHECK_INTERVAL_MILLIS)
        manualRefresh++
        followUpRechecks--
    }

    fun onActionInvoked() {
        manualRefresh++
        followUpRechecks = RECHECK_COUNT
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onBack = onBack)
            Text(
                text = RECEIVE_SETUP_DESCRIPTION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val loaded = items
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag(TAG_RECEIVE_SETUP_LOADING))
                }
            } else {
                SetupChecklist(
                    items = withRecheckOnAction(loaded, ::onActionInvoked),
                    mode = SetupChecklistMode.STANDING,
                    onCopyText = onCopyText,
                )
            }

            OutlinedButton(
                onClick = { manualRefresh++ },
                modifier = Modifier.testTag(TAG_RECEIVE_SETUP_RECHECK),
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
            text = "受信のセットアップ",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(TAG_RECEIVE_SETUP_TITLE),
        )
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(TAG_RECEIVE_SETUP_BACK)) {
                Text(text = "戻る")
            }
        }
    }
}

/** 画面タイトルのタグ。 */
const val TAG_RECEIVE_SETUP_TITLE: String = "receive-setup-title"

/** 戻る導線のタグ。 */
const val TAG_RECEIVE_SETUP_BACK: String = "receive-setup-back"

/** 「今すぐ再チェック」ボタンのタグ。 */
const val TAG_RECEIVE_SETUP_RECHECK: String = "receive-setup-recheck"

/** 読込中インジケータのタグ。 */
const val TAG_RECEIVE_SETUP_LOADING: String = "receive-setup-loading"
