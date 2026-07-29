package to.sava.peranta.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import to.sava.peranta.blob.AutoFetchRole
import to.sava.peranta.blob.exceedsAutoFetchLimit
import to.sava.peranta.blob.shouldAutoFetch
import to.sava.peranta.model.AttachmentRef

/** 送信者アイコンのタグ接頭辞（末尾に blobId を付ける）。 */
const val TAG_SENDER_ICON_PREFIX: String = "sender-icon-"

/** 上限超過で取得しなかった送信者アイコンの跡のタグ接頭辞（末尾に blobId を付ける）。 */
const val TAG_SENDER_ICON_SKIPPED_PREFIX: String = "sender-icon-skipped-"

/** ヘッダに出す送信者アイコンの一辺（dp）。ヘッダ 1 行の高さに収まる大きさにする。 */
private val SENDER_ICON_SIZE = 20.dp

/** 取得しなかった送信者アイコンの跡に出す字形。 */
private const val SKIPPED_SENDER_ICON_GLYPH: String = "🖼"

/**
 * バブルのヘッダに出す送信者アイコン（§4.3.1）。
 *
 * 画面に出た時点で未取得なら 1 回だけ取得を促す。取得してよいかの判断は [shouldAutoFetch] に委ねる。
 * 数 KB と小さいうえ手動取得の導線を持たないため、画像添付の自動表示トグル
 * （[AttachmentUi.autoDisplayImages]）とは無関係に取得する。
 * 失敗後の自動リトライは行わない（進捗が残るため対象から外れる）。
 */
@Composable
internal fun SenderIcon(ref: AttachmentRef, ui: AttachmentUi) {
    val states by ui.states.collectAsState()
    val state = states[ref.blobId] ?: AttachmentDownloadState()

    LaunchedEffect(ref.blobId) {
        val autoFetch = shouldAutoFetch(
            ref = ref,
            role = AutoFetchRole.SENDER_ICON,
            autoDisplayImages = ui.autoDisplayImages,
            now = ui.now(),
            alreadyFetched = state.cached,
            transferStarted = state.progress != null,
        )
        if (autoFetch) ui.onDownload(ref)
    }

    val thumbnail = state.thumbnail
    if (thumbnail == null) {
        if (exceedsAutoFetchLimit(ref, AutoFetchRole.SENDER_ICON)) SkippedSenderIcon(ref)
        return
    }
    Image(
        bitmap = thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(SENDER_ICON_SIZE)
            .clip(CircleShape)
            .testTag("$TAG_SENDER_ICON_PREFIX${ref.blobId}"),
    )
}

/**
 * 上限を超えるため取得しなかった送信者アイコンの跡（§4.3.1）。
 * 送信者アイコンには手動取得の導線が無く、何も描かないと「アイコンを持たない通知」と区別が付かない。
 * 同じ大きさの枠へ薄い字形を置き、アイコンが省かれたことがヘッダから読み取れるようにする。
 */
@Composable
private fun SkippedSenderIcon(ref: AttachmentRef) {
    Text(
        text = SKIPPED_SENDER_ICON_GLYPH,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(SENDER_ICON_SIZE)
            .testTag("$TAG_SENDER_ICON_SKIPPED_PREFIX${ref.blobId}"),
    )
}
