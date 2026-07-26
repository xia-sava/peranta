package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateCheckerTest {

    private fun jsonEngine(status: HttpStatusCode, body: String): HttpClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return HttpClient(engine)
    }

    private val manifestJson = """
        {
          "android": { "versionCode": 20, "versionName": "2.0.0", "url": "http://h/a.apk" },
          "desktop": { "versionCode": 20, "versionName": "2.0.0", "url": "http://h/d.msi" }
        }
    """.trimIndent()

    /** 配布物の versionCode が自分より大きければ Available になり、名前と URL を保持する。 */
    @Test
    fun availableWhenServerVersionIsHigher() = runTest {
        val checker = UpdateChecker(jsonEngine(HttpStatusCode.OK, manifestJson), 1, PLATFORM_DESKTOP)

        val status = checker.check()

        assertEquals(UpdateStatus.Available("2.0.0", "http://h/d.msi"), status)
    }

    /** 配布物の versionCode が自分と同じなら UpToDate（大きい時だけ更新）。 */
    @Test
    fun upToDateWhenServerVersionEquals() = runTest {
        val checker = UpdateChecker(jsonEngine(HttpStatusCode.OK, manifestJson), 20, PLATFORM_DESKTOP)

        assertEquals(UpdateStatus.UpToDate, checker.check())
    }

    /** 配布物の versionCode が自分より小さくても UpToDate。 */
    @Test
    fun upToDateWhenServerVersionIsLower() = runTest {
        val checker = UpdateChecker(jsonEngine(HttpStatusCode.OK, manifestJson), 999, PLATFORM_ANDROID)

        assertEquals(UpdateStatus.UpToDate, checker.check())
    }

    /** HTTP が 2xx 以外なら理由に応答コードを添えて Failed。 */
    @Test
    fun failedOnNonSuccessStatus() = runTest {
        val checker = UpdateChecker(jsonEngine(HttpStatusCode.NotFound, ""), 1, PLATFORM_DESKTOP)

        val status = checker.check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("404"))
    }

    /** JSON として壊れていれば解析失敗の Failed。 */
    @Test
    fun failedOnInvalidJson() = runTest {
        val checker = UpdateChecker(jsonEngine(HttpStatusCode.OK, "not json {"), 1, PLATFORM_DESKTOP)

        val status = checker.check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("解析"))
    }

    /** 自プラットフォームのキーが欠けていれば、理由にキー名を添えて Failed。 */
    @Test
    fun failedWhenPlatformKeyMissing() = runTest {
        val androidOnly = """
            { "android": { "versionCode": 20, "versionName": "2.0.0", "url": "http://h/a.apk" } }
        """.trimIndent()
        val checker = UpdateChecker(jsonEngine(HttpStatusCode.OK, androidOnly), 1, PLATFORM_DESKTOP)

        val status = checker.check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains(PLATFORM_DESKTOP))
    }

    /** ネットワーク例外は握り潰さず Failed に変換する。 */
    @Test
    fun failedOnNetworkException() = runTest {
        val engine = MockEngine { throw IOException("connection refused") }
        val checker = UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP)

        val status = checker.check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("取得"))
    }

    /** 取得先を明示すればその URL を引く（開発時にローカルの配信口を指せる）。 */
    @Test
    fun fetchesFromGivenManifestUrl() = runTest {
        val localUrl = "http://localhost:8091/dist/latest.json"
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond(
                content = manifestJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP, localUrl).check()

        assertEquals(localUrl, requested)
    }
}
