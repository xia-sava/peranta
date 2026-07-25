package to.sava.peranta.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import to.sava.peranta.model.AttachmentRef

/** 送信者アイコンのタグ接頭辞（末尾に blobId を付ける）。 */
const val TAG_SENDER_ICON_PREFIX: String = "sender-icon-"

/** ヘッダに出す送信者アイコンの一辺（dp）。ヘッダ 1 行の高さに収まる大きさにする。 */
private val SENDER_ICON_SIZE = 20.dp

/**
 * バブルのヘッダに出す送信者アイコン（§4.3.1）。取得できていなければ何も描かない。
 *
 * 画面に出た時点で未取得なら 1 回だけ取得を促す。数 KB と小さいうえ手動取得の導線を持たないため、
 * 画像添付の自動表示トグル（[AttachmentUi.autoDisplayImages]）とは無関係に取得する。
 * 失敗後の自動リトライは行わない（進捗が残るため対象から外れる）。
 */
@Composable
internal fun SenderIcon(ref: AttachmentRef, ui: AttachmentUi) {
    val states by ui.states.collectAsState()
    val state = states[ref.blobId] ?: AttachmentDownloadState()

    LaunchedEffect(ref.blobId) {
        if (!state.cached && state.progress == null && !isBlobExpired(ref, ui.now())) ui.onDownload(ref)
    }

    val thumbnail = state.thumbnail ?: return
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
