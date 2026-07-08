package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.PerantaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KtorNtfyClientPublishTest {

    private fun clientFor(
        config: PerantaConfig,
        status: HttpStatusCode = HttpStatusCode.OK,
        captured: MutableList<HttpRequestData> = mutableListOf(),
    ): KtorNtfyClient {
        val engine = MockEngine { request ->
            captured.add(request)
            respond(content = "", status = status)
        }
        return KtorNtfyClient(config, HttpClient(engine))
    }

    /** publish は http スキーム・host:port の権威部で URL を組み、認証とキャッシュのヘッダと本文を送る。 */
    @Test
    fun publishBuildsHttpUrlWithHeadersAndBody() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(
            PerantaConfig(host = "localhost", useTls = false, port = 8090, accessToken = "tok"),
            captured = captured,
        )
        client.publish("my-topic", "hello", cacheSeconds = 30)

        val request = captured.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://localhost:8090/my-topic", request.url.toString())
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("30s", request.headers["Cache"])
        assertEquals("hello", (request.body as TextContent).text)
    }

    /** TLS 有効・port 未指定なら https スキームで既定ポートの権威部（host のみ）になる。 */
    @Test
    fun publishUsesHttpsAndDefaultPortWhenTlsEnabled() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(
            PerantaConfig(host = "peranta.example", useTls = true, port = null, accessToken = null),
            captured = captured,
        )
        client.publish("t", "b")

        val request = captured.single()
        assertEquals("https://peranta.example/t", request.url.toString())
    }

    /** accessToken 未設定なら Authorization ヘッダを付けず、cacheSeconds 未指定なら Cache ヘッダも付けない。 */
    @Test
    fun publishOmitsAuthAndCacheHeadersWhenAbsent() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(
            PerantaConfig(host = "h", useTls = false, port = 80, accessToken = null),
            captured = captured,
        )
        client.publish("t", "b", cacheSeconds = null)

        val request = captured.single()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cache"])
    }

    /** publish が 2xx 以外で返ると NtfyPublishException になり、status に応答コードを保持する。 */
    @Test
    fun publishThrowsOnNonSuccessStatus() = runTest {
        val client = clientFor(
            PerantaConfig(host = "h", useTls = false, port = 8090, accessToken = "tok"),
            status = HttpStatusCode.InternalServerError,
        )
        val error = assertFailsWith<NtfyPublishException> { client.publish("t", "b") }
        assertEquals(500, error.status)
    }
}
