package to.sava.peranta.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 配布物の取得先。 */
private const val RELEASE_URL = "https://example.com/peranta.msi"

/**
 * 自己更新用の HTTP クライアントの設定（[configureUpdateClient]）を固定する（§12）。
 * 接続の確立と無通信だけを打ち切ること、リダイレクトは追うが https からの降格は追わないことを確かめる。
 */
class UpdateHttpTest {

    private fun updateClient(engine: MockEngine): HttpClient = HttpClient(engine) { configureUpdateClient() }

    /** 要求には接続の確立と無通信の打ち切りが付き、全体の所要時間は縛らない。 */
    @Test
    fun limitsConnectAndStallButNotWholeRequest() = runTest {
        var timeouts: HttpTimeoutConfig? = null
        val engine = MockEngine { request ->
            timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond(content = "", status = HttpStatusCode.OK)
        }

        updateClient(engine).get(RELEASE_URL)

        val applied = assertNotNull(timeouts, "update request carried no timeouts")
        assertEquals(EXPECTED_UPDATE_CONNECT_TIMEOUT_MILLIS, applied.connectTimeoutMillis)
        assertEquals(EXPECTED_UPDATE_STALL_TIMEOUT_MILLIS, applied.socketTimeoutMillis)
        assertNull(applied.requestTimeoutMillis)
    }

    /** https のままのリダイレクトは追い、移った先の応答を返す。 */
    @Test
    fun followsHttpsRedirect() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/peranta.msi") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://example.com/assets/peranta.msi"),
                )
            } else {
                respond(content = "", status = HttpStatusCode.OK)
            }
        }

        val response = updateClient(engine).get(RELEASE_URL)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, engine.requestHistory.size)
    }

    /** https から平文 http へのリダイレクトは追わず、移った先へ要求を出さない。 */
    @Test
    fun refusesHttpsDowngradeRedirect() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "http://example.com/assets/peranta.msi"),
            )
        }

        val response = updateClient(engine).get(RELEASE_URL)

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, engine.requestHistory.size)
    }
}
