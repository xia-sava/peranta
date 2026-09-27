package to.sava.peranta.send

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.MAX_REPLY_TEXT_BYTES
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.roster.CAPABILITY_COMMAND
import to.sava.peranta.roster.CAPABILITY_COMMAND_ACK
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.RecordingControlNtfy
import to.sava.peranta.timeline.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CommandSender] の宛先分類（§3.4）を配送まで通して検証する。
 * dismiss は既読同期のため自分を除く全端末へブロードキャスト（to:"*"）、
 * invokeAction/reply/muteApp は元通知の送信元 deviceId へ一点指定することを、
 * 実際に publish された封筒を復号して確かめる。
 */
class CommandSenderTest {

    private val cipher = MessageCipher(generateKey(), "k1")
    private val controlTopic = "peranta-control-xyz"
    private val self = "desktop-self"

    private fun presence(
        deviceId: String,
        endpoint: String,
        capabilities: List<String> = listOf(CAPABILITY_DISPLAY),
    ) = PresencePayload(
        id = "p-$deviceId",
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 100,
        deviceName = "$deviceId-name",
        endpoint = endpoint,
        capabilities = capabilities,
    )

    /** 応答に対応した版の presence。 */
    private fun ackCapablePresence(deviceId: String, endpoint: String) =
        presence(deviceId, endpoint, listOf(CAPABILITY_DISPLAY, CAPABILITY_COMMAND, CAPABILITY_COMMAND_ACK))

    private suspend fun event(payload: Payload): NtfyEvent =
        NtfyEvent("e", 100, controlTopic, encodeEnvelope(cipher.seal(payload)))

    private fun config(
        controlTopic: String? = this.controlTopic,
        deliveryTopics: List<String> = emptyList(),
    ) = PerantaConfig(
        deviceId = self,
        controlTopic = controlTopic,
        deliveryTopics = deliveryTopics,
    )

    private fun sender(
        ntfy: RecordingControlNtfy,
        config: PerantaConfig = config(),
        ackTracker: CommandAckTracker? = null,
    ) = CommandSender(config, cipher, ntfy, SendPipeline(cipher, ntfy, FakeTimelineStore()), ackTracker)

    /** 知らせを記録するだけの応答待ち。待ち時間は仮想時間で進める。 */
    private fun TestScope.recordingTracker(reports: MutableList<ErrorKind>) = CommandAckTracker(
        scope = backgroundScope,
        onProblem = { kind, _ -> reports.add(kind) },
        now = { testScheduler.currentTime },
    )

    private suspend fun openCommand(body: String): CommandPayload =
        cipher.open(decodeEnvelope(body)) as CommandPayload

