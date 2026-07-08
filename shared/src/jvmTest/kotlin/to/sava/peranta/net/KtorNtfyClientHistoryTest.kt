package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.PerantaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorNtfyClientHistoryTest {

    private fun clientFor(
        body: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
        captured: MutableList<HttpRequestData> = mutableListOf(),
    ): NtfyClient {
        val engine = MockEngine { request ->
            captured.add(request)
            respond(content = body, status = status)
        }
        val config = PerantaConfig(host = "localhost", useTls = false, port = 8090, accessToken = "tok")
        return KtorNtfyClient(config, HttpClient(engine))
    }

    /** fetchHistory は poll+since クエリで URL を組み、認証ヘッダを付ける。 */
    @Test
    fun fetchHistoryBuildsPollUrlWithAuth() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = clientFor(captured = captured)
        client.fetchHistory("peranta-control-xyz")

        val request = captured.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("http://localhost:8090/peranta-control-xyz/json?poll=1&since=all", request.url.toString())
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    /** NDJSON の各行を解析し、message イベントだけを NtfyEvent として返す。 */
    @Test
    fun fetchHistoryParsesMessageLinesOnly() = runTest {
        val ndjson = buildString {
            appendLine("""{"id":"a","time":100,"event":"message","topic":"t","message":"env-a"}""")
            appendLine("""{"id":"b","time":110,"event":"keepalive","topic":"t"}""")
            appendLine("")
            appendLine("""{"id":"c","time":120,"event":"message","topic":"t","message":"env-c"}""")
        }
        val events = clientFor(body = ndjson).fetchHistory("t")
        assertEquals(listOf("env-a", "env-c"), events.map { it.message })
        assertEquals(listOf("a", "c"), events.map { it.id })
    }

    /** 2xx 以外は NtfyHistoryException になり、応答コードを保持する。 */
    @Test
    fun fetchHistoryThrowsOnNonSuccess() = runTest {
        val client = clientFor(status = HttpStatusCode.Unauthorized)
        val error = assertFailsWith<NtfyHistoryException> { client.fetchHistory("t") }
        assertEquals(401, error.status)
    }
}
