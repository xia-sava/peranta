package to.sava.peranta.roster

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.decodeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceTest {

    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")

    /** presence は from に deviceId を、deviceName に表示名を載せ、宛先は全端末になる。 */
    @Test
    fun buildsPresenceWithDeviceIdAsFrom() {
        val presence = buildPresencePayload(
            deviceId = "dev-123",
            deviceName = "xia-desktop",
            endpoint = "https://peranta.sava.to/UPabc",
            capabilities = listOf(CAPABILITY_DISPLAY, CAPABILITY_COMMAND),
            sender = false,
            now = 5000,
            idGen = { "pre-1" },
        )
        assertEquals("dev-123", presence.from)
        assertEquals("xia-desktop", presence.deviceName)
        assertEquals(BROADCAST_TARGET, presence.to)
        assertEquals("https://peranta.sava.to/UPabc", presence.endpoint)
        assertEquals(listOf("display", "command"), presence.capabilities)
        assertEquals(5000, presence.sentAtEpochMillis)
    }

    /** capability 列は実能力のフラグから組み、持つ能力だけを display, command の順で並べる。 */
    @Test
    fun assemblesCapabilitiesFromRealFlags() {
        assertEquals(listOf("display", "command"), presenceCapabilities(canDisplay = true, canCommand = true))
        assertEquals(listOf("display"), presenceCapabilities(canDisplay = true, canCommand = false))
        assertEquals(listOf("command"), presenceCapabilities(canDisplay = false, canCommand = true))
        assertEquals(emptyList(), presenceCapabilities(canDisplay = false, canCommand = false))
    }

    /** publishPresence は control topic へ暗号文を送り、キャッシュ短縮ヘッダは付けない。 */
    @Test
    fun publishesEncryptedPresenceWithoutCacheHeader() = runTest {
        val ntfy = RecordingControlNtfy()
        val presence = buildPresencePayload(
            deviceId = "dev-123",
            deviceName = "phone",
            endpoint = "https://h/e",
            capabilities = listOf(CAPABILITY_DISPLAY),
            sender = true,
            now = 5000,
        )
        publishPresence(cipher, ntfy, "peranta-control-xyz", presence)

        val published = ntfy.published.single()
        assertEquals("peranta-control-xyz", published.topic)
        assertNull(published.cacheSeconds)
        assertTrue(published.body.isNotBlank())

        val opened = cipher.open(decodeEnvelope(published.body)) as PresencePayload
        assertEquals("dev-123", opened.from)
    }
}