    /** dismiss は自分を除く全端末（ロスター由来）へ to:"*" で配送する。 */
    @Test
    fun dismissBroadcastsToAllExceptSelf() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence(self, "https://h/self-topic")),
                event(presence("phone", "https://h/phone-topic")),
                event(presence("tablet", "https://h/tablet-topic")),
            ),
        )
        val ok = sender(ntfy).dismiss("0|com.x|1|null|10")
        assertTrue(ok)
        assertEquals(listOf("phone-topic", "tablet-topic"), ntfy.published.map { it.topic })
        val command = openCommand(ntfy.published.first().body)
        assertEquals(CommandType.DISMISS, command.command)
        assertEquals(BROADCAST_TARGET, command.to)
        assertEquals(self, command.from)
        assertEquals("0|com.x|1|null|10", command.targetNotificationKey)
    }

    /** ロスター取得が失敗したら dismiss は静的フォールバックへ流さず、何も送らない。 */
    @Test
    fun dismissWithRosterFetchFailureSendsNothing() = runTest {
        val ntfy = RecordingControlNtfy(historyError = RuntimeException("network down"))
        val ok = sender(ntfy, config(deliveryTopics = listOf("static"))).dismiss("0|k")
        assertFalse(ok)
        assertTrue(ntfy.published.isEmpty())
    }

    /**
     * キャッシュ保持は配送特性に従う。状態同期の dismiss は指定せずサーバの設定へ委ね、
     * 即時操作の reply は短い保持で送る（§3.4）。
     */
    @Test
    fun publishesWithCacheRetentionOfDelivery() = runTest {
        val ntfy = RecordingControlNtfy(history = listOf(event(presence("phone", "https://h/phone-topic"))))
        val commandSender = sender(ntfy)
        commandSender.dismiss("0|k")
        commandSender.reply(targetDeviceId = "phone", targetNotificationKey = "0|k", actionIndex = 0, text = "OK")
        assertNull(ntfy.published.first().cacheSeconds)
        assertEquals(HIGH_PRIORITY_CACHE_SECONDS, ntfy.published.last().cacheSeconds)
    }

    /** invokeAction は元通知の送信元 deviceId のエンドポイントへ一点指定する。 */
    @Test
    fun invokeActionTargetsSourceDevice() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence("phone", "https://h/phone-topic")),
                event(presence("tablet", "https://h/tablet-topic")),
            ),
        )
        val ok = sender(ntfy).invokeAction(targetDeviceId = "phone", targetNotificationKey = "0|k", actionIndex = 2)
        assertTrue(ok)
        assertEquals(listOf("phone-topic"), ntfy.published.map { it.topic })
        val command = openCommand(ntfy.published.single().body)
        assertEquals(CommandType.INVOKE_ACTION, command.command)
        assertEquals("phone", command.to)
        assertEquals(2, command.actionIndex)
        assertEquals("0|k", command.targetNotificationKey)
    }

    /** reply は送信元 deviceId へ返信本文つきで一点指定する。 */
    @Test
    fun replyTargetsSourceDevice() = runTest {
        val ntfy = RecordingControlNtfy(history = listOf(event(presence("phone", "https://h/phone-topic"))))
        val ok = sender(ntfy).reply(targetDeviceId = "phone", targetNotificationKey = "0|k", actionIndex = 1, text = "OK")
        assertTrue(ok)
        val command = openCommand(ntfy.published.single().body)
        assertEquals(CommandType.REPLY, command.command)
        assertEquals("phone", command.to)
        assertEquals(1, command.actionIndex)
        assertEquals("OK", command.replyText)
    }

    /** muteApp は送信元 deviceId へパッケージ名つきで一点指定する。 */
    @Test
    fun muteAppTargetsSourceDevice() = runTest {
        val ntfy = RecordingControlNtfy(history = listOf(event(presence("phone", "https://h/phone-topic"))))
        val ok = sender(ntfy).muteApp(targetDeviceId = "phone", packageName = "com.spam")
        assertTrue(ok)
        val command = openCommand(ntfy.published.single().body)
        assertEquals(CommandType.MUTE_APP, command.command)
        assertEquals("phone", command.to)
        assertEquals("com.spam", command.packageName)
    }

    /** reply は本文が上限バイト数を超えると [MAX_REPLY_TEXT_BYTES] へ切り詰めて送る（§4.2）。 */
    @Test
    fun replyTruncatesOverLongText() = runTest {
        val ntfy = RecordingControlNtfy(history = listOf(event(presence("phone", "https://h/phone-topic"))))
        val overLong = "あ".repeat(MAX_REPLY_TEXT_BYTES)
        val ok = sender(ntfy).reply(targetDeviceId = "phone", targetNotificationKey = "0|k", actionIndex = 0, text = overLong)
        assertTrue(ok)
        val command = openCommand(ntfy.published.single().body)
        val replyText = requireNotNull(command.replyText)
        assertTrue(replyText.encodeToByteArray().size <= MAX_REPLY_TEXT_BYTES)
        assertTrue(replyText.length < overLong.length)
    }

    /** unmuteApp は送信元 deviceId へパッケージ名つきで一点指定する。 */
    @Test
    fun unmuteAppTargetsSourceDevice() = runTest {
        val ntfy = RecordingControlNtfy(history = listOf(event(presence("phone", "https://h/phone-topic"))))
        val ok = sender(ntfy).unmuteApp(targetDeviceId = "phone", packageName = "com.spam")
        assertTrue(ok)
        val command = openCommand(ntfy.published.single().body)
        assertEquals(CommandType.UNMUTE_APP, command.command)
        assertEquals("phone", command.to)
        assertEquals("com.spam", command.packageName)
    }

    /** 一点指定コマンドで対象 deviceId がロスターに居なければ何も送らない。 */
    @Test
    fun singleTargetWithUnknownDeviceSendsNothing() = runTest {
        val ntfy = RecordingControlNtfy(history = listOf(event(presence("tablet", "https://h/tablet-topic"))))
        val ok = sender(ntfy).invokeAction("phone", "0|k", 0)
        assertFalse(ok)
        assertTrue(ntfy.published.isEmpty())
    }

    /** 応答に対応した宛先への invokeAction は、自端末の受信 topic を応答の送り先にして宛先へ応答を求める（§3.4）。 */
    @Test
    fun invokeActionRequestsAckFromCapableTarget() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence(self, "https://h/self-topic")),
                event(ackCapablePresence("phone", "https://h/phone-topic")),
            ),
        )
        sender(ntfy, ackTracker = recordingTracker(mutableListOf())).invokeAction("phone", "0|k", 0)
        val command = openCommand(ntfy.published.single().body)
        assertEquals("self-topic", command.ackTopic)
        assertEquals("phone", command.ackBy)
    }

    /** 応答に対応していない版の宛先には応答を求めず、応答が来なくても知らせない。 */
    @Test
    fun invokeActionDoesNotRequestAckFromLegacyTarget() = runTest {
        val reports = mutableListOf<ErrorKind>()
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence(self, "https://h/self-topic")),
                event(presence("phone", "https://h/phone-topic")),
            ),
        )
        sender(ntfy, ackTracker = recordingTracker(reports)).invokeAction("phone", "0|k", 0)
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS * 2)
        runCurrent()
        val command = openCommand(ntfy.published.single().body)
        assertNull(command.ackTopic)
        assertNull(command.ackBy)
        assertTrue(reports.isEmpty())
    }

    /** 自端末の受信 topic がロスターに無ければ応答の送り先が無いため、応答を求めない。 */
    @Test
    fun noAckRequestWithoutOwnEndpoint() = runTest {
        val ntfy = RecordingControlNtfy(history = listOf(event(ackCapablePresence("phone", "https://h/phone-topic"))))
        sender(ntfy, ackTracker = recordingTracker(mutableListOf())).invokeAction("phone", "0|k", 0)
        assertNull(openCommand(ntfy.published.single().body).ackTopic)
    }

    /** dismiss は全端末へ送りつつ、元通知を転送した端末だけを応答者に指名する。 */
    @Test
    fun dismissNominatesSourceDeviceForAck() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence(self, "https://h/self-topic")),
                event(ackCapablePresence("phone", "https://h/phone-topic")),
                event(ackCapablePresence("tablet", "https://h/tablet-topic")),
            ),
        )
        sender(ntfy, ackTracker = recordingTracker(mutableListOf())).dismiss("0|k", sourceDeviceId = "phone")
        assertEquals(listOf("phone-topic", "tablet-topic"), ntfy.published.map { it.topic })
        val command = openCommand(ntfy.published.first().body)
        assertEquals(BROADCAST_TARGET, command.to)
        assertEquals("phone", command.ackBy)
        assertEquals("self-topic", command.ackTopic)
    }

    /** 自端末が転送元の通知を dismiss するときは、自分に応答を求めない。 */
    @Test
    fun dismissOfOwnNotificationDoesNotRequestAck() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(ackCapablePresence(self, "https://h/self-topic")),
                event(ackCapablePresence("phone", "https://h/phone-topic")),
            ),
        )
        sender(ntfy, ackTracker = recordingTracker(mutableListOf())).dismiss("0|k", sourceDeviceId = self)
        assertNull(openCommand(ntfy.published.single().body).ackBy)
    }

    /** 応答を求めたコマンドに応答が来なければ、応答が無いことを知らせる。 */
    @Test
    fun unansweredCommandIsReported() = runTest {
        val reports = mutableListOf<ErrorKind>()
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence(self, "https://h/self-topic")),
                event(ackCapablePresence("phone", "https://h/phone-topic")),
            ),
        )
        sender(ntfy, ackTracker = recordingTracker(reports)).invokeAction("phone", "0|k", 0)
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(ErrorKind.COMMAND_UNACKNOWLEDGED), reports)
    }

    /** 状態同期の設定変更（mute 等）は元通知への操作ではないため、応答を求めない。 */
    @Test
    fun muteAppDoesNotRequestAck() = runTest {
        val ntfy = RecordingControlNtfy(
            history = listOf(
                event(presence(self, "https://h/self-topic")),
                event(ackCapablePresence("phone", "https://h/phone-topic")),
            ),
        )
        sender(ntfy, ackTracker = recordingTracker(mutableListOf())).muteApp("phone", "com.spam")
        assertNull(openCommand(ntfy.published.single().body).ackBy)
    }

    /** control topic が無い端末は一点指定先を引けないため、履歴を引かずに何も送らない。 */
    @Test
    fun singleTargetWithoutControlTopicSendsNothing() = runTest {
        val ntfy = RecordingControlNtfy(historyError = RuntimeException("must not be fetched"))
        val ok = sender(ntfy, config(controlTopic = null)).muteApp("phone", "com.spam")
        assertFalse(ok)
        assertTrue(ntfy.published.isEmpty())
    }
}
