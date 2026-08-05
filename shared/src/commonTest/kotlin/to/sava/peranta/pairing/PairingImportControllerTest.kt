package to.sava.peranta.pairing

import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingImportControllerTest {

    private fun key(): ByteArray = ByteArray(32) { it.toByte() }

    private fun validUri(): String =
        PairingUri.encode(
            PairingData(
                host = "peranta.example.com",
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
        assertEquals("peranta.example.com", loaded.host)
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

    /** 端末名の有無は、入力の有無ではなく取り込み後の設定から判定する（既存値の引き継ぎも有りと数える）。 */
    @Test
    fun appliedReportsDeviceNameOfResultingConfig() {
        val existing = ConfigRepository(MapSettings())
        existing.save(PerantaConfig(deviceName = "tablet"))
        val inherited = assertIs<PairingImportResult.Applied>(PairingImportController(existing).import(validUri()))
        assertTrue(inherited.hasDeviceName)

        val entered = ConfigRepository(MapSettings())
        val provided = assertIs<PairingImportResult.Applied>(
            PairingImportController(entered).import(validUri(), deviceName = "phone-1"),
        )
        assertTrue(provided.hasDeviceName)
    }

    /** 端末名を持たない端末が空欄のまま取り込むと、端末名なしとして報告する。 */
    @Test
    fun appliedReportsMissingDeviceNameWhenNeitherEnteredNorStored() {
        val repo = ConfigRepository(MapSettings())

        val result = assertIs<PairingImportResult.Applied>(PairingImportController(repo).import(validUri()))

        assertFalse(result.hasDeviceName)
    }

    /** 空文字を入力しての取り込みは端末名を消すため、端末名なしとして報告する。 */
    @Test
    fun appliedReportsMissingDeviceNameWhenClearedByEmptyInput() {
        val repo = ConfigRepository(MapSettings())
        repo.save(PerantaConfig(deviceName = "tablet"))

        val result = assertIs<PairingImportResult.Applied>(
            PairingImportController(repo).import(validUri(), deviceName = ""),
        )

        assertFalse(result.hasDeviceName)
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
