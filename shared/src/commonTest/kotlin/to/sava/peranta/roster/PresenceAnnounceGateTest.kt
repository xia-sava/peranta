package to.sava.peranta.roster

import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.PresencePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun presence(
    id: String = "id-1",
    from: String = "dev-1",
    sentAtEpochMillis: Long = 0L,
    deviceName: String = "phone",
    endpoint: String = "https://h/e",
    capabilities: List<String> = listOf(CAPABILITY_DISPLAY),
    sender: Boolean = false,
): PresencePayload = PresencePayload(
    id = id,
    from = from,
    to = BROADCAST_TARGET,
    sentAtEpochMillis = sentAtEpochMillis,
    deviceName = deviceName,
    endpoint = endpoint,
    capabilities = capabilities,
    sender = sender,
)

class PresenceAnnounceGateTest {

    /** 初回はいかなる fingerprint も通す（前回記録が無いため）。 */
    @Test
    fun firstCallAlwaysPasses() {
        val gate = PresenceAnnounceGate(minIntervalMillis = 1000)
        assertTrue(gate.shouldAnnounce("fp-a", now = 0L))
    }

    /** 同一 fingerprint は最小間隔内なら抑止する。 */
    @Test
    fun sameFingerprintWithinIntervalIsSuppressed() {
        val gate = PresenceAnnounceGate(minIntervalMillis = 1000)
        gate.recordAnnounced("fp-a", now = 0L)
        assertFalse(gate.shouldAnnounce("fp-a", now = 999))
    }

    /** fingerprint が変われば間隔内でも通す。 */
    @Test
    fun changedFingerprintPassesEvenWithinInterval() {
        val gate = PresenceAnnounceGate(minIntervalMillis = 1000)
        gate.recordAnnounced("fp-a", now = 0L)
        assertTrue(gate.shouldAnnounce("fp-b", now = 1))
    }

    /** 同一 fingerprint でも最小間隔経過後は通す。 */
    @Test
    fun sameFingerprintAfterIntervalPasses() {
        val gate = PresenceAnnounceGate(minIntervalMillis = 1000)
        gate.recordAnnounced("fp-a", now = 0L)
        assertTrue(gate.shouldAnnounce("fp-a", now = 1000))
    }

    /** recordAnnounced を呼ばなければ（publish 失敗相当）次回も通す。 */
    @Test
    fun withoutRecordingNextCallStillPasses() {
        val gate = PresenceAnnounceGate(minIntervalMillis = 1000)
        assertTrue(gate.shouldAnnounce("fp-a", now = 0L))
        // record しない（publish 失敗を模す）。
        assertTrue(gate.shouldAnnounce("fp-a", now = 1))
    }

    /** fingerprint は id・sentAtEpochMillis の差を無視する。 */
    @Test
    fun fingerprintIgnoresIdAndSentAt() {
        val a = presence(id = "id-1", sentAtEpochMillis = 0L)
        val b = presence(id = "id-2", sentAtEpochMillis = 5000L)
        assertEquals(presenceFingerprint(a), presenceFingerprint(b))
    }

    /** fingerprint は capabilities の差を検出する。 */
    @Test
    fun fingerprintDetectsCapabilitiesChange() {
        val a = presence(capabilities = listOf(CAPABILITY_DISPLAY))
        val b = presence(capabilities = listOf(CAPABILITY_DISPLAY, CAPABILITY_COMMAND))
        assertNotEquals(presenceFingerprint(a), presenceFingerprint(b))
    }

    /** fingerprint は sender の差を検出する。 */
    @Test
    fun fingerprintDetectsSenderChange() {
        val a = presence(sender = false)
        val b = presence(sender = true)
        assertNotEquals(presenceFingerprint(a), presenceFingerprint(b))
    }

    /** fingerprint は deviceName の差を検出する。 */
    @Test
    fun fingerprintDetectsDeviceNameChange() {
        val a = presence(deviceName = "phone-a")
        val b = presence(deviceName = "phone-b")
        assertNotEquals(presenceFingerprint(a), presenceFingerprint(b))
    }

    /** fingerprint は endpoint の差を検出する。 */
    @Test
    fun fingerprintDetectsEndpointChange() {
        val a = presence(endpoint = "https://h/e1")
        val b = presence(endpoint = "https://h/e2")
        assertNotEquals(presenceFingerprint(a), presenceFingerprint(b))
    }
}
