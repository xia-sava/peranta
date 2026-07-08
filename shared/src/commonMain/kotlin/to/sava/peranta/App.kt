package to.sava.peranta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.PerantaTheme
import to.sava.peranta.ui.TimelineScreen
import to.sava.peranta.ui.UpdateBanner
import to.sava.peranta.update.UpdateController

/** タイムラインを表示するアプリ本体。更新導線が渡されたときは上部にバナーを出す。 */
@Composable
fun App(
    items: StateFlow<List<TimelineItem>>,
    updateController: UpdateController? = null,
    onInstallUpdate: ((String) -> Unit)? = null,
    receiveEndpoint: String? = null,
) {
    PerantaTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            if (updateController != null && onInstallUpdate != null) {
                UpdateBanner(updateController, onInstallUpdate)
            }
            if (receiveEndpoint != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        text = "受信エンドポイント: $receiveEndpoint",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                TimelineScreen(items)
            }
        }
    }
}

/** 設定が未完了のときに表示する画面。設定 UI 自体は後続マイルストーンで実装する。 */
@Composable
fun App() {
    PerantaTheme {
        MessageScreen(
            title = "設定が必要です",
            body = "サーバ・トークン・共有鍵・端末名を設定すると通知の受信を開始します。",
        )
    }
}

/** 受信が続行不能なエラーで停止したときに表示する画面。 */
@Composable
fun App(errorMessage: String) {
    PerantaTheme {
        MessageScreen(title = "エラー", body = errorMessage)
    }
}

/** 送信ロールの状態を簡易表示する（本格 UI は後続マイルストーン）。 */
@Composable
fun SendRoleApp(
    sendEnabled: Boolean,
    updateController: UpdateController? = null,
    onInstallUpdate: ((String) -> Unit)? = null,
) {
    PerantaTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            if (updateController != null && onInstallUpdate != null) {
                UpdateBanner(updateController, onInstallUpdate)
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = if (sendEnabled) "通知を送信する: 有効" else "通知を送信する: 無効",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                MessageScreen(
                    title = "Peranta 送信端末",
                    body = "設定が完了すると通知と SMS の転送を開始します。",
                )
            }
        }
    }
}

@Composable
private fun MessageScreen(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
