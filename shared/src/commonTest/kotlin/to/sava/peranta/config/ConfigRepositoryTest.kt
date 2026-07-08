package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.RuleAction
import to.sava.peranta.model.Priority
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** 送信ロール関連の項目（モード・配送先・ルール・フラグ）も往復する。 */
    @Test
    fun sendRoleFieldsRoundTrip() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        val config = PerantaConfig(
            deviceName = "phone",
            sendEnabled = true,
            smsDirectReceive = false,
            filterMode = FilterMode.ALLOWLIST,
            deliveryTopics = listOf("peranta-dev-desk-abc", "peranta-updXYZ"),
            filterRules = listOf(
                FilterRule("com.example.bank", RuleAction.INCLUDE, priorityOverride = Priority.HIGH),
                FilterRule("com.android.shell", RuleAction.INCLUDE, redact = true),
            ),
            persistSensitiveHistory = true,
            otpSenderPackages = listOf("com.example.bank", "com.example.auth"),
        )
        repo.save(config)
        val loaded = repo.load()
        assertTrue(loaded.sendEnabled)
        assertFalse(loaded.smsDirectReceive)
        assertEquals(FilterMode.ALLOWLIST, loaded.filterMode)
        assertEquals(config.deliveryTopics, loaded.deliveryTopics)
        assertEquals(config.filterRules, loaded.filterRules)
        assertTrue(loaded.persistSensitiveHistory)
        assertEquals(config.otpSenderPackages, loaded.otpSenderPackages)
    }

    /** 既定値の config を保存・読込すると送信ロールは無効・denylist・配送先空に戻る。 */
    @Test
    fun sendRoleDefaultsRoundTrip() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(PerantaConfig())
        val loaded = repo.load()
        assertFalse(loaded.sendEnabled)
        assertTrue(loaded.smsDirectReceive)
        assertEquals(FilterMode.DENYLIST, loaded.filterMode)
        assertTrue(loaded.deliveryTopics.isEmpty())
        assertTrue(loaded.filterRules.isEmpty())
        assertFalse(loaded.persistSensitiveHistory)
        assertTrue(loaded.otpSenderPackages.isEmpty())
        assertFalse(loaded.isReadyForSend)
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
