package to.sava.peranta.config

import kotlin.test.Test
import kotlin.test.assertEquals

class DevConfigOverridesTest {

    private val base = PerantaConfig(
        host = "base-host",
        useTls = true,
        port = 443,
        accessToken = "base-token",
        deviceName = "base-device",
        sharedKeyBase64 = "base-key",
        keyId = "base-key-id",
        receiveTopic = "base-topic",
    )

    /** すべてのキーに値がある lookup は全項目を上書きする。 */
    @Test
    fun overridesAllFieldsWhenLookupProvidesValues() {
        val overrides = mapOf(
            "PERANTA_HOST" to "override-host",
            "PERANTA_PORT" to "9999",
            "PERANTA_TLS" to "false",
            "PERANTA_TOKEN" to "override-token",
            "PERANTA_KEY" to "override-key",
            "PERANTA_KEY_ID" to "override-key-id",
            "PERANTA_TOPIC" to "override-topic",
            "PERANTA_DEVICE" to "override-device",
        )
        val result = base.withDevOverrides { overrides[it] }
        assertEquals(
            PerantaConfig(
                host = "override-host",
                useTls = false,
                port = 9999,
                accessToken = "override-token",
                deviceName = "override-device",
                sharedKeyBase64 = "override-key",
                keyId = "override-key-id",
                receiveTopic = "override-topic",
            ),
            result,
        )
    }

    /** どのキーも解決しない lookup は元の設定をそのまま保つ。 */
    @Test
    fun keepsOriginalWhenLookupResolvesNothing() {
        assertEquals(base, base.withDevOverrides { null })
    }

    /** PERANTA_PORT が数値に解釈できないときは元の port を保つ。 */
    @Test
    fun invalidPortFallsBackToOriginal() {
        val result = base.withDevOverrides { key -> if (key == "PERANTA_PORT") "not-a-number" else null }
        assertEquals(443, result.port)
    }

    /** PERANTA_TLS が真偽値に解釈できないときは元の useTls を保つ。 */
    @Test
    fun invalidTlsFallsBackToOriginal() {
        val result = base.withDevOverrides { key -> if (key == "PERANTA_TLS") "maybe" else null }
        assertEquals(true, result.useTls)
    }

    /** 既定 lookup（envOrProperty）は環境変数が無ければ同名のシステムプロパティを引く。 */
    @Test
    fun envOrPropertyFallsBackToSystemProperty() {
        val name = "PERANTA_HOST"
        val previous = System.getProperty(name)
        System.setProperty(name, "prop-host")
        try {
            assertEquals("prop-host", envOrProperty(name))
            assertEquals("prop-host", base.withDevOverrides().host)
        } finally {
            previous?.let { System.setProperty(name, it) } ?: System.clearProperty(name)
        }
    }
}
