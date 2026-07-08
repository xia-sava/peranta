package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import to.sava.peranta.config.PerantaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UpdateControllerTest {

    private val config = PerantaConfig(host = "localhost", useTls = false, port = 8091)

    private val manifestJson = """
        { "desktop": { "versionCode": 20, "versionName": "2.0.0", "url": "http://h/d.msi" } }
    """.trimIndent()

    private fun manifestEngine(): MockEngine = MockEngine {
        respond(
            content = manifestJson,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun controller(scope: CoroutineScope, engine: MockEngine = manifestEngine()): UpdateController {
        val checker = UpdateChecker(HttpClient(engine), config, 1, PLATFORM_DESKTOP)
        return UpdateController(checker, scope)
    }

    /** checkNow は結果を status へ反映し、完了後に checking を false へ戻す。 */
    @Test
    fun checkNowPublishesResultAndResetsChecking() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = controller(scope)
            controller.checkNow()

            val status = withTimeout(5_000) { controller.status.filterNotNull().first() }
            withTimeout(5_000) { controller.checking.first { !it } }

            assertEquals(UpdateStatus.Available("2.0.0", "http://h/d.msi"), status)
            assertFalse(controller.checking.value)
        } finally {
            scope.cancel()
        }
    }

    /** 実行中の多重 checkNow は無視され、HTTP リクエストも確認も 1 回だけ走る。 */
    @Test
    fun checkNowIgnoresConcurrentInvocation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = manifestEngine()
        try {
            val controller = controller(scope, engine)
            controller.checkNow()
            controller.checkNow()

            val status = withTimeout(5_000) { controller.status.filterNotNull().first() }
            withTimeout(5_000) { controller.checking.first { !it } }

            assertEquals(UpdateStatus.Available("2.0.0", "http://h/d.msi"), status)
            assertFalse(controller.checking.value)
            assertEquals(1, engine.requestHistory.size)
        } finally {
            scope.cancel()
        }
    }
}
