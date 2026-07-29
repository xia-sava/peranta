package to.sava.peranta.pairing

import com.russhwolf.settings.MapSettings
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.SettingsSecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingBlobTopicTest {

    private fun key(): ByteArray = ByteArray(32) { it.toByte() }

    private fun decodeSuccess(uri: String): PairingData =
        (PairingUri.decode(uri) as PairingResult.Success).data

    /** blob topic を含む PairingData は URI へ符号化して往復する。 */
    @Test
    fun blobTopicRoundTripsThroughUri() {
        val data = PairingData("h", "tk", "k1", key(), blobTopic = "peranta-blob-xyz")
        assertEquals(data, decodeSuccess(PairingUri.encode(data)))
        assertEquals("peranta-blob-xyz", decodeSuccess(PairingUri.encode(data)).blobTopic)
    }

    /** blob を持たない従来 URI も復号でき、blobTopic は null になる（後方互換）。 */
    @Test
    fun uriWithoutBlobTopicDecodesToNull() {
        val legacy = PairingUri.encode(PairingData("h", "tk", "k1", key()))
        assertNull(decodeSuccess(legacy).blobTopic)
    }

    /** control と blob の両 topic を同時に載せても互いに壊れずに往復する。 */
    @Test
    fun controlAndBlobTopicsRoundTripTogether() {
        val data = PairingData(
            "h",
            "tk",
            "k1",
            key(),
            controlTopic = "peranta-control-abc",
            blobTopic = "peranta-blob-def",
        )
        val decoded = decodeSuccess(PairingUri.encode(data))
        assertEquals("peranta-control-abc", decoded.controlTopic)
        assertEquals("peranta-blob-def", decoded.blobTopic)
    }

    /** PairingApplier は blob topic を設定へ適用する。 */
    @Test
    fun applierStoresBlobTopic() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        repo.save(repo.load().copy(deviceName = "tablet"))
        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key(), blobTopic = "peranta-blob-abc"))
        assertEquals("peranta-blob-abc", repo.load().blobTopic)
    }

    /** blob 無しのペアリングは既存の blob topic を消さずに引き継ぐ。 */
    @Test
    fun applierKeepsExistingBlobTopicWhenAbsent() {
        val settings = MapSettings()
        val repo = ConfigRepository(settings, SettingsSecretStore(settings))
        repo.save(repo.load().copy(deviceName = "tablet", blobTopic = "peranta-blob-existing"))
        PairingApplier(repo).apply(PairingData("h", "tk", "k1", key()))
        assertEquals("peranta-blob-existing", repo.load().blobTopic)
    }
}
