package to.sava.peranta.blob

import io.ktor.utils.io.ByteReadChannel
import to.sava.peranta.model.AttachmentKind
import to.sava.peranta.model.AttachmentRef

/** 全文添付（§4.3）のファイル名。受信側で保存・表示するときの名前に使う。 */
const val FULL_TEXT_ATTACHMENT_FILE_NAME: String = "message.txt"

/** 全文添付の MIME タイプ。 */
const val FULL_TEXT_ATTACHMENT_MIME_TYPE: String = "text/plain"

/**
 * 全文添付としてアップロード・自動取得する本文の上限バイト（§4.3）。通知本文としては十分すぎる大きさ。
 * 送信側はこれを超える本文の全文添付自体を諦め、受信側はこれを超える添付の自動取得を行わない。
 */
const val MAX_FULL_TEXT_ATTACHMENT_BYTES: Long = 256L * 1024

/** [sizeBytes] が全文添付の自動取得上限（[MAX_FULL_TEXT_ATTACHMENT_BYTES]）を超えているか。 */
fun exceedsFullTextAutoFetchLimit(sizeBytes: Long): Boolean = sizeBytes > MAX_FULL_TEXT_ATTACHMENT_BYTES

/**
 * 切り詰め前の本文全文 [text] を暗号化 blob として [blobTopic] へアップロードし、[AttachmentRef]（kind=TEXT）を返す（§4.3）。
 * 画像・ファイルと同じ [uploadAttachment] 経路を使い、平文は UTF-8 バイト列として 1 度だけ読ませる。
 */
suspend fun uploadFullTextAttachment(
    transport: BlobTransport,
    blobCipher: BlobCipher,
    blobTopic: String,
    text: String,
    newBlobId: () -> String = ::randomBlobId,
): AttachmentRef {
    val bytes = text.encodeToByteArray()
    return uploadAttachment(
        transport = transport,
        blobCipher = blobCipher,
        blobTopic = blobTopic,
        request = AttachmentUploadRequest(
            fileName = FULL_TEXT_ATTACHMENT_FILE_NAME,
            mimeType = FULL_TEXT_ATTACHMENT_MIME_TYPE,
            sizeBytes = bytes.size.toLong(),
            kind = AttachmentKind.TEXT,
            openSource = { ByteReadChannel(bytes) },
        ),
        newBlobId = newBlobId,
    )
}
