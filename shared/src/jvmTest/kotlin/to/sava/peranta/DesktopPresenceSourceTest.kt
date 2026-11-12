package to.sava.peranta

import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.buildPresencePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Desktop の設定から presence の告知に載せる自端末の情報を組む [desktopPresenceSource]（§3.5）を検証する。 */
class DesktopPresenceSourceTest {

    private val ready = PerantaConfig(
        host = "peranta.example.com",
        deviceId = "dev-1",
        deviceName = "desktop",
        controlTopic = "peranta-control-xyz",
        receiveTopic = "peranta-desktop-abc",
        sendEnabled = true,
    )

    private fun source(
        config: PerantaConfig = ready,
        publish: suspend (String, PresencePayload) -> Unit = { _, _ -> },
    ) = desktopPresenceSource(config, publish)

    /** 設定が揃っていれば、端末 ID・端末名・送信設定を自端末の情報に載せる。 */
    @Test
    fun readyConfigBuildsSource() {
        val source = assertNotNull(source())

        assertEquals("dev-1", source.deviceId)
        assertEquals("desktop", source.deviceName)
        assertTrue(source.sender)
    }

    /** 届け先を見分ける値は、control topic か keyId が変わると変わる。 */
    @Test
    fun destinationFollowsControlTopicAndKeyId() {
        val base = assertNotNull(source(config = ready.copy(keyId = "k1"))).destination

        assertEquals(base, assertNotNull(source(config = ready.copy(keyId = "k1", deviceName = "renamed"))).destination)
        assertNotEquals(base, assertNotNull(source(config = ready.copy(keyId = "k2"))).destination)
        assertNotEquals(
            base,
            assertNotNull(source(config = ready.copy(keyId = "k1", controlTopic = "peranta-control-other"))).destination,
        )
    }

    /** 送信が無効（sendEnabled が偽）なら、sender を偽にする。 */
    @Test
    fun sendDisabledIsNotSender() {
        assertFalse(assertNotNull(source(config = ready.copy(sendEnabled = false))).sender)
    }

    /** エンドポイントは、サーバのベース URL に受信 topic を連ねたもの。 */
    @Test
    fun endpointIsBaseUrlWithReceiveTopic() {
        assertEquals("https://peranta.example.com/peranta-desktop-abc", assertNotNull(source()).endpoint)
        assertEquals(
            "http://peranta.example.com:8080/peranta-desktop-abc",
            assertNotNull(source(config = ready.copy(useTls = false, port = 8080))).endpoint,
        )
    }

    /** 端末名が未設定なら、端末 ID を表示名に使う。 */
    @Test
    fun missingDeviceNameFallsBackToDeviceId() {
        assertEquals("dev-1", assertNotNull(source(config = ready.copy(deviceName = null))).deviceName)
    }

    /** Desktop は通知を表示できるが、通知捕捉（NLS）を持たないためコマンド実行能力は持たない。 */
    @Test
    fun canDisplayButCannotCommand() {
        val source = assertNotNull(source())

        assertTrue(source.canDisplay)
        assertFalse(source.canCommand)
    }

    /** 告知に必要な設定のどれかが欠けていれば null を返す。 */
    @Test
    fun missingSettingYieldsNull() {
        val missing = mapOf(
            "controlTopic" to ready.copy(controlTopic = null),
            "deviceId" to ready.copy(deviceId = null),
            "receiveTopic" to ready.copy(receiveTopic = null),
        )
        missing.forEach { (name, config) ->
            assertNull(source(config = config), "missing $name")
        }
    }

    /** 組んだ情報の publish は、設定の control topic と presence を、引数で渡した publish する関数へ渡す。 */
    @Test
    fun publishSendsToConfiguredControlTopic() = runTest {
        val sent = mutableListOf<Pair<String, PresencePayload>>()
        val source = assertNotNull(source(publish = { topic, presence -> sent += topic to presence }))
        val presence = buildPresencePayload(
            deviceId = "dev-1",
            deviceName = "desktop",
            endpoint = "https://peranta.example.com/peranta-desktop-abc",
            capabilities = listOf(CAPABILITY_DISPLAY),
            sender = true,
            now = 0,
        )

        source.publish(presence)

        assertEquals(listOf("peranta-control-xyz" to presence), sent)
    }
}
