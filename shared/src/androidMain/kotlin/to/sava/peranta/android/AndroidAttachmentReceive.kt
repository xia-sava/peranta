package to.sava.peranta.android

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.sava.peranta.blob.BlobTransport
import to.sava.peranta.blob.KtorBlobTransport
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.blob.attachmentKindForMimeType
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.net.createNtfyHttpClient
import to.sava.peranta.timeline.ReceivedFile
import to.sava.peranta.timeline.TimelineItem
import to.sava.peranta.ui.AttachmentDownloadState
import to.sava.peranta.ui.AttachmentUi
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/** 復号済み添付キャッシュのディレクトリ名（res/xml/file_paths.xml の cache-path と一致させる）。 */
const val ATTACHMENTS_CACHE_DIR: String = "attachments"

/** サムネイルのデコードを試みる添付の上限バイト（巨大画像で OOM しないため）。 */
private const val MAX_THUMBNAIL_DECODE_BYTES: Long = 25L * 1024 * 1024

/** サムネイルの目標一辺（dp）。この寸法に収まるよう縮小デコードして OOM を防ぐ（カード表示は最大 220dp）。 */
private const val THUMBNAIL_TARGET_DP: Int = 220

/**
 * [width]×[height] の画像を [reqWidth]×[reqHeight] に収めるための [BitmapFactory] inSampleSize（2 の累乗）を求める。
 * フルサイズのビットマップを確保せず、表示に必要な解像度までデコード時に間引くための係数（AOSP 標準パターン）。
 */
internal fun thumbnailSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
    var sampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / sampleSize >= reqHeight && halfWidth / sampleSize >= reqWidth) {
            sampleSize *= 2
        }
    }
    return sampleSize
}

/**
 * Android 受信側の添付ダウンロード状態を保持し、タイムライン UI（[AttachmentUi]）へ橋渡しするシングルトン（§4.3）。
 * ダウンロード本体は [AttachmentTransferService]（フォアグラウンドサービス）が実行し、進捗・完了・失敗を
 * ここの [states] へ書き込む。UI はこの StateFlow を購読して未取得/進行中/完了/失敗を出し分ける。
 * プロセス内で 1 つに保つことで、サービス（書き手）と Compose UI（読み手）が同じ状態を共有する。
 */
object AndroidAttachmentReceive {

    private val log = Logger.withTag("AndroidAttachmentReceive")
    private val httpClient by lazy { createNtfyHttpClient() }

