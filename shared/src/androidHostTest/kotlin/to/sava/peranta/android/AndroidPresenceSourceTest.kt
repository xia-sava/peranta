package to.sava.peranta.android

import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.buildPresencePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android の設定から presence の告知に載せる自端末の情報を組む [androidPresenceSource]（§3.5）を検証する。 */
class AndroidPresenceSourceTest {

    private val ready = PerantaConfig(
        deviceName = "phone",
        sharedKeyBase64 = "a2V5",
        keyId = "k1",
        controlTopic = "peranta-control-xyz",
        unifiedPushEndpoint = "https://peranta.example.com/UPabc",
        sendEnabled = true,
    )

    private fun source(
        config: PerantaConfig = ready,
        deviceId: () -> String = { "dev-1" },
        canCommand: Boolean = false,
        publish: suspend (String, PresencePayload) -> Unit = { _, _ -> },
    ) = androidPresenceSource(config, deviceId, canCommand, publish)

    /** 設定が揃っていれば、端末 ID・端末名・UnifiedPush のエンドポイント・送信設定を自端末の情報に載せる。 */
    @Test
    fun readyConfigBuildsSource() {
        val source = assertNotNull(source())

        assertEquals("dev-1", source.deviceId)
        assertEquals("phone", source.deviceName)
        assertEquals("https://peranta.example.com/UPabc", source.endpoint)
        assertTrue(source.sender)
    }

    /** 送信が無効（sendEnabled が偽）なら、sender を偽にする。 */
    @Test
    fun sendDisabledIsNotSender() {
        assertFalse(assertNotNull(source(config = ready.copy(sendEnabled = false))).sender)
    }

    /** 受信設定が揃っていれば表示能力を持つ。 */
    @Test
    fun readyForUnifiedPushReceiveCanDisplay() {
        assertTrue(assertNotNull(source()).canDisplay)
    }

    /** keyId が空で受信設定が揃っていなければ、自端末の情報は組むが表示能力は持たない。 */
    @Test
    fun blankKeyIdCannotDisplay() {
        assertFalse(assertNotNull(source(config = ready.copy(keyId = ""))).canDisplay)
    }

    /** コマンド実行能力は、渡された NLS の実接続の有無をそのまま載せる。 */
    @Test
    fun canCommandFollowsArgument() {
        assertTrue(assertNotNull(source(canCommand = true)).canCommand)
        assertFalse(assertNotNull(source(canCommand = false)).canCommand)
    }

    /** 告知に必要な設定のどれかが欠けていれば null を返し、端末 ID を確定させない。 */
    @Test
    fun missingSettingYieldsNullWithoutEnsuringDeviceId() {
        val missing = mapOf(
            "controlTopic" to ready.copy(controlTopic = null),
            "unifiedPushEndpoint" to ready.copy(unifiedPushEndpoint = null),
            "deviceName" to ready.copy(deviceName = null),
            "sharedKeyBase64" to ready.copy(sharedKeyBase64 = null),
            "keyId" to ready.copy(keyId = null),
        )
        missing.forEach { (name, config) ->
            var deviceIdEnsured = false

            val source = source(
                config = config,
                deviceId = {
                    deviceIdEnsured = true
                    "dev-1"
                },
            )

            assertNull(source, "missing $name")
            assertFalse(deviceIdEnsured, "device id ensured while $name is missing")
        }
    }

    /** 組んだ情報の publish は、設定の control topic と presence を、引数で渡した publish する関数へ渡す。 */
    @Test
    fun publishSendsToConfiguredControlTopic() = runTest {
        val sent = mutableListOf<Pair<String, PresencePayload>>()
        val source = assertNotNull(source(publish = { topic, presence -> sent += topic to presence }))
        val presence = buildPresencePayload(
            deviceId = "dev-1",
            deviceName = "phone",
            endpoint = "https://peranta.example.com/UPabc",
            capabilities = listOf(CAPABILITY_DISPLAY),
            sender = true,
            now = 0,
        )

        source.publish(presence)

        assertEquals(listOf("peranta-control-xyz" to presence), sent)
    }
}
