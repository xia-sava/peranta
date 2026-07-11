package to.sava.peranta.blob

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.serialization.Serializable
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.PerantaJson
import to.sava.peranta.net.httpBaseUrl

/** ntfy の添付アップロードで実ファイル名を載せないためのヘッダ（§7 のリスク対応、blobId のみを送る）。 */
private const val HEADER_FILENAME: String = "X-Filename"

/**
 * ntfy の添付機能を使う [BlobTransport] の Ktor 実装（§4.3）。
 * blobTopic へ暗号化バイト列を PUT し、返ってきた添付 URL を通常ペイロードに埋めて配送する。
 * ダウンロード時も含めて [PerantaConfig.accessToken] による Bearer 認証を必ず付与する。
 * [httpClient] は [to.sava.peranta.net.createNtfyHttpClient] と同じ生成パターンのものを渡す。
 * ダウンロードは [PerantaConfig.host] と異なるホストの URL を拒否し、認証トークンを任意の宛先へ送らない。
 */
class KtorBlobTransport(
    private val config: PerantaConfig,
    private val httpClient: HttpClient,
    private val log: Logger = Logger.withTag("BlobTransport"),
) : BlobTransport {

    override suspend fun upload(
        topic: String,
        blobId: String,
        contentLength: Long,
        writeBody: suspend (ByteWriteChannel) -> Unit,
    ): UploadedBlob {
        val response: HttpResponse = httpClient.put(uploadUrl(topic)) {
            config.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HEADER_FILENAME, blobId)
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Application.OctetStream
                    override val contentLength: Long = contentLength

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        writeBody(channel)
                    }
                },
            )
        }
        if (!response.status.isSuccess()) {
            throw BlobTransportException(response.status.value, "blob upload failed: ${response.status}")
        }
        val attachment = PerantaJson.decodeFromString<NtfyPublishResponse>(response.bodyAsText()).attachment
            ?: throw BlobTransportException(response.status.value, "blob upload response has no attachment")
        log.d { "uploaded blob $blobId to $topic ($contentLength bytes)" }
        return UploadedBlob(
            url = attachment.url,
            blobExpiresAtEpochMillis = attachment.expires?.let { it * MILLIS_PER_SECOND },
        )
    }

    override suspend fun download(url: String): ByteReadChannel {
        val requestHost = Url(url).host
        if (!requestHost.equals(config.host, ignoreCase = true)) {
            throw BlobTransportException(
                HOST_MISMATCH_STATUS,
                "blob download host mismatch: expected ${config.host}, was $requestHost",
            )
        }
        val response: HttpResponse = httpClient.get(url) {
            config.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) {
            throw BlobTransportException(response.status.value, "blob download failed: ${response.status}")
        }
        log.d { "downloading blob from $url" }
        return response.bodyAsChannel()
    }

    private fun uploadUrl(topic: String): String = "${config.httpBaseUrl()}/$topic"

    private companion object {
        const val MILLIS_PER_SECOND: Long = 1000

        /** ホスト不一致で HTTP リクエスト自体を送らなかったことを示す status（HTTP ステータスではない）。 */
        const val HOST_MISMATCH_STATUS: Int = 0
    }
}

/** ntfy の publish/PUT レスポンスのうち添付情報だけを取り出す（他フィールドは無視）。 */
@Serializable
private data class NtfyPublishResponse(val attachment: NtfyAttachment? = null)

/** ntfy の添付メタ。[expires] は添付保持期限（エポック秒）。 */
@Serializable
private data class NtfyAttachment(
    val url: String,
    val expires: Long? = null,
)
