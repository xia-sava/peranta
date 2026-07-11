package to.sava.peranta.blob

import io.ktor.utils.io.ByteReadChannel
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef
import to.sava.peranta.model.BlobEnc
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 1 件の添付をアップロードするための入力（§4.3）。
 * [openSource] は平文本体のチャンネルを返す。呼び出しごとに先頭から読める新しいチャンネルを返すこと。
 * [sizeBytes] は平文の総バイト数で、暗号文長・チャンク数の算出に使う。
 */
class AttachmentUploadRequest(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AttachmentKind,
    val openSource: suspend () -> ByteReadChannel,
)

/** 新しい blobId 用の UUID 文字列を生成する。 */
@OptIn(ExperimentalUuidApi::class)
fun randomBlobId(): String = Uuid.random().toString()

/**
 * [request] の平文本体をストリーミング暗号化しながら [blobTopic] へアップロードし、[AttachmentRef] を組む（§4.3）。
 * 暗号文長は [cipherLenFor] で決定的に算出して [BlobTransport.upload] の contentLength に渡すため、
 * 全量をメモリに載せない。ファイル名は無害化・長さ制限を掛けてから載せる。
 * アップロード失敗は例外として送出し、呼び出し側（フォアグラウンドサービス）がエラー表示する。
 */
suspend fun uploadAttachment(
    transport: BlobTransport,
    blobCipher: BlobCipher,
    blobTopic: String,
    request: AttachmentUploadRequest,
    newBlobId: () -> String = ::randomBlobId,
): AttachmentRef {
    val blobId = newBlobId()
    val totalChunks = totalChunksFor(request.sizeBytes, DEFAULT_CHUNK_SIZE)
    val cipherLength = cipherLenFor(request.sizeBytes, totalChunks)
    var enc: BlobEnc? = null
    val uploaded = transport.upload(blobTopic, blobId, cipherLength) { output ->
        enc = blobCipher.encrypt(blobId, request.openSource(), output, request.sizeBytes)
    }
    val blobEnc = enc ?: error("encrypt did not produce BlobEnc for blob $blobId")
    return AttachmentRef(
        blobId = blobId,
        url = uploaded.url,
        fileName = normalizeAttachmentFileName(request.fileName),
        mimeType = request.mimeType,
        sizeBytes = request.sizeBytes,
        kind = request.kind,
        blobExpiresAtEpochMillis = uploaded.blobExpiresAtEpochMillis,
        enc = blobEnc,
    )
}
