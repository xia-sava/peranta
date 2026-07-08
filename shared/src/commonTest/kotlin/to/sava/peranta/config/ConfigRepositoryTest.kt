package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import to.sava.peranta.crypto.generateKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigRepositoryTest {

    /** save した設定が load で同じ値に戻り、共有鍵も KeyStore 経由で往復する。 */
    @Test
    fun saveThenLoadRoundTrips() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        val key = Base64.encode(generateKey())
        val config = PerantaConfig(
            host = "localhost",
            useTls = false,
            port = 8090,
            accessToken = "tok",
            deviceName = "desk",
            sharedKeyBase64 = key,
            keyId = "k1",
            receiveTopic = "peranta-dev-desk-abc",
        )
        repo.save(config)
        assertEquals(config, repo.load())
        assertTrue(repo.load().isReadyForReceive)
    }

    /** ensureReceiveTopic は未設定なら端末名から topic を生成し、以後は同じ値を返す。 */
    @Test
    fun ensureReceiveTopicGeneratesAndPersists() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        val first = repo.ensureReceiveTopic("My Desk")
        assertTrue(first.startsWith("peranta-dev-my-desk-"), first)
        assertEquals(first, repo.ensureReceiveTopic("My Desk"))
    }

    /** 共有鍵未設定の config を保存すると鍵はクリアされ、load で null になる。 */
    @Test
    fun savingWithoutKeyClearsStoredKey() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(PerantaConfig(sharedKeyBase64 = Base64.encode(generateKey())))
        repo.save(PerantaConfig(sharedKeyBase64 = null))
        assertNull(repo.load().sharedKeyBase64)
    }
}
