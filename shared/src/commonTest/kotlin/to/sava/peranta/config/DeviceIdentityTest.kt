package to.sava.peranta.config

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceIdentityTest {

    private fun repo(settings: MapSettings = MapSettings()) =
        ConfigRepository(settings, SettingsSecretStore(settings))

    /** ensureDeviceId は未設定なら生成して永続化し、以後は同じ ID を返す。 */
    @Test
    fun ensureDeviceIdGeneratesOnceAndPersists() {
        val repo = repo()
        val first = repo.ensureDeviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, repo.ensureDeviceId())
        assertEquals(first, repo.load().deviceId)
    }

    /** 別々に生成した deviceId は互いに異なる（ランダム性）。 */
    @Test
    fun generatedDeviceIdsAreDistinct() {
        assertNotEquals(repo().ensureDeviceId(), repo().ensureDeviceId())
    }

    /** ensureControlTopic は control 形式で採番し、以後は同じ値を返す。 */
    @Test
    fun ensureControlTopicGeneratesAndPersists() {
        val repo = repo()
        val first = repo.ensureControlTopic()
        assertTrue(first.startsWith("peranta-control-"), first)
        assertEquals(first, repo.ensureControlTopic())
        assertEquals(first, repo.load().controlTopic)
    }

    /** ensureBlobTopic は blob 形式で採番し、以後は同じ値を返す。 */
    @Test
    fun ensureBlobTopicGeneratesAndPersists() {
        val repo = repo()
        val first = repo.ensureBlobTopic()
        assertTrue(first.startsWith("peranta-blob-"), first)
        assertEquals(first, repo.ensureBlobTopic())
        assertEquals(first, repo.load().blobTopic)
    }

    /** deviceId と controlTopic・blobTopic は save/load で往復し、未設定なら null に戻る。 */
    @Test
    fun deviceIdAndControlTopicRoundTrip() {
        val repo = repo()
        repo.save(
            PerantaConfig(
                deviceId = "dev-123",
                controlTopic = "peranta-control-xyz",
                blobTopic = "peranta-blob-xyz",
            ),
        )
        val loaded = repo.load()
        assertEquals("dev-123", loaded.deviceId)
        assertEquals("peranta-control-xyz", loaded.controlTopic)
        assertEquals("peranta-blob-xyz", loaded.blobTopic)

        repo.save(PerantaConfig(deviceId = null, controlTopic = null, blobTopic = null))
        assertNull(repo.load().deviceId)
        assertNull(repo.load().controlTopic)
        assertNull(repo.load().blobTopic)
    }

    /** control topic だけでも送信ロールの前提（配送先の解決手段）が揃う。 */
    @Test
    fun sendReadyWithControlTopicButNoDeliveryTopics() {
        val base = PerantaConfig(
            accessToken = "tok",
            deviceName = "phone",
            sharedKeyBase64 = "a".repeat(44),
            keyId = "k1",
        )
        assertFalse(base.isReadyForSend)
        assertTrue(base.copy(controlTopic = "peranta-control-xyz").isReadyForSend)
        assertTrue(base.copy(deliveryTopics = listOf("t")).isReadyForSend)
    }
}
