package to.sava.peranta

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.sava.peranta.blob.AttachmentOpenDecision
import to.sava.peranta.blob.attachmentOpenDecision
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.platform.ioDispatcher
import to.sava.peranta.ui.ATTACHMENT_NO_VIEWER_MESSAGE
import to.sava.peranta.ui.ATTACHMENT_SAVE_FAILED_MESSAGE
import to.sava.peranta.ui.REFUSED_OPEN_MESSAGE
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 受信した添付の「開く」「保存」を担う（§4.3）。添付カードのボタンから呼ばれ、復号済みキャッシュのファイルを
 * OS 既定アプリへ渡す・利用者が選んだ保存先へコピーする。失敗は [reportError] でタイムラインへ記録する（§10.1）。
 * blobId から [AttachmentRef] を引くのは [refFor]、復号済みファイルを引くのは [cachedFileFor] に委ねる。
 * OS への受け渡しは [openFile]（渡せなければ例外を投げる）と [chooseSaveDestination]（閉じられたら null）で受ける。
 * 1 回の操作は [scope] の 1 つのコルーチンで動かす（参照・ファイルの引き当てから、OS への受け渡し・コピー・記録まで）。
 */
internal class DesktopAttachmentActions(
    private val scope: CoroutineScope,
    private val refFor: (blobId: String) -> AttachmentRef?,
    private val cachedFileFor: (ref: AttachmentRef) -> File?,
    private val openFile: (file: File) -> Unit,
    private val chooseSaveDestination: (suggestedName: String) -> File?,
    private val reportError: suspend (message: String) -> Unit,
    private val log: Logger = Logger.withTag("AttachmentActions"),
) {

    /**
     * 復号済みファイルを OS 既定アプリで開く。渡せなかったときは開けるアプリが無い旨を記録する。
     * 添付カードは [attachmentOpenDecision] で導線を出し分けているが、OS へ渡す直前でも当て直す。
     */
    fun open(blobId: String) {
        scope.launch {
            val (ref, file) = resolve(blobId) ?: return@launch
            if (attachmentOpenDecision(ref.mimeType, ref.fileName) == AttachmentOpenDecision.REFUSE) {
                log.w { "attachment open refused blobId=$blobId" }
                reportError(REFUSED_OPEN_MESSAGE)
                return@launch
            }
            try {
                openFile(file)
            } catch (error: Exception) {
                log.w(error) { "failed to open attachment blobId=$blobId" }
                reportError(ATTACHMENT_NO_VIEWER_MESSAGE)
            }
        }
    }

    /**
     * 保存先を選ばせ、復号済みファイルをそこへコピーする。保存先を選ばずに閉じたときは何もしない。
     * 保存先の選択やコピーに失敗したときは記録する。
     */
    fun save(blobId: String) {
        scope.launch {
            val (_, source) = resolve(blobId) ?: return@launch
            try {
                val destination = chooseSaveDestination(source.name) ?: return@launch
                withContext(ioDispatcher) {
                    Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                log.i { "attachment saved blobId=$blobId" }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to save attachment blobId=$blobId" }
                reportError(ATTACHMENT_SAVE_FAILED_MESSAGE)
            }
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
}

/** [file] を OS 既定アプリで開く。この環境で開けない・開くのに失敗したときは例外を投げる。 */
internal fun openWithDesktop(file: File) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        throw UnsupportedOperationException("Desktop open not supported")
    }
    Desktop.getDesktop().open(file)
}

/** 保存ダイアログで保存先を選ばせる。[suggestedName] を初期のファイル名にする。選ばずに閉じたら null。 */
internal fun chooseSaveDestinationWithDialog(suggestedName: String): File? {
    val dialog = FileDialog(null as Frame?, "保存", FileDialog.SAVE).apply {
        file = suggestedName
        isVisible = true
    }
    val directory = dialog.directory ?: return null
    val chosen = dialog.file ?: return null
    return File(directory, chosen)
}
