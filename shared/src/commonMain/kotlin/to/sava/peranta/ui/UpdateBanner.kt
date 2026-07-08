package to.sava.peranta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.sava.peranta.update.UpdateController
import to.sava.peranta.update.UpdateStatus

/**
 * 更新確認の導線と結果を控えめに表示するバナー。
 * 「更新を確認」で手動チェックし、更新ありのときは新バージョン名と更新ボタンを出す。
 */
@Composable
fun UpdateBanner(
    controller: UpdateController,
    onInstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by controller.status.collectAsStateWithLifecycle()
    val checking by controller.checking.collectAsStateWithLifecycle()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = statusText(status), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { controller.checkNow() }, enabled = !checking) {
                    Text(text = if (checking) "確認中..." else "更新を確認")
                }
            }
            (status as? UpdateStatus.Available)?.let { available ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "新しいバージョン ${available.versionName}",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Button(onClick = { onInstall(available.url) }) {
                        Text(text = "更新")
                    }
                }
            }
        }
    }
}

private fun statusText(status: UpdateStatus?): String = when (status) {
    null -> "更新を確認できます"
    UpdateStatus.UpToDate -> "最新のバージョンです"
    is UpdateStatus.Available -> "更新があります"
    is UpdateStatus.Failed -> "更新確認に失敗しました: ${status.reason}"
}
