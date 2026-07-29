package to.sava.peranta.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import to.sava.peranta.blob.AttachmentCategory
import to.sava.peranta.blob.AttachmentOpenDecision
import to.sava.peranta.blob.AutoFetchRole
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.blob.attachmentCategoryFor
import to.sava.peranta.blob.attachmentOpenDecision
import to.sava.peranta.blob.isBlobExpired
import to.sava.peranta.blob.shouldAutoFetch
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.nowEpochMillis

/** 添付カードのダウンロードボタンのタグ接頭辞（末尾に blobId を付ける）。 */
const val TAG_ATTACHMENT_DOWNLOAD_PREFIX: String = "attachment-download-"

/** 添付カードのキャンセルボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_CANCEL_PREFIX: String = "attachment-cancel-"

/** 添付カードの「開く」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_OPEN_PREFIX: String = "attachment-open-"

/** 開く前の確認ダイアログの「開く」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_OPEN_CONFIRM_PREFIX: String = "attachment-open-confirm-"

/** 開く前の確認ダイアログの「キャンセル」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_OPEN_CANCEL_PREFIX: String = "attachment-open-cancel-"

/** 開く導線を出さない添付に添える案内のタグ接頭辞。 */
const val TAG_ATTACHMENT_OPEN_REFUSED_PREFIX: String = "attachment-open-refused-"

/** 添付カードの「保存」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_SAVE_PREFIX: String = "attachment-save-"

/** 添付カードの「共有」ボタンのタグ接頭辞。 */
const val TAG_ATTACHMENT_SHARE_PREFIX: String = "attachment-share-"

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
 * [autoDisplayImages] が true のとき、画像添付はカードが画面に出た時点で自動ダウンロードする（§4.3）。
 */