    private val _states = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())

    /** blobId 毎のダウンロード状態。UI がこれを購読する。 */
    val states: StateFlow<Map<String, AttachmentDownloadState>> = _states.asStateFlow()

    /** [context] のキャッシュ領域を基点にした添付キャッシュを組む。 */
    fun cache(context: Context, config: PerantaConfig): AndroidAttachmentCache =
        AndroidAttachmentCache(
            transport = transport(context, config),
            sharedKey = Base64.decode(config.sharedKeyBase64!!),
            keyId = config.keyId!!,
            baseDir = attachmentsDir(context),
        )

    /** [context] の添付キャッシュ基点ディレクトリ（`cacheDir/attachments`）。 */
    fun attachmentsDir(context: Context): File = File(context.applicationContext.cacheDir, ATTACHMENTS_CACHE_DIR)

    private fun transport(context: Context, config: PerantaConfig): BlobTransport =
        KtorBlobTransport(config, httpClient)

    /** ダウンロード開始を UI へ即時反映する（サービス起動と並行してボタンを進行中表示へ切り替える）。 */
    fun markRunning(blobId: String, totalBytes: Long) {
        _states.update { it + (blobId to AttachmentDownloadState(progress = TransferProgress.running(totalBytes))) }
    }

    /** 転送済みバイト数を UI の進捗バーへ反映する。 */
    fun markProgress(blobId: String, transferredBytes: Long, totalBytes: Long) {
        _states.update {
            it + (blobId to AttachmentDownloadState(
                progress = TransferProgress(transferredBytes, totalBytes, TransferState.RUNNING),
            ))
        }
    }

    /** ダウンロード完了を反映し、画像なら復号済みファイルからサムネイルを付ける。 */
    fun markCached(ref: AttachmentRef, file: File) {
        val thumbnail = decodeThumbnail(ref, file)
        _states.update {
            it + (ref.blobId to AttachmentDownloadState(
                progress = TransferProgress(ref.sizeBytes, ref.sizeBytes, TransferState.COMPLETED),
                cached = true,
                thumbnail = thumbnail,
            ))
        }
    }

    /** ダウンロード失敗を反映する（カードは再試行ボタンを出す）。 */
    fun markFailed(blobId: String, totalBytes: Long) {
        _states.update {
            it + (blobId to AttachmentDownloadState(progress = TransferProgress(0, totalBytes, TransferState.FAILED)))
        }
    }

    /** ユーザーによるキャンセルを反映する（カードは未取得へ戻る）。 */
    fun markCancelled(blobId: String) {
        _states.update { it + (blobId to AttachmentDownloadState(progress = TransferProgress(0, 0, TransferState.CANCELLED))) }
    }

    /**
     * タイムラインに現れた受信ファイルのうち、既にキャッシュ済みの添付を「取得済み」状態へ反映する（§4.3）。
     * 履歴読み込み・新規受信の双方でカードを開く/保存/共有できる状態にする。
     */
    fun primeCached(context: Context, config: PerantaConfig, items: List<TimelineItem>) {
        if (!config.hasSharedKey) return
        val cache = cache(context, config)
        items.filterIsInstance<ReceivedFile>().forEach { file ->
            file.payload.attachments.forEach { ref ->
                if (_states.value[ref.blobId] == null) {
                    cache.cachedFile(ref)?.let { markCached(ref, it) }
                }
            }
        }
    }

    /**
     * 画像添付を復号済みファイルからデコードしてサムネイルにする。失敗時は null（種別アイコンにフォールバック）。
     * まず [BitmapFactory.Options.inJustDecodeBounds] で寸法だけ読み、表示サイズに収まる [inSampleSize] を
     * 掛けて縮小デコードする。フルサイズのビットマップを [states] に抱え続けて OOM するのを避ける。
     */
    private fun decodeThumbnail(ref: AttachmentRef, file: File): ImageBitmap? {
        if (attachmentKindForMimeType(ref.mimeType) != AttachmentKind.IMAGE) return null
        if (file.length() > MAX_THUMBNAIL_DECODE_BYTES) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val target = thumbnailTargetPixels()
                val options = BitmapFactory.Options().apply {
                    inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, target, target)
                }
                BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
            }
        } catch (error: Exception) {
            log.w(error) { "thumbnail decode failed blobId=${ref.blobId}" }
            null
        }
    }

    /** サムネイルの目標一辺（ピクセル）。端末の表示密度を掛けて dp をピクセルへ変換する。 */
    private fun thumbnailTargetPixels(): Int =
        (THUMBNAIL_TARGET_DP * Resources.getSystem().displayMetrics.density).roundToInt()

    /**
     * タイムラインの添付カード用の操作束を作る（§4.3、§10.1）。ダウンロード・キャンセルはフォアグラウンド
     * サービスへ委ね、開く・保存・共有は [actions]（Activity 起点の FileProvider / SAF 連携）へ委ねる。
     * 保存は SAF がランチャーを要するため Activity 側で実装し、開く・共有は app context からも起動できる。
     */
    fun attachmentUi(context: Context, actions: AndroidAttachmentActions): AttachmentUi {
        val appContext = context.applicationContext
        return AttachmentUi(
            states = states,
            onDownload = { ref -> AttachmentTransferService.enqueueDownload(appContext, ref) },
            onCancel = { blobId -> AttachmentTransferService.cancel(appContext, blobId) },
            onOpen = { blobId -> actions.open(blobId) },
            onSave = { blobId -> actions.save(blobId) },
            onShare = { blobId -> actions.share(blobId) },
            canShare = true,
        )
    }
}
