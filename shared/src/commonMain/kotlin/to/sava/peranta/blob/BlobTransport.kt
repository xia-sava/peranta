package to.sava.peranta.blob

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel

/** アップロード完了で得た blob の所在と有効期限。 */
data class UploadedBlob(
    val url: String,
    val blobExpiresAtEpochMillis: Long?,
)

/**
 * 暗号化 blob を blobTopic へアップロードし、URL からダウンロードする層（§4.3）。
 * テストではフェイク実装に差し替えられるよう interface とする。
 */
interface BlobTransport {

    /**
     * [topic] へ [contentLength] バイトの blob 本体を [writeBody] で書き出しつつアップロードし、所在を返す。
     * 本体はコールバックでストリーム出力し、全量をメモリに載せない。
     */
    suspend fun upload(
        topic: String,
        blobId: String,
        contentLength: Long,
        writeBody: suspend (ByteWriteChannel) -> Unit,
    ): UploadedBlob

    /** [url] の blob をダウンロードし、本文チャンネルを返す。呼び出し側は速やかに消費する。 */
    suspend fun download(url: String): ByteReadChannel
}

/** blob のアップロード/ダウンロードが 2xx 以外で返ったことを示す。 */
class BlobTransportException(val status: Int, message: String) : Exception(message)
