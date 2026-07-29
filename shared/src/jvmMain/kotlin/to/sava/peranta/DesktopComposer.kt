package to.sava.peranta

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import to.sava.peranta.blob.AttachmentUploadRequest
import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.blob.KtorBlobTransport
import to.sava.peranta.blob.TransferProgress
import to.sava.peranta.blob.TransferState
import to.sava.peranta.blob.attachmentKindForMimeType
import to.sava.peranta.blob.uploadAttachment
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.nowEpochMillis
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.platform.JvmPaths
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.send.buildFilePayloads
import to.sava.peranta.send.resolveSendTopics
import to.sava.peranta.send.sendMessage
import to.sava.peranta.ui.ComposerAttachmentsUi
import to.sava.peranta.ui.MessageComposerUi
import to.sava.peranta.ui.StagedFile
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/** ファイル送信に失敗したときにタイムラインへ出す文言（§13 M9d）。 */
internal const val FILE_SEND_FAILED_MESSAGE: String = "ファイルの送信に失敗しました。もう一度お試しください"

/** クリップボード貼り付けでステージする画像の一時ファイル名（[sequence] は 1 始まりの連番）。 */
internal fun clipboardImageFileName(sequence: Long): String = "clipboard-$sequence.png"

/**
 * 貼り付け画像の置き場を composer ごとに分けるディレクトリの前置き。
 * 連番は composer ごとに振り直されるため、作り直された composer が前の画像を上書きしないようにする。
 */
private const val CLIPBOARD_SESSION_PREFIX: String = "session"

/** ステージ済みチップにサムネイルを出す画像ファイルの拡張子（大文字小文字を区別しない）。 */
private val IMAGE_FILE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** ステージ済みサムネイルへ縮小デコードする一辺の目標ピクセル数。 */
private const val STAGED_THUMBNAIL_TARGET_PX: Int = 64

/** [file] が拡張子上、ステージのチップにサムネイル表示すべき画像か。 */
internal fun isImageFile(file: File): Boolean = file.extension.lowercase() in IMAGE_FILE_EXTENSIONS

