package to.sava.peranta.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.platform.ioDispatcher
import java.io.File

/** 添付共有・保存に使う FileProvider の authority 接尾辞（自己更新と同じ provider を流用、§4.3）。 */
private const val FILE_PROVIDER_SUFFIX: String = ".updates"

/** 開くハンドラが無いときにタイムラインへ出す文言。 */
private const val NO_VIEWER_MESSAGE: String = "このファイルを開けるアプリが見つかりません"

/** 保存に失敗したときにタイムラインへ出す文言。 */
private const val SAVE_FAILED_MESSAGE: String = "ファイルの保存に失敗しました"

/** [packageName] に対応する添付用 FileProvider の authority。 */
fun attachmentFileProviderAuthority(packageName: String): String = "$packageName$FILE_PROVIDER_SUFFIX"

/** SAF 保存結果に対する処理の分岐。 */
internal enum class SaveDocumentOutcome {
    /** ユーザーがピッカーをキャンセルした（結果 Uri なし）。何もしない。 */
    CANCELLED,

    /** 保存先は返ったが保存対象を引けない（blobId 消失・履歴消失）。SAF が作った空ファイルを黙認せずエラーにする。 */
    MISSING_TARGET,

    /** 保存先も保存対象も揃った。コピーへ進む。 */
    PROCEED,
}

/**
 * SAF が返した保存先の有無 [hasUri] と、保存対象を引けたか [hasSaveTarget] から処理を決める（§4.3）。
 * SAF は結果 Uri の時点で保存先に空ファイルを作るため、保存対象が無いまま return すると空ファイルが残る。
 * これを避けるため、保存先があるのに対象が無いケースは失敗として案内する。
 */
internal fun saveDocumentOutcome(hasUri: Boolean, hasSaveTarget: Boolean): SaveDocumentOutcome = when {
    !hasUri -> SaveDocumentOutcome.CANCELLED
    !hasSaveTarget -> SaveDocumentOutcome.MISSING_TARGET
    else -> SaveDocumentOutcome.PROCEED
}

/**
 * 受信した添付の「開く」「保存」「共有」を担う（§4.3）。復号済みキャッシュを FileProvider の Uri として
 * 他アプリへ渡す。保存は Storage Access Framework のドキュメント作成ランチャーが要るため、
 * ランチャー起動は [launchSaveDocument] として Activity 側から注入する（結果 Uri は [copyToDocument] へ戻す）。
 * blobId から [AttachmentRef] を引くのは [refFor]（タイムライン履歴を参照）に委ねる。
 */
class AndroidAttachmentActions(
    private val context: Context,
    private val refFor: (blobId: String) -> AttachmentRef?,
    private val cachedFileFor: (ref: AttachmentRef) -> File?,
    private val launchSaveDocument: (fileName: String, mimeType: String) -> Unit,
    private val reportError: (message: String) -> Unit,
    private val log: Logger = Logger.withTag("AttachmentActions"),
) {

    /** 保存ランチャーが返す Uri と対応づけるため、保存要求中の blobId を覚える。 */
    private var pendingSaveBlobId: String? = null

    /** 復号済みファイルを OS 既定アプリで開く。ハンドラ不在は案内表示にフォールバックする。 */
    fun open(blobId: String) {
        val (ref, file) = resolve(blobId) ?: return
        val uri = uriFor(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ref.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (notFound: ActivityNotFoundException) {
            log.i(notFound) { "no viewer for mimeType=${ref.mimeType}" }
            reportError(NO_VIEWER_MESSAGE)
        }
    }

    /** 復号済みファイルを Android 共有シートで他アプリへ共有する。 */
    fun share(blobId: String) {
        val (ref, file) = resolve(blobId) ?: return
        val uri = uriFor(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = ref.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(chooser)
        } catch (notFound: ActivityNotFoundException) {
            log.i(notFound) { "no share target for mimeType=${ref.mimeType}" }
            reportError(NO_VIEWER_MESSAGE)
        }
    }

    /** SAF のドキュメント作成を起動する。返った Uri は [copyToDocument] へ渡すこと。 */
    fun save(blobId: String) {
        val (ref, _) = resolve(blobId) ?: return
        pendingSaveBlobId = blobId
        launchSaveDocument(ref.fileName, ref.mimeType)
    }

    /** Activity 再生成に備え、保存要求中の blobId を取り出す（`onSaveInstanceState` で退避する）。 */
    fun pendingSaveState(): String? = pendingSaveBlobId

    /** `onSaveInstanceState` で退避した blobId を復元する（Activity 再生成後の結果 Uri 受け取りに備える）。 */
    fun restorePendingSaveState(blobId: String?) {
        if (blobId != null) pendingSaveBlobId = blobId
    }

    /**
     * SAF が返した [uri]（キャンセル時は null）へ、保存要求中の添付をコピーする（§4.3）。
     * 保存対象を引けないときは SAF が作った空ファイルを黙認せず失敗として案内する（[saveDocumentOutcome]）。
     */
    suspend fun copyToDocument(uri: Uri?) {
        val blobId = pendingSaveBlobId
        pendingSaveBlobId = null
        val resolved = blobId?.let { resolve(it) }
        when (saveDocumentOutcome(hasUri = uri != null, hasSaveTarget = resolved != null)) {
            SaveDocumentOutcome.CANCELLED -> Unit
            SaveDocumentOutcome.MISSING_TARGET -> {
                log.w { "save destination returned but save target was lost blobId=$blobId" }
                reportError(SAVE_FAILED_MESSAGE)
            }
            SaveDocumentOutcome.PROCEED -> writeToDocument(requireNotNull(uri), resolved!!.second, requireNotNull(blobId))
        }
    }

    /**
     * 復号済みファイルを SAF の保存先 [uri] へ書き出す。コピーは IO ディスパッチャで行い、
     * 失敗は握り潰さずタイムラインへ案内する。ログには保存先 Uri を出さず blobId のみを残す。
     */
    private suspend fun writeToDocument(uri: Uri, source: File, blobId: String) {
        try {
            withContext(ioDispatcher) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw java.io.IOException("openOutputStream returned null blobId=$blobId")
            }
            log.i { "attachment saved blobId=$blobId" }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "failed to save attachment blobId=$blobId" }
            reportError(SAVE_FAILED_MESSAGE)
        }
    }

    /** blobId から添付参照と復号済みファイルを引く。どちらか欠ければ null（未取得・履歴消失）。 */
    private fun resolve(blobId: String): Pair<AttachmentRef, File>? {
        val ref = refFor(blobId) ?: run {
            log.w { "no attachment ref for blobId=$blobId" }
            return null
        }
        val file = cachedFileFor(ref) ?: run {
            log.w { "no cached file for blobId=$blobId" }
            return null
        }
        return ref to file
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, attachmentFileProviderAuthority(context.packageName), file)
}
