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

    private val key = TestSigningKey()

    private val manifestJson = """
        {
          "android": { "versionCode": 20, "versionName": "2.0.0", "url": "http://h/a.apk", "sha256": "a1" },
          "desktop": { "versionCode": 20, "versionName": "2.0.0", "url": "http://h/d.msi", "sha256": "d2" }
        }
    """.trimIndent()

    /** マニフェストとその署名を返す口を作り、テスト用の鍵を信頼の起点にした確認器を組む。 */
    private fun checker(
        json: String,
        currentVersionCode: Int = 1,
        platformKey: String = PLATFORM_DESKTOP,
        signature: String? = null,
    ): UpdateChecker {
        val engine = signedManifestEngine(json, signature ?: key.sign(json))
        return UpdateChecker(HttpClient(engine), currentVersionCode, platformKey, publicKey = key.publicKey)
    }

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

    /**
     * 配布物の versionCode が自分より大きければ Available になり、名前と照合値を保持する。
     * 取得先はマニフェストの指定ではなく固定の配布元から組む。
     */
    @Test
    fun availableWhenServerVersionIsHigher() = runTest {
        val status = checker(manifestJson).check()

        assertEquals(UpdateStatus.Available("2.0.0", releaseAssetUrl("peranta.msi"), "d2"), status)
    }

    /** sha256 を欠いたマニフェストは受理せず、解析失敗の Failed にする。 */
    @Test
    fun failedWhenDigestMissing() = runTest {
        val withoutDigest = """
            { "desktop": { "versionCode": 20, "versionName": "2.0.0" } }
        """.trimIndent()

        val status = checker(withoutDigest).check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("解析"))
    }

    /** 配布物の versionCode が自分と同じなら UpToDate（大きい時だけ更新）。 */
    @Test
    fun upToDateWhenServerVersionEquals() = runTest {
        assertEquals(UpdateStatus.UpToDate, checker(manifestJson, currentVersionCode = 20).check())
    }

    /** 配布物の versionCode が自分より小さくても UpToDate。 */
    @Test
    fun upToDateWhenServerVersionIsLower() = runTest {
        val checker = checker(manifestJson, currentVersionCode = 999, platformKey = PLATFORM_ANDROID)

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

    /** JSON として壊れていれば解析失敗の Failed（署名は通っている）。 */
    @Test
    fun failedOnInvalidJson() = runTest {
        val status = checker("not json {").check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("解析"))
    }

    /** 自プラットフォームのキーが欠けていれば、理由にキー名を添えて Failed。 */
    @Test
    fun failedWhenPlatformKeyMissing() = runTest {
        val androidOnly = """
            { "android": { "versionCode": 20, "versionName": "2.0.0", "sha256": "a1" } }
        """.trimIndent()

        val status = checker(androidOnly).check()

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

    /** 署名が取得できないマニフェストは受理しない（署名が無ければ通す経路を作らない）。 */
    @Test
    fun failedWhenSignatureIsMissing() = runTest {
        val engine = signedManifestEngine(manifestJson, signature = null)
        val checker = UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP, publicKey = key.publicKey)

        val status = checker.check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("署名"))
    }

    /** 署名を取ったあとに書き換えられたマニフェストは受理しない。 */
    @Test
    fun failedWhenManifestIsTampered() = runTest {
        val signature = key.sign(manifestJson)
        val tampered = manifestJson.replace("\"versionName\": \"2.0.0\"", "\"versionName\": \"9.9.9\"")

        val status = checker(tampered, signature = signature).check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("署名"))
    }

    /** 別の鍵で署名されたマニフェストは受理しない。 */
    @Test
    fun failedWhenSignedByAnotherKey() = runTest {
        val status = checker(manifestJson, signature = TestSigningKey().sign(manifestJson)).check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("署名"))
    }

    /** 埋め込みの公開鍵を使う既定の配線では、テスト鍵で署名したマニフェストも受理しない。 */
    @Test
    fun failedWithEmbeddedPublicKeyWhenSignedByTestKey() = runTest {
        val engine = signedManifestEngine(manifestJson, key.sign(manifestJson))
        val checker = UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP)

        val status = checker.check()

        assertTrue(status is UpdateStatus.Failed)
        assertTrue(status.reason.contains("署名"))
    }

    /** 取得先を明示すればその URL を引き、署名はその隣から引く（開発時にローカルの配信口を指せる）。 */
    @Test
    fun fetchesFromGivenManifestUrl() = runTest {
        val localUrl = "http://localhost:8091/dist/latest.json"
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            if (request.url.toString().endsWith(".sig")) {
                respond(content = key.sign(manifestJson), status = HttpStatusCode.OK)
            } else {
                respond(
                    content = manifestJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }

        UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP, localUrl, key.publicKey).check()

        assertEquals(listOf(localUrl, "$localUrl.sig"), requested)
    }
}
