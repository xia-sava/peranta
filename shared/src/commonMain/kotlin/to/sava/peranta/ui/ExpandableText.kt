package to.sava.peranta.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.FilePayload
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.SmsPayload

/** 自動展開する本文のタグ接頭辞（末尾に blobId を付ける）。 */
const val TAG_FULL_TEXT_PREFIX: String = "full-text-"

/**
 * 全文添付（kind=TEXT、§4.3）の自動取得口。プラットフォーム配線が添付キャッシュ経由で実装する。
 * [fetchFullText] は表示時に呼ばれ、成功で全文文字列、取得失敗（オフライン・期限切れ等）で null を返す。
 * キャンセル以外の例外は握って null を返す契約とし、画面をエラー表示に落とさない。
 */
class FullTextUi(
    val fetchFullText: suspend (AttachmentRef) -> String?,
)

/** payload に含まれる全文添付（kind=TEXT）を返す。無ければ null。 */
internal fun Payload.fullTextAttachment(): AttachmentRef? =
    attachmentsOf(this).firstOrNull { it.kind == AttachmentKind.TEXT }

private fun attachmentsOf(payload: Payload): List<AttachmentRef> = when (payload) {
    is NotificationPayload -> payload.attachments
    is SmsPayload -> payload.attachments
    is FilePayload -> payload.attachments
    else -> emptyList()
}

/**
 * 切り詰めプレビュー [preview] を表示し、表示された時点で [ref] の全文添付を自動取得して差し替える（§4.3）。
 * ボタンは無く、コンポーズされると取得が始まる。取得中・取得失敗はプレビューのまま据え置く。
 */
@Composable
internal fun ExpandableText(
    preview: String,
    ref: AttachmentRef,
    fullText: FullTextUi,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(ref.blobId) { mutableStateOf<String?>(null) }
    LaunchedEffect(ref.blobId) {
        expanded = try {
            fullText.fetchFullText(ref)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
    LinkifiedText(
        text = expanded ?: preview,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.testTag("$TAG_FULL_TEXT_PREFIX${ref.blobId}"),
    )
}
