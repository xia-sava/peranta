package to.sava.peranta

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.send.buildFilePayloads
import to.sava.peranta.send.resolveSendTopics
import to.sava.peranta.send.sendMessage
import to.sava.peranta.ui.ComposerAttachmentsUi
import to.sava.peranta.ui.MessageComposerUi
import to.sava.peranta.ui.StagedFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64

/** ファイル送信に失敗したときにタイムラインへ出す文言（§13 M9d）。 */
internal const val FILE_SEND_FAILED_MESSAGE: String = "ファイルの送信に失敗しました。もう一度お試しください"

/**
 * Desktop の composer 送信束（§13 M9d）。テキストのみなら [to.sava.peranta.model.MessagePayload]、
 * ステージ済みファイルが有れば暗号化 blob アップロード + [to.sava.peranta.model.FilePayload]（caption=text）で送る。
 * [DesktopReceiver] が保持する config/httpClient/cipher/ntfy/sendPipeline を共有して生成する。
 */
class DesktopComposer(
    private val config: PerantaConfig,
    private val httpClient: HttpClient,
    private val cipher: MessageCipher,
    private val ntfy: NtfyClient,
    private val sendPipeline: SendPipeline,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.withTag("DesktopComposer"),
) {
    private val stagedFiles = MutableStateFlow<List<File>>(emptyList())
    private val staged = MutableStateFlow<List<StagedFile>>(emptyList())
    private val uploadProgress = MutableStateFlow<TransferProgress?>(null)

    /** composer が使う操作束。添付は [config.blobTopic][PerantaConfig.blobTopic] が有るときのみ有効にする。 */
    fun ui(): MessageComposerUi = MessageComposerUi(
        send = ::send,
        attachments = if (config.blobTopic != null) {
            ComposerAttachmentsUi(
                staged = staged.asStateFlow(),
                uploadProgress = uploadProgress.asStateFlow(),
                pickFiles = ::pickFiles,
                removeStaged = ::removeStaged,
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

    private fun setStaged(files: List<File>) {
        stagedFiles.value = files
        staged.value = files.map { StagedFile(it.name, it.length()) }
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
            sendFiles(files, text)
        }
    }

    private suspend fun sendFiles(files: List<File>, text: String): Boolean {
        val blobTopic = config.blobTopic ?: return false
        val totalBytes = files.sumOf { it.length() }
        val transferred = AtomicLong(0)
        uploadProgress.value = TransferProgress(0, totalBytes, TransferState.RUNNING)
        return try {
            val blobCipher = BlobCipher(Base64.decode(config.sharedKeyBase64!!), config.keyId!!)
            val transport = KtorBlobTransport(config, httpClient)
            val refs = files.map { file -> uploadOne(file, blobCipher, transport, blobTopic, transferred, totalBytes) }
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
            setStaged(emptyList())
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.w(error) { "file send failed" }
            runCatching { sendPipeline.recordError(FILE_SEND_FAILED_MESSAGE) }
            false
        } finally {
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
                openSource = { countingChannel(file, transferred, totalBytes) },
            ),
        )
    }

    /** [file] を読みながらバイト数を [transferred] へ積み上げ、複数ファイル合計の進捗を [uploadProgress] へ直接反映する。 */
    private fun countingChannel(file: File, transferred: AtomicLong, totalBytes: Long): ByteReadChannel {
        val counting = object : FilterInputStream(file.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { read ->
                    if (read > 0) {
                        val total = transferred.addAndGet(read.toLong())
                        uploadProgress.value = TransferProgress(total, totalBytes, TransferState.RUNNING)
                    }
                }
        }
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
