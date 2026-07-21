package to.sava.peranta.pairing

import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingApplierTest {

    private fun key(): ByteArray = ByteArray(32) { it.toByte() }

    /** 受け取ったペアリング設定一式がローカル設定へ保存され、端末名は引き継がれる。 */
    @Test
    fun applyStoresPairedSettings() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)
        repo.save(PerantaConfig(deviceName = "tablet"))

        PairingApplier(repo).apply(
            PairingData(host = "peranta.sava.to", token = "tk", keyId = "k3", key = key(), port = 8443),
        )

        val loaded = repo.load()
        assertEquals("peranta.sava.to", loaded.host)
        assertEquals("tk", loaded.accessToken)
        assertEquals("k3", loaded.keyId)
        assertEquals(Base64.encode(key()), loaded.sharedKeyBase64)
        assertEquals(true, loaded.useTls)
        assertEquals(8443, loaded.port)
        assertEquals("tablet", loaded.deviceName)
    }

    /** 端末名が設定済みなら、適用後に UnifiedPush 受信ロールが成立する。 */
    @Test
    fun applyMakesReceiveRoleReady() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)
        repo.save(PerantaConfig(deviceName = "tablet"))

        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key()))

        assertTrue(repo.load().isReadyForUnifiedPushReceive)
    }

    /** 共有鍵は KeyStore シーム経由で保存され、別インスタンスの設定層からも読み出せる。 */
    @Test
    fun applyPersistsKeyThroughKeyStoreSeam() {
        val settings = MapSettings()
        PairingApplier(ConfigRepository(settings)).apply(
            PairingData("h", "tk", "k1", key(), port = null),
        )

        val reloaded = ConfigRepository(settings).load()
        assertEquals(Base64.encode(key()), reloaded.sharedKeyBase64)
        assertEquals(null, reloaded.port)
    }

    /** ペアリング適用は TLS 設定に触れず、開発向けリポジトリでは既存の保存値が保たれる（§16）。 */
    @Test
    fun applyKeepsStoredTls() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, forceTls = false)
        repo.save(PerantaConfig(useTls = true))

        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key(), port = null))

        assertEquals(true, repo.load().useTls)
    }

    /** 端末名を渡すと設定へ適用される。 */
    @Test
    fun applyAppliesDeviceNameWhenProvided() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)

        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key()), deviceName = "phone-1")

        assertEquals("phone-1", repo.load().deviceName)
    }

    /** 端末名に空文字を渡してもそのまま適用する（未入力でも許可）。 */
    @Test
    fun applyAppliesEmptyDeviceName() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)
        repo.save(PerantaConfig(deviceName = "old"))

        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key()), deviceName = "")

        assertEquals("", repo.load().deviceName)
    }

    /** 端末名を渡さなければ既存設定を引き継ぐ。 */
    @Test
    fun applyKeepsDeviceNameWhenNotProvided() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)
        repo.save(PerantaConfig(deviceName = "tablet"))

        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key()))

        assertEquals("tablet", repo.load().deviceName)
    }
}
