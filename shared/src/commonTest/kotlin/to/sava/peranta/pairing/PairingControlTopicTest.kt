package to.sava.peranta.pairing

import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.SettingsKeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingControlTopicTest {

    private fun key(): ByteArray = ByteArray(32) { it.toByte() }

    private fun decodeSuccess(uri: String): PairingData =
        (PairingUri.decode(uri) as PairingResult.Success).data

    /** control topic を含む PairingData は URI へ符号化して往復する。 */
    @Test
    fun controlTopicRoundTripsThroughUri() {
        val data = PairingData("h", "tk", "k1", key(), controlTopic = "peranta-control-xyz")
        assertEquals(data, decodeSuccess(PairingUri.encode(data)))
        assertEquals("peranta-control-xyz", decodeSuccess(PairingUri.encode(data)).controlTopic)
    }

    /** ctl を持たない従来 URI も復号でき、controlTopic は null になる（後方互換）。 */
    @Test
    fun uriWithoutControlTopicDecodesToNull() {
        val legacy = PairingUri.encode(PairingData("h", "tk", "k1", key()))
        assertNull(decodeSuccess(legacy).controlTopic)
    }

    /** PairingApplier は control topic を設定へ適用する。 */
    @Test
    fun applierStoresControlTopic() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(repo.load().copy(deviceName = "tablet"))
        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key(), controlTopic = "peranta-control-abc"))
        assertEquals("peranta-control-abc", repo.load().controlTopic)
    }

    /** ctl 無しのペアリングは既存の control topic を消さずに引き継ぐ。 */
    @Test
    fun applierKeepsExistingControlTopicWhenAbsent() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsKeyStore(settings))
        repo.save(repo.load().copy(deviceName = "tablet", controlTopic = "peranta-control-existing"))
        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key()))
        assertEquals("peranta-control-existing", repo.load().controlTopic)
    }
}
