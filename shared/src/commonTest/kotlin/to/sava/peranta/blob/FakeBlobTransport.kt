package to.sava.peranta.blob

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel

/**
 * テスト用の [BlobTransport]。アップロードされた本体をメモリに保持し、URL でダウンロードできる。
 * [urlForBlob] で blobId から URL を組み、[blobExpiresAtEpochMillis] を [UploadedBlob] に載せる。
 */
class FakeBlobTransport(
    private val urlForBlob: (blobId: String) -> String = { "https://blob.invalid/file/$it" },
    private val blobExpiresAtEpochMillis: Long? = 9_000,
) : BlobTransport {

    data class Uploaded(val topic: String, val blobId: String, val contentLength: Long, val body: ByteArray)

    val uploads: MutableList<Uploaded> = mutableListOf()
    private val stored: MutableMap<String, ByteArray> = mutableMapOf()

    override suspend fun upload(
        topic: String,
        blobId: String,
        contentLength: Long,
        writeBody: suspend (ByteWriteChannel) -> Unit,
    ): UploadedBlob {
        val body = drainToBytes(writeBody)
        val url = urlForBlob(blobId)
        uploads.add(Uploaded(topic, blobId, contentLength, body))
        stored[url] = body
        return UploadedBlob(url = url, blobExpiresAtEpochMillis = blobExpiresAtEpochMillis)
    }

    override suspend fun download(url: String): ByteReadChannel {
        val body = stored[url] ?: throw IllegalStateException("no blob stored at $url")
        return ByteReadChannel(body)
    }
}
