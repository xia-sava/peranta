package to.sava.peranta.update

import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.net.httpBaseUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateUrlTest {

    /** TLS 有効・ポート省略なら https の既定ポート権威部で latest.json URL を組む。 */
    @Test
    fun derivesHttpsUrlWithDefaultPort() {
        val config = PerantaConfig(host = "peranta.sava.to", useTls = true, port = null)

        assertEquals("https://peranta.sava.to", config.httpBaseUrl())
        assertEquals("https://peranta.sava.to/dist/latest.json", latestManifestUrl(config))
    }

    /** TLS 無効・ポート指定なら http で host:port の権威部になる（開発時のローカル配信）。 */
    @Test
    fun derivesHttpUrlWithExplicitPort() {
        val config = PerantaConfig(host = "localhost", useTls = false, port = 8091)

        assertEquals("http://localhost:8091", config.httpBaseUrl())
        assertEquals("http://localhost:8091/dist/latest.json", latestManifestUrl(config))
    }
}