/** [maxSide] 四方に収まるよう縦横比を保って縮小した画像を返す。既に収まっているならそのまま返す（拡大はしない）。 */
internal fun BufferedImage.scaledToFit(maxSide: Int): BufferedImage {
    if (width <= maxSide && height <= maxSide) return this
    val scale = maxSide.toDouble() / maxOf(width, height)
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(this, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return scaled
}

/**
 * Desktop の composer 送信束（§13 M9d）。テキストのみなら [to.sava.peranta.model.MessagePayload]、
 * ステージ済みファイルが有れば暗号化 blob アップロード + [to.sava.peranta.model.FilePayload]（caption=text）で送る。
 * [DesktopReceiver] が保持する config/httpClient/cipher/ntfy/sendPipeline を共有して生成する。
 * [clipboardImagesRoot] は貼り付け画像の置き場の基点で、既定はアプリのデータ領域（消去の対象）。
 */
class DesktopComposer(
    private val config: PerantaConfig,
    private val httpClient: HttpClient,
    private val cipher: MessageCipher,
    private val ntfy: NtfyClient,
    private val sendPipeline: SendPipeline,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.withTag("DesktopComposer"),
    private val clipboardImagesRoot: File = JvmPaths.clipboardImagesDir,
) {
    private val stagedFiles = MutableStateFlow<List<File>>(emptyList())
    private val staged = MutableStateFlow<List<StagedFile>>(emptyList())
    private val uploadProgress = MutableStateFlow<TransferProgress?>(null)
    private val clipboardImageCounter = AtomicLong(0)
    private val clipboardImageDir: File by lazy {
        Files.createTempDirectory(clipboardImagesRoot.toPath(), CLIPBOARD_SESSION_PREFIX).toFile()
    }

    /** 貼り付けで作ったファイル。ステージから外れたときに消す対象をこれに限り、利用者が選んだファイルには触れない。 */
    private val pastedImages: MutableSet<File> = ConcurrentHashMap.newKeySet()

    /** composer が使う操作束。添付は [config.blobTopic][PerantaConfig.blobTopic] が有るときのみ有効にする。 */
    fun ui(): MessageComposerUi = MessageComposerUi(
        send = ::send,
        attachments = if (config.blobTopic != null) {
            ComposerAttachmentsUi(
                staged = staged.asStateFlow(),
                uploadProgress = uploadProgress.asStateFlow(),
                pickFiles = ::pickFiles,
                removeStaged = ::removeStaged,
                pasteImage = ::pasteImageFromClipboard,
            )
        } else {
            null
        },
    )

    /** 送信するファイルをファイルダイアログ（複数選択）で選び、ステージへ積む。 */
    private fun pickFiles() {
        scope.launch {
            val dialog = FileDialog(null as Frame?, "送信するファイルを選択", FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true
            }
            val selected = dialog.files?.toList().orEmpty()
            if (selected.isNotEmpty()) addStaged(selected)
        }
    }

    /** [index] 番目のステージ済みファイルを外す。 */
    private fun removeStaged(index: Int) {
        setStaged(stagedFiles.value.filterIndexed { i, _ -> i != index })
    }

    /** 選択済みファイルをステージへ積む。テストからも直接呼べるよう [FileDialog] 起動から切り離してある。 */
    internal fun addStaged(files: List<File>) {
        setStaged(stagedFiles.value + files)
    }

    /**
     * composer への Ctrl+V を横取りする（§10.1）。クリップボードに画像が有れば PNG 一時ファイルへ
     * 書き出してステージへ追加し true を返す（イベント消費）。画像が無ければ何もせず false を返し、
     * composer 側の通常のテキスト貼り付けに委ねる。
     */
    private fun pasteImageFromClipboard(): Boolean {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return false
        return try {
            val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return false
            stageClipboardImage(image)
            true
        } catch (error: Exception) {
            log.w(error) { "clipboard image paste failed" }
            false
        }
    }

    /**
     * [image] を PNG 一時ファイルへ書き出してステージへ積み、そのファイルを返す。
     * テストからも直接呼べるようクリップボード読み取りから切り離してある。
     */
    internal fun stageClipboardImage(image: Image): File {
        val target = File(clipboardImageDir, clipboardImageFileName(clipboardImageCounter.incrementAndGet()))
        ImageIO.write(image.toBufferedImage(), "png", target)
        pastedImages.add(target)
        addStaged(listOf(target))
        return target
    }

    private fun Image.toBufferedImage(): BufferedImage {
        if (this is BufferedImage) return this
        val buffered = BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return buffered
    }

    /**
     * ステージ済みの一覧を [files] へ入れ替える。ステージから外れた貼り付け画像はその場で消し、
     * 送る中身の平文コピーをディスクへ残さない（§11）。
     */
    private fun setStaged(files: List<File>) {
        val dropped = stagedFiles.value - files.toSet()
        stagedFiles.value = files
        staged.value = files.map { StagedFile(it.name, it.length(), decodeStagedThumbnail(it)) }
        dropped.filter { pastedImages.remove(it) }
            .filterNot { it.delete() }
            .forEach { log.w { "failed to delete pasted image ${it.name}" } }
    }

    /**
     * 画像ファイルなら表示用に縮小したサムネイルへデコードする。原寸は Compose へ渡さず、
     * [STAGED_THUMBNAIL_TARGET_PX] 四方に収めてから [ImageBitmap] 化する。画像でない、または
     * デコードに失敗した場合は null（チップはファイル名＋サイズの表示へフォールバックする）。
     */
    private fun decodeStagedThumbnail(file: File): ImageBitmap? {
        if (!isImageFile(file)) return null
        return try {
            val original = ImageIO.read(file) ?: return null
            val scaled = original.scaledToFit(STAGED_THUMBNAIL_TARGET_PX)
            val pngBytes = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
            SkiaImage.makeFromEncoded(pngBytes).toComposeImageBitmap()
        } catch (error: Exception) {
            log.w(error) { "staged thumbnail decode failed for ${file.name}" }
            null
        }
    }

    /**
     * ステージ済み添付が有れば暗号化アップロード + FilePayload、無ければ [sendMessage] へ委譲する。
     * 失敗時はステージ済みファイルとテキストを保持し、ユーザーが再送できるようにする。
     */
    private suspend fun send(text: String): Boolean {
        val files = stagedFiles.value
        return if (files.isEmpty()) {
            sendMessage(config, cipher, ntfy, sendPipeline, text)
        } else {
            sendFiles(files, text).also { delivered -> if (delivered) setStaged(emptyList()) }
        }
    }

    private suspend fun sendFiles(files: List<File>, text: String): Boolean {
        val blobTopic = config.blobTopic ?: return false
        val totalBytes = files.sumOf { it.length() }
        val transferred = AtomicLong(0)
        val sources = mutableListOf<Closeable>()
        uploadProgress.value = TransferProgress(0, totalBytes, TransferState.RUNNING)
        return try {
            val blobCipher = BlobCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
            val transport = KtorBlobTransport(config, httpClient)
            val refs = files.map { file ->
                uploadOne(file, blobCipher, transport, blobTopic, transferred, totalBytes, sources)
            }
            val payloads = buildFilePayloads(
                deviceId = config.deviceId!!,
                attachments = refs,
                keyId = config.keyId!!,
                now = nowEpochMillis(),
                caption = text.ifBlank { null },
                deviceName = config.deviceName,
            )
            val topics = resolveSendTopics(config, cipher, ntfy)
            if (topics.isEmpty()) {
                sendPipeline.recordError(FILE_SEND_FAILED_MESSAGE)
                return false
            }
            payloads.forEach { payload -> sendPipeline.send(payload, topics) }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "file send failed" }
            runCatching { sendPipeline.recordError(FILE_SEND_FAILED_MESSAGE) }
            false
        } finally {
            sources.forEach { source ->
                runCatching { source.close() }
                    .onFailure { log.w(it) { "failed to close upload source" } }
            }
            uploadProgress.value = null
        }
    }

    private suspend fun uploadOne(
        file: File,
        blobCipher: BlobCipher,
        transport: KtorBlobTransport,
        blobTopic: String,
        transferred: AtomicLong,
        totalBytes: Long,
        sources: MutableList<Closeable>,
    ): AttachmentRef {
        val mimeType = mimeTypeFor(file)
        return uploadAttachment(
            transport = transport,
            blobCipher = blobCipher,
            blobTopic = blobTopic,
            request = AttachmentUploadRequest(
                fileName = file.name,
                mimeType = mimeType,
                sizeBytes = file.length(),
                kind = attachmentKindForMimeType(mimeType),
                openSource = { countingChannel(file, transferred, totalBytes, sources) },
            ),
        )
    }

    /**
     * [file] を読みながらバイト数を [transferred] へ積み上げ、複数ファイル合計の進捗を [uploadProgress] へ直接反映する。
     * 開いた読み取り元は [sources] へ預け、送信の後始末で必ず閉じる（掴んだままだとファイルを消せない）。
     */
    private fun countingChannel(
        file: File,
        transferred: AtomicLong,
        totalBytes: Long,
        sources: MutableList<Closeable>,
    ): ByteReadChannel {
        val counting = object : FilterInputStream(file.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { read ->
                    if (read > 0) {
                        val total = transferred.addAndGet(read.toLong())
                        uploadProgress.value = TransferProgress(total, totalBytes, TransferState.RUNNING)
                    }
                }
        }
        sources.add(counting)
        return counting.toByteReadChannel()
    }

    private fun mimeTypeFor(file: File): String =
        try {
            Files.probeContentType(file.toPath()) ?: DEFAULT_MIME_TYPE
        } catch (error: Exception) {
            log.w(error) { "failed to probe content type for ${file.name}" }
            DEFAULT_MIME_TYPE
        }

    private companion object {
        const val DEFAULT_MIME_TYPE: String = "application/octet-stream"
    }
}
