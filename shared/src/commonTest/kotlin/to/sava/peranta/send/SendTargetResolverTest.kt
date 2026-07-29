package to.sava.peranta.send

import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.RecordingControlNtfy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [resolveSendTopics] を通した配送先解決を検証する（§8/§9）。
 * 即時送信と WorkManager 再送が共有する解決経路で、ロスター解決・失効除外・取得失敗時の扱いを担保する。
 */
class SendTargetResolverTest {

    private val keyBytes = generateKey()
    private val cipher = MessageCipher(keyBytes, "k1")
    private val controlTopic = "peranta-control-xyz"
    private val selfDeviceId = "self-device"

    private fun presence(deviceId: String, endpoint: String) = PresencePayload(
        id = "p-$deviceId",
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 100,
        deviceName = deviceId,
        endpoint = endpoint,
        capabilities = listOf(CAPABILITY_DISPLAY),
    )

    private suspend fun event(deviceId: String, endpoint: String): NtfyEvent =
        NtfyEvent("e", 100, controlTopic, encodeEnvelope(cipher.seal(presence(deviceId, endpoint))))

    private fun config(
        deliveryTopics: List<String> = emptyList(),
        controlTopic: String? = this.controlTopic,
    ) = PerantaConfig(
        deviceId = selfDeviceId,
        controlTopic = controlTopic,
        deliveryTopics = deliveryTopics,
    )

    /** control topic のロスターから、自分を除いた端末のエンドポイント topic へ解決する。 */
    @Test
    fun resolvesRosterEndpointsExcludingSelf() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(selfDeviceId, "https://h/self-topic"),
                event("tablet", "https://h/tablet-topic"),
                event("desktop", "https://h/desktop-topic"),
            ),
        )
        val topics = resolveSendTopics(config(deliveryTopics = listOf("static")), cipher, ntfy)
        assertEquals(listOf("desktop-topic", "tablet-topic"), topics)
    }

    /**
     * ロスター取得自体が失敗したら空を返す。空は「あとで解決され得る一時状態」であり、
     * 再送側はこれを回復不能な失敗とせず再試行し続ける（本セッションの再送ワーカー修正が依存する契約）。
     */
    @Test
    fun fetchFailureYieldsEmptyForRetry() = runTest {
        val ntfy = RecordingControlNtfy(historyError = RuntimeException("network down"))
        val topics = resolveSendTopics(config(deliveryTopics = listOf("static")), cipher, ntfy)
        assertTrue(topics.isEmpty())
    }

    /** ロスターが取得できて空なら静的な deliveryTopics へ退避する。 */
    @Test
    fun fallsBackToStaticTopicsWhenRosterEmpty() = runTest {
        val ntfy = RecordingControlNtfy(history = emptyList())
        val topics = resolveSendTopics(config(deliveryTopics = listOf("static-a", "static-b")), cipher, ntfy)
        assertEquals(listOf("static-a", "static-b"), topics)
    }

    /** control topic 未設定なら履歴を引かず静的な deliveryTopics をそのまま使う。 */
    @Test
    fun usesStaticTopicsWhenNoControlTopic() = runTest {
        val ntfy = RecordingControlNtfy(historyError = RuntimeException("must not be called"))
        val topics = resolveSendTopics(
            config(deliveryTopics = listOf("static-a"), controlTopic = null),
            cipher,
            ntfy,
        )
        assertEquals(listOf("static-a"), topics)
    }
}
