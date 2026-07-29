package to.sava.peranta.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UpdateControllerTest {

    private val key = TestSigningKey()

    private val manifestJson = """
        { "desktop": { "versionCode": 20, "versionName": "2.0.0", "sha256": "d2" } }
    """.trimIndent()

    private fun manifestEngine(): MockEngine = signedManifestEngine(manifestJson, key.sign(manifestJson))

    private fun controller(scope: CoroutineScope, engine: MockEngine = manifestEngine()): UpdateController {
        val checker = UpdateChecker(HttpClient(engine), 1, PLATFORM_DESKTOP, publicKey = key.publicKey)
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

            assertEquals(UpdateStatus.Available("2.0.0", releaseAssetUrl("peranta.msi"), "d2"), status)
            assertFalse(controller.checking.value)
        } finally {
            scope.cancel()
        }
    }

    /** 実行中の多重 checkNow は無視され、確認は 1 回だけ走る（マニフェストと署名の 2 要求で 1 回）。 */
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

            assertEquals(UpdateStatus.Available("2.0.0", releaseAssetUrl("peranta.msi"), "d2"), status)
            assertFalse(controller.checking.value)
            assertEquals(2, engine.requestHistory.size)
        } finally {
            scope.cancel()
        }
    }
}
