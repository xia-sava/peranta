package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import to.sava.peranta.loadDesktopConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevModeTest {

    /** システムプロパティを一時設定し、テスト後に元へ戻す。 */
    private fun withProperty(name: String, value: String, block: () -> Unit) {
        val previous = System.getProperty(name)
        System.setProperty(name, value)
        try {
            block()
        } finally {
            previous?.let { System.setProperty(name, it) } ?: System.clearProperty(name)
        }
    }

    private fun settingsWith(config: PerantaConfig): MapSettings =
        MapSettings().also { ConfigRepository(it).save(config) }

    /** isDevMode は peranta.devMode が "true" のときだけ真になる。 */
    @Test
    fun isDevModeTrueOnlyWhenPropertyIsTrue() {
        val previous = System.getProperty(DEV_MODE_PROPERTY)
        try {
            System.setProperty(DEV_MODE_PROPERTY, "true")
            assertTrue(isDevMode())
            System.setProperty(DEV_MODE_PROPERTY, "false")
            assertFalse(isDevMode())
            System.clearProperty(DEV_MODE_PROPERTY)
            assertFalse(isDevMode())
        } finally {
            previous?.let { System.setProperty(DEV_MODE_PROPERTY, it) } ?: System.clearProperty(DEV_MODE_PROPERTY)
        }
    }

    /** devMode でないときは開発用オーバーライドを素通しし、TLS を強制する。 */
    @Test
    fun nonDevModeForcesTlsAndIgnoresOverrides() {
        val settings = settingsWith(PerantaConfig(host = "saved-host", useTls = false, deviceName = "desk"))

        withProperty("PERANTA_HOST", "override-host") {
            val config = loadDesktopConfig(settings, devMode = false)
            assertEquals(true, config.useTls)
            assertEquals("saved-host", config.host)
        }
    }

    /** 端末名が未設定でも（早期 return 経路でも）TLS を強制する。 */
    @Test
    fun nonDevModeForcesTlsWithoutDeviceName() {
        val settings = settingsWith(PerantaConfig(useTls = false))

        val config = loadDesktopConfig(settings, devMode = false)

        assertEquals(true, config.useTls)
        assertNull(config.deviceId)
    }

    /** devMode のときは開発用オーバーライドを適用し、TLS ダウングレードも効く。 */
    @Test
    fun devModeAppliesOverridesIncludingTlsDowngrade() {
        val settings = settingsWith(PerantaConfig(host = "saved-host", useTls = true, deviceName = "desk"))

        withProperty("PERANTA_HOST", "override-host") {
            withProperty("PERANTA_TLS", "false") {
                val config = loadDesktopConfig(settings, devMode = true)
                assertEquals("override-host", config.host)
                assertEquals(false, config.useTls)
            }
        }
    }
}
