package to.sava.peranta.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.nowEpochMillis

/** 添付カードのダウンロードボタンのタグ接頭辞（末尾に blobId を付ける）。 */
const val TAG_ATTACHMENT_DOWNLOAD_PREFIX: String = "attachment-download-"

/** 添付カードのキャンセルボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_CANCEL_PREFIX: String = "attachment-cancel-"

/** 添付カードの「開く」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_OPEN_PREFIX: String = "attachment-open-"

/** 添付カードの「保存」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_SAVE_PREFIX: String = "attachment-save-"

/** 添付カードの「再試行」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_RETRY_PREFIX: String = "attachment-retry-"

/** 添付カードの進捗バーのタグ接頭辞。 */
const val TAG_ATTACHMENT_PROGRESS_PREFIX: String = "attachment-progress-"

/** 添付カードの期限切れ表示のタグ接頭辞。 */
const val TAG_ATTACHMENT_EXPIRED_PREFIX: String = "attachment-expired-"

/**
 * 1 添付のダウンロード状態（§4.3）。プラットフォーム配線（DesktopReceiver 等）が blobId 毎に公開する。
 * [progress] は進行中・終了状態、[cached] は復号済みファイルがキャッシュにあるか、
 * [thumbnail] は画像を復号・デコードできたときのサムネイル。
 */
data class AttachmentDownloadState(
    val progress: TransferProgress? = null,
    val cached: Boolean = false,
    val thumbnail: ImageBitmap? = null,
)

/**
 * 受信した添付に対する操作束（§4.3、§10.1）。
 * [states] は blobId 毎のダウンロード状態で、カードはこれを購読して状態別 UI を出す。
 * 各操作は fire-and-forget で、プラットフォーム配線が実際のダウンロード・保存を行う。
 * 既定は no-op / 空状態で、添付操作を持たない画面ではダウンロード導線を出さない。
 */
class AttachmentUi(
    val states: StateFlow<Map<String, AttachmentDownloadState>>,
    val onDownload: (AttachmentRef) -> Unit = {},
    val onCancel: (blobId: String) -> Unit = {},
    val onOpen: (blobId: String) -> Unit = {},
    val onSave: (blobId: String) -> Unit = {},
    val now: () -> Long = ::nowEpochMillis,
)

/** バイト数を人間向けの短い表現にする（KB/MB 単位、1 桁小数）。 */
internal fun formatFileSize(bytes: Long): String {
    val kib = 1024.0
    val mib = kib * 1024
    return when {
        bytes < kib -> "$bytes B"
        bytes < mib -> "${((bytes / kib) * 10).toInt() / 10.0} KB"
        else -> "${((bytes / mib) * 10).toInt() / 10.0} MB"
    }
}

/** 添付種別のフォールバックアイコン（サムネイルが無いときに出す）。 */
private fun kindGlyph(kind: AttachmentKind): String = when (kind) {
    AttachmentKind.IMAGE -> "🖼"
    AttachmentKind.FILE -> "📄"
}

/**
 * 1 件の添付を表すカード（§4.3）。状態別にボタンを出し分ける:
 * 未取得=ダウンロード / 進行中=進捗バー+キャンセル / 完了=開く+保存 / 失敗=再試行 / 期限切れ=無効表示。
 */
@Composable
internal fun AttachmentCard(ref: AttachmentRef, ui: AttachmentUi) {
    val states by ui.states.collectAsState()
    val state = states[ref.blobId] ?: AttachmentDownloadState()
    val expired = !state.cached && isBlobExpired(ref, ui.now())

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        state.thumbnail?.let { thumbnail ->
            Image(
                bitmap = thumbnail,
                contentDescription = ref.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            )
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (state.thumbnail == null) {
                Text(text = kindGlyph(ref.kind), modifier = Modifier.padding(end = 6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ref.fileName,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatFileSize(ref.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AttachmentControls(ref, ui, state, expired)
    }
}

@Composable
private fun AttachmentControls(
    ref: AttachmentRef,
    ui: AttachmentUi,
    state: AttachmentDownloadState,
    expired: Boolean,
) {
    val progress = state.progress
    when {
        state.cached -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { ui.onOpen(ref.blobId) },
                modifier = Modifier.testTag("$TAG_ATTACHMENT_OPEN_PREFIX${ref.blobId}"),
            ) { Text("開く") }
            TextButton(
                onClick = { ui.onSave(ref.blobId) },
                modifier = Modifier.testTag("$TAG_ATTACHMENT_SAVE_PREFIX${ref.blobId}"),
            ) { Text("保存") }
        }

        progress?.state == TransferState.RUNNING || progress?.state == TransferState.PENDING -> Column {
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$TAG_ATTACHMENT_PROGRESS_PREFIX${ref.blobId}"),
            )
            TextButton(
                onClick = { ui.onCancel(ref.blobId) },
                modifier = Modifier.testTag("$TAG_ATTACHMENT_CANCEL_PREFIX${ref.blobId}"),
            ) { Text("キャンセル (${progress.percent}%)") }
        }

        progress?.state == TransferState.FAILED -> TextButton(
            onClick = { ui.onDownload(ref) },
            modifier = Modifier.testTag("$TAG_ATTACHMENT_RETRY_PREFIX${ref.blobId}"),
        ) { Text("再試行") }

        expired -> TextButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.testTag("$TAG_ATTACHMENT_EXPIRED_PREFIX${ref.blobId}"),
        ) { Text("期限切れ") }

        else -> TextButton(
            onClick = { ui.onDownload(ref) },
            modifier = Modifier.testTag("$TAG_ATTACHMENT_DOWNLOAD_PREFIX${ref.blobId}"),
        ) { Text("ダウンロード") }
    }
}

/** サーバ側の添付保持期限を過ぎているか（過ぎているとダウンロード不可）。 */
private fun isBlobExpired(ref: AttachmentRef, now: Long): Boolean {
    val expiresAt = ref.blobExpiresAtEpochMillis ?: return false
    return expiresAt < now
}