class AttachmentUi(
    val states: StateFlow<Map<String, AttachmentDownloadState>>,
    val onDownload: (AttachmentRef) -> Unit = {},
    val onCancel: (blobId: String) -> Unit = {},
    val onOpen: (blobId: String) -> Unit = {},
    val onSave: (blobId: String) -> Unit = {},
    val onShare: (blobId: String) -> Unit = {},
    val canShare: Boolean = false,
    val now: () -> Long = ::nowEpochMillis,
    val autoDisplayImages: Boolean = false,
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

/** 添付種別のフォールバックアイコン（サムネイルが無いときに出す）。mimeType と拡張子から簡易分類する。 */
private fun categoryGlyph(ref: AttachmentRef): String =
    when (attachmentCategoryFor(ref.mimeType, ref.fileName)) {
        AttachmentCategory.IMAGE -> "🖼"
        AttachmentCategory.VIDEO -> "🎞"
        AttachmentCategory.AUDIO -> "🎵"
        AttachmentCategory.DOCUMENT -> "📄"
        AttachmentCategory.OTHER -> "📎"
    }

/**
 * 1 件の添付を表すカード（§4.3）。状態別にボタンを出し分ける:
 * 未取得=ダウンロード（画像は「表示」） / 進行中=進捗バー+キャンセル / 完了=開く+保存 / 失敗=再試行 /
 * 期限切れ=無効表示。
 * 画像添付は、カードが画面に出た時点（[ref.blobId] のコンポーズ時点）で未キャッシュ・進捗なし・期限内・
 * [AttachmentUi.autoDisplayImages] が true なら自動で 1 回だけダウンロードを発火する（§4.3）。
 * 失敗後の自動リトライは行わない（進捗なしの条件により、失敗状態は対象から外れる）。
 */
@Composable
internal fun AttachmentCard(ref: AttachmentRef, ui: AttachmentUi) {
    val states by ui.states.collectAsState()
    val state = states[ref.blobId] ?: AttachmentDownloadState()
    val expired = !state.cached && isBlobExpired(ref, ui.now())

    LaunchedEffect(ref.blobId) {
        val autoFetch = shouldAutoFetch(
            ref = ref,
            role = AutoFetchRole.DISPLAY_IMAGE,
            autoDisplayImages = ui.autoDisplayImages,
            now = ui.now(),
            alreadyFetched = state.cached,
            transferStarted = state.progress != null,
        )
        if (autoFetch) ui.onDownload(ref)
    }

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
                Text(text = categoryGlyph(ref), modifier = Modifier.padding(end = 6.dp))
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

/**
 * 添付を開くボタンのラベル。同じバブルには送信元アプリの通知アクションも並ぶため、何が開くのかを
 * ラベルに含めて取り違えを防ぐ（§10.1）。
 */
private fun openLabelFor(ref: AttachmentRef): String =
    if (attachmentCategoryFor(ref.mimeType, ref.fileName) == AttachmentCategory.IMAGE) {
        "画像を開く"
    } else {
        "ファイルを開く"
    }

/** 添付を保存するボタンのラベル。開くボタンと同じ理由で、対象を含める。 */
private const val ATTACHMENT_SAVE_LABEL: String = "ファイルに保存"

/** 開く導線を出さない添付に添える案内。保存という代替の導線を示す。 */
internal const val REFUSED_OPEN_MESSAGE: String = "この種類のファイルは開けません。保存してから開いてください。"

/** 開く前の確認ダイアログの見出し。 */
private const val CONFIRM_OPEN_TITLE: String = "他の端末から届いたファイルです"

@Composable
private fun AttachmentControls(
    ref: AttachmentRef,
    ui: AttachmentUi,
    state: AttachmentDownloadState,
    expired: Boolean,
) {
    val progress = state.progress
    when {
        state.cached -> CachedAttachmentControls(ref, ui)

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
        ) {
            val isImage = attachmentCategoryFor(ref.mimeType, ref.fileName) == AttachmentCategory.IMAGE
            Text(if (isImage) "表示" else "ダウンロード")
        }
    }
}

/**
 * 取得済みの添付に出す操作（§4.3）。「開く」は復号済みファイルを OS の既定アプリへ渡すため、
 * 渡してよいかを [attachmentOpenDecision] で決めてから出し分ける（§4.3.2）。
 * 保存・共有は OS のダイアログでユーザーが宛先を選ぶ経路なので、判定に関わらず常に出す。
 */
@Composable
private fun CachedAttachmentControls(ref: AttachmentRef, ui: AttachmentUi) {
    var confirming by remember(ref.blobId) { mutableStateOf(false) }
    val decision = attachmentOpenDecision(ref.mimeType, ref.fileName)
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (decision) {
                AttachmentOpenDecision.OPEN -> OpenButton(ref) { ui.onOpen(ref.blobId) }
                AttachmentOpenDecision.CONFIRM -> OpenButton(ref) { confirming = true }
                AttachmentOpenDecision.REFUSE -> Unit
            }
            TextButton(
                onClick = { ui.onSave(ref.blobId) },
                modifier = Modifier.testTag("$TAG_ATTACHMENT_SAVE_PREFIX${ref.blobId}"),
            ) { Text(ATTACHMENT_SAVE_LABEL) }
            if (ui.canShare) {
                TextButton(
                    onClick = { ui.onShare(ref.blobId) },
                    modifier = Modifier.testTag("$TAG_ATTACHMENT_SHARE_PREFIX${ref.blobId}"),
                ) { Text("共有") }
            }
        }
        if (decision == AttachmentOpenDecision.REFUSE) {
            Text(
                text = REFUSED_OPEN_MESSAGE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$TAG_ATTACHMENT_OPEN_REFUSED_PREFIX${ref.blobId}"),
            )
        }
    }
    if (confirming) {
        ExternalOriginDialog(
            ref = ref,
            onConfirm = {
                confirming = false
                ui.onOpen(ref.blobId)
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun OpenButton(ref: AttachmentRef, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag("$TAG_ATTACHMENT_OPEN_PREFIX${ref.blobId}"),
    ) { Text(openLabelFor(ref)) }
}

/**
 * 素性を確かめられない種別の添付を開く前に、外部由来である旨を示す確認（§4.3.2）。
 * 種別が判っていて表示するだけの添付（画像・PDF 等）はこの確認を通らないため、
 * 日常の操作で問われ続けて確認が読み飛ばされる状態にはならない。
 */
@Composable
private fun ExternalOriginDialog(ref: AttachmentRef, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CONFIRM_OPEN_TITLE) },
        text = { Text("「${ref.fileName}」を既定のアプリで開きます。心当たりの無いファイルは開かないでください。") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("$TAG_ATTACHMENT_OPEN_CONFIRM_PREFIX${ref.blobId}"),
            ) { Text("開く") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("$TAG_ATTACHMENT_OPEN_CANCEL_PREFIX${ref.blobId}"),
            ) { Text("キャンセル") }
        },
    )
}
