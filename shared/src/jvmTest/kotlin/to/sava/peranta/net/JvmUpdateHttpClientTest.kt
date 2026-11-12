package to.sava.peranta.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class JvmUpdateHttpClientTest {

    /** Desktop の自己更新用クライアントは、自己更新用の設定（[configureUpdateClient]）を通って作られる（§12）。 */
    @Test
    fun updateClientIsConfiguredForUpdates() = runTest {
        createUpdateHttpClient().use { client -> assertUpdateClientConfigured(client) }
    }
}
