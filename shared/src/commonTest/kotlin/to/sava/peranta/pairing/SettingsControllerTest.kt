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

    /** 既定値を埋めた接続設定の保存。各テストは検証したい項目だけを渡す。 */
    private fun SettingsController.saveConnection(
        host: String = "example.test",
        accessToken: String? = "tk",
        deviceName: String? = "desktop-1",
        port: Int? = null,
        persistSensitiveHistory: Boolean = false,
        attachFullTextWhenTruncated: Boolean = true,
        timelineRetentionDays: Int? = null,
        autoDisplayImages: Boolean = true,
        attachNotificationImages: Boolean = true,
        verboseLogging: Boolean = false,
    ) = saveConnectionSettings(
        host = host,
        accessToken = accessToken,
        deviceName = deviceName,
        port = port,
        persistSensitiveHistory = persistSensitiveHistory,
        attachFullTextWhenTruncated = attachFullTextWhenTruncated,
        timelineRetentionDays = timelineRetentionDays,
        autoDisplayImages = autoDisplayImages,
        attachNotificationImages = attachNotificationImages,
        verboseLogging = verboseLogging,
    )

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

    /** 接続設定の保存: 値がリポジトリへ反映され、TLS は常に有効として保存される。 */
    @Test
    fun saveConnectionSettingsPersistsValues() {
        val (controller, repo) = controllerWith()

        controller.saveConnection(port = 8090)

        val loaded = repo.load()
        assertEquals("example.test", loaded.host)
        assertEquals("tk", loaded.accessToken)
        assertEquals("desktop-1", loaded.deviceName)
        assertTrue(loaded.useTls)
        assertEquals(8090, loaded.port)
    }

    /** 既存レコードに useTls=false が残っていても、保存を通じて true へ正規化される。 */
    @Test
    fun saveConnectionSettingsNormalizesTlsToTrue() {
        val (controller, repo) = controllerWith(PerantaConfig(useTls = false))

        controller.saveConnection()

        assertTrue(repo.load().useTls)
    }

    /** 空文字の token/端末名/port は未設定（null）として保存される。 */
    @Test
    fun saveConnectionSettingsNormalizesBlanks() {
        val (controller, repo) = controllerWith()

        controller.saveConnection(accessToken = "", deviceName = "  ")

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

        controller.saveConnection(persistSensitiveHistory = true, attachFullTextWhenTruncated = false)

        val loaded = repo.load()
        assertTrue(loaded.persistSensitiveHistory)
        assertFalse(loaded.attachFullTextWhenTruncated)

        controller.saveConnection(persistSensitiveHistory = false, attachFullTextWhenTruncated = true)

        val revertedLoaded = repo.load()
        assertFalse(revertedLoaded.persistSensitiveHistory)
        assertTrue(revertedLoaded.attachFullTextWhenTruncated)
    }

    /** タイムライン保持日数（§11）の保存/読み込みラウンドトリップ。空欄相当の null は日数で剪定しない設定に戻る。 */
    @Test
    fun saveConnectionSettingsPersistsTimelineRetentionDays() {
        val (controller, repo) = controllerWith(PerantaConfig(timelineRetentionDays = null))

        controller.saveConnection(timelineRetentionDays = 30)

        assertEquals(30, repo.load().timelineRetentionDays)

        controller.saveConnection(timelineRetentionDays = null)

        assertNull(repo.load().timelineRetentionDays)
    }

    /** 画像の自動表示トグル（§4.3）の保存/読み込みラウンドトリップ。 */
    @Test
    fun saveConnectionSettingsPersistsAutoDisplayImages() {
        val (controller, repo) = controllerWith(PerantaConfig(autoDisplayImages = true))

        controller.saveConnection(autoDisplayImages = false)

        assertFalse(repo.load().autoDisplayImages)

        controller.saveConnection(autoDisplayImages = true)

        assertTrue(repo.load().autoDisplayImages)
    }

    /** 通知画像の転送トグル（§4.3.1）の保存/読み込みラウンドトリップ。 */
    @Test
    fun saveConnectionSettingsPersistsAttachNotificationImages() {
        val (controller, repo) = controllerWith(PerantaConfig(attachNotificationImages = true))

        controller.saveConnection(attachNotificationImages = false)

        assertFalse(repo.load().attachNotificationImages)

        controller.saveConnection(attachNotificationImages = true)

        assertTrue(repo.load().attachNotificationImages)
    }

    /** 詳細な記録トグル（§11）の保存/読み込みラウンドトリップ。既定は OFF。 */
    @Test
    fun saveConnectionSettingsPersistsVerboseLogging() {
        val (controller, repo) = controllerWith(PerantaConfig())

        assertFalse(repo.load().verboseLogging)

        controller.saveConnection(verboseLogging = true)

        assertTrue(repo.load().verboseLogging)

        controller.saveConnection(verboseLogging = false)

        assertFalse(repo.load().verboseLogging)
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
            PerantaConfig(host = "peranta.example.com", accessToken = "tk", useTls = true, port = 8443),
        )
        controller.rotateSharedKey()

        val uri = controller.buildPairingUri()

        assertTrue(uri != null)
        val decoded = PairingUri.decode(uri)
        val success = assertIs<PairingResult.Success>(decoded)
        assertEquals("peranta.example.com", success.data.host)
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

    /** 送信ロール設定の保存: sendEnabled/smsDirectReceive/仕事用プロファイルがリポジトリへ反映される。 */
    @Test
    fun saveSendRoleSettingsPersistsValues() {
        val (controller, repo) = controllerWith(
            PerantaConfig(sendEnabled = false, smsDirectReceive = true),
        )

        controller.saveSendRoleSettings(
            sendEnabled = true,
            smsDirectReceive = false,
            forwardWorkProfileNotifications = true,
        )

        val loaded = repo.load()
        assertTrue(loaded.sendEnabled)
        assertFalse(loaded.smsDirectReceive)
        assertTrue(loaded.forwardWorkProfileNotifications)
    }

    /** 送信ロール設定の保存: 他項目（host 等）は既存値を引き継ぐ。 */
    @Test
    fun saveSendRoleSettingsKeepsOtherFields() {
        val (controller, repo) = controllerWith(
            PerantaConfig(host = "example.test", accessToken = "tk"),
        )

        controller.saveSendRoleSettings(
            sendEnabled = true,
            smsDirectReceive = true,
            forwardWorkProfileNotifications = false,
        )

        val loaded = repo.load()
        assertEquals("example.test", loaded.host)
        assertEquals("tk", loaded.accessToken)
    }

    /** 初期設定完了判定: どのロールの readiness も満たさなければ未完了。 */
    @Test
    fun isSetupCompleteFalseWhenNothingReady() {
        val (controller, _) = controllerWith()
        assertFalse(controller.isSetupComplete())
    }

    /** 初期設定完了判定: UnifiedPush 受信ロールが成立すれば完了。 */
    @Test
    fun isSetupCompleteTrueWhenUnifiedPushReceiveReady() {
        val (controller, _) = controllerWith(
            PerantaConfig(
                deviceName = "tablet",
                sharedKeyBase64 = Base64.encode(ByteArray(32)),
                keyId = "1",
            ),
        )
        assertTrue(controller.isSetupComplete())
    }

    /**
     * 初期設定完了判定: 送信ロール（isReadyForSend）のみが成立すれば完了。
     * receiveTopic を持たないため isReadyForReceive は不成立、
     * host あり (token/鍵あり)なので isReadyForUnifiedPushReceive とは重複しない別枝であることを確認する。
     */
    @Test
    fun isSetupCompleteTrueWhenOnlySendReady() {
        val (controller, _) = controllerWith(
            PerantaConfig(
                host = "example.test",
                accessToken = "tk",
                deviceName = "desktop-1",
                sharedKeyBase64 = Base64.encode(ByteArray(32)),
                keyId = "1",
                deliveryTopics = listOf("delivery-topic"),
            ),
        )
        assertTrue(controller.isSetupComplete())
    }

    /**
     * 初期設定完了判定: 受信ロール（isReadyForReceive）のみが成立すれば完了。
     * receiveTopic を明示的に持つ点で、UnifiedPush 受信 readiness（receiveTopic 不問）とは別の枝である。
     */
    @Test
    fun isSetupCompleteTrueWhenOnlyReceiveReady() {
        val (controller, _) = controllerWith(
            PerantaConfig(
                host = "example.test",
                accessToken = "tk",
                deviceName = "desktop-1",
                sharedKeyBase64 = Base64.encode(ByteArray(32)),
                keyId = "1",
                receiveTopic = "receive-topic",
            ),
        )
        assertTrue(controller.isSetupComplete())
    }
}
