package to.sava.peranta.update

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import to.sava.peranta.net.createNtfyHttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ローカルの静的配信口（docker-compose の dist サービス、localhost:8091）に対する実 check。
 * server/dist/latest.json は versionCode を高く設定してあるため Available を期待する。
 * マニフェストは sha256 を必須とするので、配信する latest.json にも入れておく。
 * PERANTA_IT=1 のときだけ実行する。
 */
class UpdateCheckIntegrationTest {

    @Test
    fun availableFromLocalDist() {
        assumeTrue("PERANTA_IT!=1 のためスキップ", System.getenv("PERANTA_IT") == "1")

        runBlocking {
            val httpClient = createNtfyHttpClient()
            try {
                val checker = UpdateChecker(
                    httpClient,
                    currentVersionCode = 1,
                    platformKey = PLATFORM_DESKTOP,
                    manifestUrl = "http://localhost:8091/dist/latest.json",
                )

                val status = withTimeout(10_000) { checker.check() }

                assertTrue(status is UpdateStatus.Available, "expected Available but was $status")
            } finally {
                httpClient.close()
            }
        }
    }
}
