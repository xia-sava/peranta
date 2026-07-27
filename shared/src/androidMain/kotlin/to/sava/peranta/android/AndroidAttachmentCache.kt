package to.sava.peranta.android

import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.blob.BlobTransport
import to.sava.peranta.blob.CachedAttachment
import to.sava.peranta.blob.normalizeAttachmentFileName
import to.sava.peranta.blob.sanitizeAttachmentFileName
import to.sava.peranta.blob.selectAttachmentsToPrune
import to.sava.peranta.model.AttachmentRef
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 復号ストリームを一時ファイルへ写す際のバッファサイズ。 */
private const val COPY_BUFFER_SIZE: Int = 64 * 1024

/**
 * Android の復号済み添付キャッシュ（§4.3）。Desktop の同名層と同じ設計思想で、blob ごとに
 * `<baseDir>/<blobId>/<サニタイズ済みfileName>` へ置く。ダウンロードは必ず一時ファイルへ復号し、
 * 全チャンクの検証が完走してから最終ファイル名へ rename する。復号に失敗したら一時ファイルを消して
 * 例外を送出し、部分ファイルを残さない。[baseDir] は `context.cacheDir/attachments` を渡す。
 * android 依存を持たない（[baseDir] を注入する）ので、フェイク transport でホストテストできる。
 */
class AndroidAttachmentCache(
    private val transport: BlobTransport,
    private val sharedKey: ByteArray,
    private val keyId: String,
    private val baseDir: File,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: Logger = Logger.withTag("AndroidAttachmentCache"),
) {

    /** 既に復号済みのファイルがあれば返し、最終アクセス時刻を更新する。無ければ null。 */
    fun cachedFile(ref: AttachmentRef): File? {
        val target = targetFile(ref)
        if (!target.isFile) return null
        target.setLastModified(now())
        return target
    }

    /**
     * [ref] の blob をダウンロードして復号し、キャッシュへ保存して最終ファイルを返す（§4.3）。
     * 既にキャッシュ済みならダウンロードせずそれを返す。[onProgress] は復号済みバイト数を都度通知する。
     * ダウンロード・復号失敗は例外として送出する（自動再送しない。ユーザーの手動再試行に委ねる）。
     */
    suspend fun download(ref: AttachmentRef, onProgress: (transferredBytes: Long) -> Unit = {}): File {
        cachedFile(ref)?.let {
            onProgress(ref.sizeBytes)
            return it
        }
        val dir = blobDir(ref).apply { mkdirs() }
        val target = File(dir, safeFileName(ref))
        val tmp = File(dir, ".${safeFileName(ref)}.part")
        try {
            transport.download(ref.url, ref.blobId) { input ->
                writeDecrypted(ref, input, tmp, onProgress)
            }
            moveIntoPlace(tmp, target)
            target.setLastModified(now())
            log.i { "attachment cached blobId=${ref.blobId} bytes=${ref.sizeBytes}" }
            prune()
            return target
        } catch (error: Throwable) {
            if (!tmp.delete() && tmp.exists()) {
                log.w { "failed to delete partial download blobId=${ref.blobId}" }
            }
            throw error
        }
    }

    /**
     * [input] の暗号文を復号しながら [tmp] へ書き出す。復号側（[BlobCipher]）が改竄・切り詰め・伸長を
     * 検出して例外を投げ、その場合 [tmp] は不完全なまま呼び出し側が破棄する。
     */
    private suspend fun writeDecrypted(
        ref: AttachmentRef,
        input: ByteReadChannel,
        tmp: File,
        onProgress: (Long) -> Unit,
    ) = coroutineScope {
        val cipher = BlobCipher(sharedKey, keyId)
        val pipe = ByteChannel(autoFlush = true)
        FileOutputStream(tmp).use { output ->
            val copyJob = launch {
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val read = pipe.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }
            try {
                cipher.decrypt(ref.blobId, ref.enc, ref.sizeBytes, input, pipe)
                pipe.flushAndClose()
                copyJob.join()
            } catch (cancellation: CancellationException) {
                pipe.cancel(cancellation)
                copyJob.cancel()
                throw cancellation
            } catch (error: Throwable) {
                pipe.cancel(error)
                copyJob.cancel()
                throw error
            }
        }
    }

    private fun moveIntoPlace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 「最終アクセスから 24 時間経過」または「合計サイズが上限超過分を古い順」でキャッシュを剪定する（§4.3）。
     * 削除失敗（ロック中等）は握り潰さずログに残し、次回に回して処理を継続する。
     */
    fun prune() {
        val entries = listEntries()
        val toRemove = selectAttachmentsToPrune(entries, now()).toSet()
        entries.filter { it.id in toRemove }.forEach { entry ->
            val dir = File(baseDir, entry.id)
            if (!dir.deleteRecursively()) {
                log.w { "failed to prune attachment cache dir ${entry.id}; retrying next time" }
            }
        }
    }

    /** キャッシュ配下の blob ディレクトリを剪定判定用のエントリに変換する。 */
    fun listEntries(): List<CachedAttachment> {
        val dirs = baseDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.map { dir ->
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            CachedAttachment(
                id = dir.name,
                sizeBytes = files.sumOf { it.length() },
                lastAccessEpochMillis = files.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
            )
        }
    }

    private fun targetFile(ref: AttachmentRef): File = File(blobDir(ref), safeFileName(ref))

    private fun blobDir(ref: AttachmentRef): File =
        File(baseDir, sanitizeAttachmentFileName(ref.blobId, fallback = "blob"))

    private fun safeFileName(ref: AttachmentRef): String = normalizeAttachmentFileName(ref.fileName)
}
