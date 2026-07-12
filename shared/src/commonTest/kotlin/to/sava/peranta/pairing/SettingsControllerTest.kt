package to.sava.peranta.pairing

import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsControllerTest {

    private fun controllerWith(config: PerantaConfig? = null): Pair<SettingsController, ConfigRepository> {
        val repo = ConfigRepository(MapSettings())
        config?.let { repo.save(it) }
        return SettingsController(repo) to repo
    }

    /** keyId 採番: 未設定・非数値・1 未満は "1"、正の整数はその +1。 */
    @Test
    fun nextKeyIdCoversBoundaries() {
        assertEquals("1", nextKeyId(null))
        assertEquals("1", nextKeyId(""))
        assertEquals("1", nextKeyId("   "))
        assertEquals("1", nextKeyId("abc"))
        assertEquals("1", nextKeyId("0"))
        assertEquals("1", nextKeyId("-3"))
        assertEquals("2", nextKeyId("1"))
        assertEquals("10", nextKeyId("9"))
        assertEquals("101", nextKeyId(" 100 "))
    }

    /** 接続設定の保存: 値がリポジトリへ反映され、空文字の token/端末名は null になる。 */
    @Test
    fun saveConnectionSettingsPersistsValues() {
        val (controller, repo) = controllerWith()

        controller.saveConnectionSettings(
            host = "example.test",
            accessToken = "tk",
            deviceName = "desktop-1",
            useTls = false,
            port = 8090,
            persistSensitiveHistory = false,
            attachFullTextWhenTruncated = true,
        )

        val loaded = repo.load()
        assertEquals("example.test", loaded.host)
        assertEquals("tk", loaded.accessToken)
        assertEquals("desktop-1", loaded.deviceName)
        assertEquals(false, loaded.useTls)
        assertEquals(8090, loaded.port)
    }

    /** 空文字の token/端末名/port は未設定（null）として保存される。 */
    @Test
    fun saveConnectionSettingsNormalizesBlanks() {
        val (controller, repo) = controllerWith()

        controller.saveConnectionSettings(
            host = "example.test",
            accessToken = "",
            deviceName = "  ",
            useTls = true,
            port = null,
            persistSensitiveHistory = false,
            attachFullTextWhenTruncated = true,
        )

        val loaded = repo.load()
        assertNull(loaded.accessToken)
        assertNull(loaded.deviceName)
        assertNull(loaded.port)
    }

    /** persistSensitiveHistory・attachFullTextWhenTruncated の保存/読み込みラウンドトリップ。 */
    @Test
    fun saveConnectionSettingsPersistsSensitiveHistoryAndFullTextToggles() {
        val (controller, repo) = controllerWith(
            PerantaConfig(persistSensitiveHistory = false, attachFullTextWhenTruncated = true),
        )

        controller.saveConnectionSettings(
            host = "example.test",
            accessToken = "tk",
            deviceName = "desktop-1",
            useTls = true,
            port = null,
            persistSensitiveHistory = true,
            attachFullTextWhenTruncated = false,
        )

        val loaded = repo.load()
        assertTrue(loaded.persistSensitiveHistory)
        assertFalse(loaded.attachFullTextWhenTruncated)

        controller.saveConnectionSettings(
            host = "example.test",
            accessToken = "tk",
            deviceName = "desktop-1",
            useTls = true,
            port = null,
            persistSensitiveHistory = false,
            attachFullTextWhenTruncated = true,
        )

        val revertedLoaded = repo.load()
        assertFalse(revertedLoaded.persistSensitiveHistory)
        assertTrue(revertedLoaded.attachFullTextWhenTruncated)
    }

    /** 鍵未設定からの作成: 32 バイト鍵が入り keyId は "1" になる。 */
    @Test
    fun rotateSharedKeyFromEmptyStartsAtOne() {
        val (controller, repo) = controllerWith()
        assertFalse(controller.hasSharedKey())

        val updated = controller.rotateSharedKey()

        assertEquals("1", updated.keyId)
        assertTrue(controller.hasSharedKey())
        val key = Base64.decode(repo.load().sharedKeyBase64!!)
        assertEquals(32, key.size)
    }

    /** 既存鍵からの作り直し: 鍵の実体が変わり keyId が +1 される。 */
    @Test
    fun rotateSharedKeyReplacesExistingKeyAndIncrementsKeyId() {
        val (controller, repo) = controllerWith()
        val first = controller.rotateSharedKey()

        val second = controller.rotateSharedKey()

        assertEquals("1", first.keyId)
        assertEquals("2", second.keyId)
        assertNotEquals(first.sharedKeyBase64, second.sharedKeyBase64)
        assertEquals(second.sharedKeyBase64, repo.load().sharedKeyBase64)
    }

    /** ペアリング URI: 設定が揃えば復号可能な URI を生成し、control topic を永続化する。 */
    @Test
    fun buildPairingUriProducesDecodableUri() {
        val (controller, repo) = controllerWith(
            PerantaConfig(host = "peranta.sava.to", accessToken = "tk", useTls = true, port = 8443),
        )
        controller.rotateSharedKey()

        val uri = controller.buildPairingUri()

        assertTrue(uri != null)
        val decoded = PairingUri.decode(uri)
        val success = assertIs<PairingResult.Success>(decoded)
        assertEquals("peranta.sava.to", success.data.host)
        assertEquals("tk", success.data.token)
        assertEquals("1", success.data.keyId)
        assertEquals(8443, success.data.port)
        assertTrue(!success.data.controlTopic.isNullOrBlank())
        assertEquals(success.data.controlTopic, repo.load().controlTopic)
    }

    /** ペアリング URI: token または鍵が欠けると生成できず null。 */
    @Test
    fun buildPairingUriReturnsNullWhenIncomplete() {
        val (noToken, _) = controllerWith(PerantaConfig(sharedKeyBase64 = Base64.encode(ByteArray(32)), keyId = "1"))
        assertNull(noToken.buildPairingUri())

        val (noKey, _) = controllerWith(PerantaConfig(accessToken = "tk"))
        assertNull(noKey.buildPairingUri())
    }
}
