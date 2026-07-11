package to.sava.peranta.blob

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import to.sava.peranta.config.PerantaConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorBlobTransportTest {

    private fun transportFor(
        config: PerantaConfig = PerantaConfig(host = "localhost", useTls = false, port = 8090, accessToken = "tok"),
        captured: MutableList<HttpRequestData> = mutableListOf(),
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorBlobTransport {
        val engine = MockEngine { request ->
            captured.add(request)
            handler(request)
        }
        return KtorBlobTransport(config, HttpClient(engine))
    }

    /** upload は PUT で blobTopic の URL を叩き、認証・X-Filename(blobId)・本体を送り、添付情報を返す。 */
    @Test
    fun uploadPutsWithFilenameHeaderAndParsesAttachment() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val transport = transportFor(captured = captured) {
            respond(
                content = """{"id":"x","attachment":{"name":"blob-1","url":"https://localhost:8090/file/abc","expires":1700}}""",
                status = HttpStatusCode.OK,
            )
        }
        val body = ByteArray(300) { it.toByte() }
        val uploaded = transport.upload("peranta-blob-x", "blob-1", body.size.toLong()) { channel ->
            channel.writeByteArray(body)
        }

        assertEquals("https://localhost:8090/file/abc", uploaded.url)
        assertEquals(1_700_000, uploaded.blobExpiresAtEpochMillis)

        val request = captured.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("http://localhost:8090/peranta-blob-x", request.url.toString())
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("blob-1", request.headers["X-Filename"])
        assertEquals(body.size.toLong(), request.body.contentLength)

        val sentBody = drainToBytes { (request.body as OutgoingContent.WriteChannelContent).writeTo(it) }
        assertContentEquals(body, sentBody)
    }

    /** 添付情報を持たない応答は BlobTransportException。 */
    @Test
    fun uploadWithoutAttachmentFails() = runTest {
        val transport = transportFor {
            respond(content = """{"id":"x"}""", status = HttpStatusCode.OK)
        }
        assertFailsWith<BlobTransportException> {
            transport.upload("t", "b", 0) { }
        }
    }

    /** upload が 2xx 以外なら status 付きの BlobTransportException。 */
    @Test
    fun uploadThrowsOnNonSuccess() = runTest {
        val transport = transportFor {
            respond(content = "", status = HttpStatusCode.Forbidden)
        }
        val error = assertFailsWith<BlobTransportException> {
            transport.upload("t", "b", 0) { }
        }
        assertEquals(403, error.status)
    }

    /** download は GET で認証を付け、本文チャンネルを返す。 */
    @Test
    fun downloadGetsWithAuthAndReturnsBody() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val body = ByteArray(256) { it.toByte() }
        val transport = transportFor(captured = captured) {
            respond(content = body, status = HttpStatusCode.OK)
        }
        val downloaded = transport.download("http://localhost:8090/file/abc").readRemaining().readByteArray()
        assertContentEquals(body, downloaded)

        val request = captured.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    /** download が 2xx 以外なら status 付きの BlobTransportException。 */
    @Test
    fun downloadThrowsOnNonSuccess() = runTest {
        val transport = transportFor {
            respond(content = "", status = HttpStatusCode.NotFound)
        }
        val error = assertFailsWith<BlobTransportException> {
            transport.download("http://localhost:8090/file/missing")
        }
        assertEquals(404, error.status)
    }

    /** download は config.host と異なるホストの URL を拒否し、Bearer トークンを含むリクエストを送らない。 */
    @Test
    fun downloadRejectsMismatchedHost() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val transport = transportFor(captured = captured) {
            respond(content = "", status = HttpStatusCode.OK)
        }
        assertFailsWith<BlobTransportException> {
            transport.download("http://evil.example.com:8090/file/abc")
        }
        assertEquals(emptyList(), captured)
    }
}
