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

    /** publish は http スキーム・host:port の権威部で URL を組み、認証ヘッダと本文を送る。 */
    @Test
    fun publishBuildsHttpUrlWithHeadersAndBody() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(
            PerantaConfig(host = "localhost", useTls = false, port = 8090, accessToken = "tok"),
            captured = captured,
        )
        client.publish("my-topic", "hello")

        val request = captured.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://localhost:8090/my-topic", request.url.toString())
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("hello", (request.body as TextContent).text)
    }

    /** 既定の publish（cache = true）は Cache ヘッダを付けず、サーバの保持に従わせる。 */
    @Test
    fun publishOmitsCacheHeaderByDefault() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(
            PerantaConfig(host = "h", useTls = false, port = 80, accessToken = "tok"),
            captured = captured,
        )
        client.publish("t", "b")

        assertNull(captured.single().headers["Cache"])
    }

    /** cache = false の publish は `Cache: no` を付け、サーバに残さない。 */
    @Test
    fun publishSendsCacheNoWhenCacheDisabled() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(
            PerantaConfig(host = "h", useTls = false, port = 80, accessToken = "tok"),
            captured = captured,
        )
        client.publish("t", "b", cache = false)

        assertEquals("no", captured.single().headers["Cache"])
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

    /** accessToken 未設定なら Authorization ヘッダを付けない。 */
    @Test
    fun publishOmitsAuthHeaderWhenTokenAbsent() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(
            PerantaConfig(host = "h", useTls = false, port = 80, accessToken = null),
            captured = captured,
        )
        client.publish("t", "b")

        assertNull(captured.single().headers["Authorization"])
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
