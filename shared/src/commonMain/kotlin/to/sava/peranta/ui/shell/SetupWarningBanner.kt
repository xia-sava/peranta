package to.sava.peranta.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 起動時のセットアップ未達を知らせる警告バナーの文言（§10.5）。 */
private const val SETUP_WARNING_MESSAGE: String = "セットアップに未達があります"

/** 警告バナーの確認導線の文言。 */
private const val SETUP_WARNING_ACTION: String = "確認する"

/** 警告バナー全体（タップ領域）のタグ。 */
const val TAG_SETUP_WARNING_BANNER: String = "setup-warning-banner"

/**
 * 起動時のセットアップ未達を知らせる警告バナー（§10.5）。タイムライン上部に置き、タップで
 * [onConfirm] を通じて未達を操作できる画面へ誘導する。誘導先の決定は [setupBannerTarget] が担い、
 * 呼び出し側が渡す。未達が無い・取得中はそもそもこのバナーを出さない。
 */
@Composable
fun SetupWarningBanner(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onConfirm,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().testTag(TAG_SETUP_WARNING_BANNER),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = SETUP_WARNING_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = SETUP_WARNING_ACTION,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
