package to.sava.peranta.pairing

import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingImportControllerTest {

    private fun key(): ByteArray = ByteArray(32) { it.toByte() }

    private fun validUri(): String =
        PairingUri.encode(
            PairingData(
                host = "peranta.sava.to",
                token = "tk",
                keyId = "k5",
                key = key(),
                port = 8443,
                controlTopic = "peranta-control-abc",
            ),
        )

    /** 正しいペアリング URI を取り込むと設定へ適用され、keyId を伴う成功結果を返す。 */
    @Test
    fun importValidUriAppliesSettingsAndReportsKeyId() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)
        val controller = PairingImportController(repo)

        val result = controller.import(validUri())

        val applied = assertIs<PairingImportResult.Applied>(result)
        assertEquals("k5", applied.keyId)

        val loaded = repo.load()
        assertEquals("peranta.sava.to", loaded.host)
        assertEquals("tk", loaded.accessToken)
        assertEquals("k5", loaded.keyId)
        assertEquals(Base64.encode(key()), loaded.sharedKeyBase64)
        assertEquals(8443, loaded.port)
        assertEquals("peranta-control-abc", loaded.controlTopic)
    }

    /** 端末名が設定済みなら、取り込み後に UnifiedPush 受信ロールが成立する。 */
    @Test
    fun importMakesReceiveRoleReadyWhenDeviceNamePresent() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)
        repo.save(PerantaConfig(deviceName = "tablet"))
        val controller = PairingImportController(repo)

        controller.import(validUri())

        assertTrue(repo.load().hasSharedKey)
        assertTrue(repo.load().isReadyForUnifiedPushReceive)
    }

    /** 端末名を渡して取り込むと、共有鍵とともに端末名も設定へ適用される。 */
    @Test
    fun importAppliesDeviceNameWhenProvided() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings)
        val controller = PairingImportController(repo)

        controller.import(validUri(), deviceName = "phone-1")

        val loaded = repo.load()
        assertEquals("phone-1", loaded.deviceName)
        assertTrue(loaded.isReadyForUnifiedPushReceive)
    }

    /** 前後の空白付きで貼り付けられても除去して取り込める。 */
    @Test
    fun importTrimsSurroundingWhitespace() {
        val repo = ConfigRepository(MapSettings())
        val controller = PairingImportController(repo)

        val result = controller.import("  ${validUri()}\n")

        assertIs<PairingImportResult.Applied>(result)
    }

    /** 復号に失敗する文字列は設定を変更せず、失敗理由を返す。 */
    @Test
    fun importInvalidUriReportsReasonWithoutApplying() {
        val repo = ConfigRepository(MapSettings())
        val controller = PairingImportController(repo)

        val result = controller.import("https://example.com/not-a-pairing")

        val failed = assertIs<PairingImportResult.Failed>(result)
        assertEquals(PairingError.WrongScheme.reason, failed.reason)
        assertTrue(repo.load().sharedKeyBase64 == null)
    }

    /** 空文字は解析不能として失敗理由を返す。 */
    @Test
    fun importBlankReportsMalformedReason() {
        val repo = ConfigRepository(MapSettings())
        val controller = PairingImportController(repo)

        val result = controller.import("   ")

        val failed = assertIs<PairingImportResult.Failed>(result)
        assertEquals(PairingError.Malformed.reason, failed.reason)
    }
}
