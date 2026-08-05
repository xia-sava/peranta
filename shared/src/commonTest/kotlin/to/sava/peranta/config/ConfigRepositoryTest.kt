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

    /** save した設定が load で同じ値に戻り、共有鍵とアクセストークンも SecretStore 経由で往復する。 */
    @Test
    fun saveThenLoadRoundTrips() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings), forceTls = false)
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
            unifiedPushEndpoint = "https://peranta.example.com/UPabc123",
        )
        repo.save(config)
        assertEquals(config, repo.load())
        assertTrue(repo.load().isReadyForReceive)
    }

    /** clear は保存済みの設定と共有鍵を消し、load が初期値を返す状態に戻す（§11）。 */
    @Test
    fun clearRemovesEverySettingAndSharedKey() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings), forceTls = false)
        repo.save(
            PerantaConfig(
                host = "peranta.example.com",
                accessToken = "tok",
                deviceName = "desk",
                sharedKeyBase64 = Base64.encode(generateKey()),
                keyId = "k1",
            ),
        )

        repo.clear()

        val cleared = repo.load()
        assertNull(cleared.accessToken)
        assertNull(cleared.deviceName)
        assertNull(cleared.sharedKeyBase64)
        assertNull(cleared.keyId)
        assertFalse(cleared.hasSharedKey)
        assertEquals(PerantaConfig().host, cleared.host)
    }

    /** 秘密の保管先が settings の外にある実装でも、clear は SecretStore へ消去を伝える（§11）。 */
    @Test
    fun clearAlsoClearsSecretsOutsideSettings() {
        val settings = MapSettings()
        val secretStore = SettingsSecretStore(MapSettings())
        val repo = ConfigRepository(settings, secretStore, forceTls = false)
        repo.save(
            PerantaConfig(
                accessToken = "tok",
                sharedKeyBase64 = Base64.encode(generateKey()),
                keyId = "k1",
            ),
        )

        repo.clear()

        assertNull(secretStore.loadSecret(SECRET_SHARED_KEY))
        assertNull(secretStore.loadSecret(SECRET_ACCESS_TOKEN))
    }

    /**
     * 素のまま保存する実装では、旧版が settings へ直に書いた共有鍵・アクセストークンを
     * そのまま読み出せる（保存形式を変えていない、§11）。
     */
    @Test
    fun loadsSecretsWrittenDirectlyIntoSettings() {
        val key = Base64.encode(generateKey())
        val settings = MapSettings()
        settings.putString(SECRET_SHARED_KEY, key)
        settings.putString(SECRET_ACCESS_TOKEN, "tok")
        val repo = ConfigRepository(settings, SettingsSecretStore(settings), forceTls = false)

        val loaded = repo.load()

        assertEquals(key, loaded.sharedKeyBase64)
        assertEquals("tok", loaded.accessToken)
    }

    /** リリース相当（forceTls）では TLS を常に有効として読み出し、保存値も書かない（§16）。 */
    @Test
    fun forceTlsAlwaysLoadsTrueAndSkipsPersisting() {
        val settings = MapSettings()
        val forced = ConfigRepository(settings, SettingsSecretStore(settings), forceTls = true)
        forced.save(PerantaConfig(useTls = false))
        assertTrue(forced.load().useTls)
        assertFalse(settings.hasKey(ConfigRepository.KEY_USE_TLS))
    }

    /** 開発相当（forceTls 無効）でも TLS の既定は有効で、明示して落としたときだけ無効になる（§16）。 */
    @Test
    fun devRepositoryDefaultsTlsOnAndHonorsStoredValue() {
        val settings = MapSettings()
        val dev = ConfigRepository(settings, SettingsSecretStore(settings), forceTls = false)
        assertTrue(dev.load().useTls)
        dev.save(PerantaConfig(useTls = false))
        assertFalse(dev.load().useTls)
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
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        repo.save(PerantaConfig(unifiedPushEndpoint = "https://peranta.example.com/UPxyz"))
        assertEquals("https://peranta.example.com/UPxyz", repo.load().unifiedPushEndpoint)
        repo.save(PerantaConfig(unifiedPushEndpoint = null))
        assertNull(repo.load().unifiedPushEndpoint)
    }

    /** ensureReceiveTopic は未設定なら端末名から topic を生成し、以後は同じ値を返す。 */
    @Test
    fun ensureReceiveTopicGeneratesAndPersists() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        val first = repo.ensureReceiveTopic("My Desk")
        assertTrue(first.startsWith("peranta-dev-my-desk-"), first)
        assertEquals(first, repo.ensureReceiveTopic("My Desk"))
    }

    /** 送信ロール関連の項目（モード・配送先・ルール・フラグ）も往復する。 */
    @Test
    fun sendRoleFieldsRoundTrip() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
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
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
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

    /** このアプリが読み書きしない設定キーは、次の保存で端末から取り除かれる。 */
    @Test
    fun droppedSettingKeysAreRemovedOnSave() {
        val settings = MapSettings()
        settings.putString("revokedDeviceIds", "dev-lost\ndev-old")
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))

        repo.save(PerantaConfig())

        assertFalse(settings.hasKey("revokedDeviceIds"))
    }

    /** 仕事用プロファイルの転送は save/load で往復し、既定は転送しない（§3.1）。 */
    @Test
    fun forwardWorkProfileNotificationsRoundTrips() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        repo.save(PerantaConfig())
        assertFalse(repo.load().forwardWorkProfileNotifications)
        repo.save(PerantaConfig(forwardWorkProfileNotifications = true))
        assertTrue(repo.load().forwardWorkProfileNotifications)
    }

    /** タイムライン保持日数は port と同じ optional int として save/load で往復し、未設定なら null に戻る（§11）。 */
    @Test
    fun timelineRetentionDaysRoundTrips() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        repo.save(PerantaConfig(timelineRetentionDays = 30))
        assertEquals(30, repo.load().timelineRetentionDays)
        repo.save(PerantaConfig(timelineRetentionDays = null))
        assertNull(repo.load().timelineRetentionDays)
    }

    /** 何も保存されていない端末（インストール直後）にはタイムライン保持日数の既定を与える（§11）。 */
    @Test
    fun timelineRetentionDaysDefaultsOnAFreshInstall() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))

        assertEquals(ConfigRepository.DEFAULT_TIMELINE_RETENTION_DAYS, repo.load().timelineRetentionDays)
    }

    /**
     * 既に使っている端末では保持日数を無制限のままにする（§11）。
     * 既定を有限にしたことで、保持日数を自分で決めていない利用者の履歴が消えてはいけない。
     */
    @Test
    fun timelineRetentionDaysStaysUnlimitedOnAnExistingInstall() {
        val settings = MapSettings()
        settings.putString(ConfigRepository.KEY_DEVICE_NAME, "phone")
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))

        assertNull(repo.load().timelineRetentionDays)
    }

    /** updateFilterRules は変換結果を filterRules だけへ反映し、他項目は保つ。 */
    @Test
    fun updateFilterRulesAppliesTransformAndKeepsOtherFields() = runTest {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
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
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
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
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
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
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        assertTrue(repo.load().attachFullTextWhenTruncated)
        repo.save(PerantaConfig(attachFullTextWhenTruncated = false))
        assertFalse(repo.load().attachFullTextWhenTruncated)
        repo.save(PerantaConfig(attachFullTextWhenTruncated = true))
        assertTrue(repo.load().attachFullTextWhenTruncated)
    }

    /** 画像の自動表示トグルは既定 true で、false に設定しても save/load で往復する（§4.3）。 */
    @Test
    fun autoDisplayImagesToggleRoundTrips() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        assertTrue(repo.load().autoDisplayImages)
        repo.save(PerantaConfig(autoDisplayImages = false))
        assertFalse(repo.load().autoDisplayImages)
        repo.save(PerantaConfig(autoDisplayImages = true))
        assertTrue(repo.load().autoDisplayImages)
    }

    /** 共有鍵未設定の config を保存すると鍵はクリアされ、load で null になる。 */
    @Test
    fun savingWithoutKeyClearsStoredKey() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        repo.save(PerantaConfig(sharedKeyBase64 = Base64.encode(generateKey())))
        repo.save(PerantaConfig(sharedKeyBase64 = null))
        assertNull(repo.load().sharedKeyBase64)
    }

    /** 受信テスト合格時のトークン指紋は未記録なら null で、記録すると load から読める（§10.6）。 */
    @Test
    fun selfTestPassFingerprintIsRecordedAndLoaded() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        assertNull(repo.load().selfTestPassedTokenFingerprint)

        repo.recordSelfTestPass("fp-1")

        assertEquals("fp-1", repo.load().selfTestPassedTokenFingerprint)
    }

    /** 指紋の記録は他の設定項目を巻き戻さず、記録済みの指紋は後からの save でも往復する。 */
    @Test
    fun recordingSelfTestPassKeepsOtherSettings() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        repo.save(PerantaConfig(host = "localhost", accessToken = "tok", deviceName = "desk"))

        repo.recordSelfTestPass("fp-1")

        val loaded = repo.load()
        assertEquals("localhost", loaded.host)
        assertEquals("desk", loaded.deviceName)
        assertEquals("fp-1", loaded.selfTestPassedTokenFingerprint)
        repo.save(loaded)
        assertEquals("fp-1", repo.load().selfTestPassedTokenFingerprint)
    }
}
