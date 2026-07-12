package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.RuleAction
import to.sava.peranta.filter.mutePackage
import to.sava.peranta.model.Priority
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
            unifiedPushEndpoint = "https://peranta.sava.to/UPabc123",
        )
        repo.save(config)
        assertEquals(config, repo.load())
        assertTrue(repo.load().isReadyForReceive)
    }

    /** UnifiedPush 受信は端末名・共有鍵・keyId が揃えば成立し、topic/host は要件に含めない。 */
    @Test
    fun unifiedPushReceiveReadinessNeedsOnlyDecryptEssentials() {
        val ready = PerantaConfig(
            deviceName = "tablet",
            sharedKeyBase64 = Base64.encode(generateKey()),
            keyId = "k1",
        )
        assertTrue(ready.isReadyForUnifiedPushReceive)
        assertFalse(ready.copy(deviceName = null).isReadyForUnifiedPushReceive)
        assertFalse(ready.copy(sharedKeyBase64 = null).isReadyForUnifiedPushReceive)
        assertFalse(ready.copy(keyId = null).isReadyForUnifiedPushReceive)
    }

    /** UnifiedPush エンドポイントは save/load で往復し、未設定なら null に戻る。 */
    @Test
    fun unifiedPushEndpointRoundTrips() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(PerantaConfig(unifiedPushEndpoint = "https://peranta.sava.to/UPxyz"))
        assertEquals("https://peranta.sava.to/UPxyz", repo.load().unifiedPushEndpoint)
        repo.save(PerantaConfig(unifiedPushEndpoint = null))
        assertNull(repo.load().unifiedPushEndpoint)
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

    /** 失効させた deviceId 集合は save/load で往復し、未設定なら空集合に戻る（§9）。 */
    @Test
    fun revokedDeviceIdsRoundTrip() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(PerantaConfig(revokedDeviceIds = setOf("dev-lost", "dev-old")))
        assertEquals(setOf("dev-lost", "dev-old"), repo.load().revokedDeviceIds)
        repo.save(PerantaConfig())
        assertTrue(repo.load().revokedDeviceIds.isEmpty())
    }

    /** updateFilterRules は変換結果を filterRules だけへ反映し、他項目は保つ。 */
    @Test
    fun updateFilterRulesAppliesTransformAndKeepsOtherFields() = runTest {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(PerantaConfig(deviceName = "phone", sendEnabled = true))
        val updated = repo.updateFilterRules { rules -> mutePackage(rules, "com.spam") }
        assertEquals(listOf(FilterRule("com.spam", RuleAction.EXCLUDE)), updated)
        val loaded = repo.load()
        assertEquals(listOf(FilterRule("com.spam", RuleAction.EXCLUDE)), loaded.filterRules)
        assertEquals("phone", loaded.deviceName)
        assertTrue(loaded.sendEnabled)
    }

    /** 変換が同じインスタンスを返したときは書き込まず、その一覧をそのまま返す。 */
    @Test
    fun updateFilterRulesSkipsWriteWhenUnchanged() = runTest {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(PerantaConfig(filterRules = listOf(FilterRule("com.spam", RuleAction.EXCLUDE))))
        var received: List<FilterRule>? = null
        val result = repo.updateFilterRules { rules ->
            received = rules
            rules
        }
        assertSame(received, result)
        assertEquals(listOf(FilterRule("com.spam", RuleAction.EXCLUDE)), repo.load().filterRules)
    }

    /**
     * save と updateFilterRules を実スレッドで並行に呼び出しても、共有ロックにより書き込みが直列化され、
     * 例外や壊れた（デコード不能な）状態にならない。
     */
    @Test
    fun saveAndUpdateFilterRulesShareExclusionUnderConcurrency() = runTest {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        val iterations = 200

        coroutineScope {
            launch(Dispatchers.Default) {
                repeat(iterations) { i -> repo.save(PerantaConfig(deviceName = "phone-$i")) }
            }
            launch(Dispatchers.Default) {
                repeat(iterations) { i -> repo.updateFilterRules { rules -> mutePackage(rules, "com.spam$i") } }
            }
        }

        val loaded = repo.load()
        assertTrue(loaded.deviceName?.startsWith("phone-") == true)
        assertTrue(loaded.filterRules.all { it.action == RuleAction.EXCLUDE })
    }

    /** 全文添付トグルは既定 true で、false に設定しても save/load で往復する（§4.3）。 */
    @Test
    fun attachFullTextToggleRoundTrips() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        assertTrue(repo.load().attachFullTextWhenTruncated)
        repo.save(PerantaConfig(attachFullTextWhenTruncated = false))
        assertFalse(repo.load().attachFullTextWhenTruncated)
        repo.save(PerantaConfig(attachFullTextWhenTruncated = true))
        assertTrue(repo.load().attachFullTextWhenTruncated)
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
