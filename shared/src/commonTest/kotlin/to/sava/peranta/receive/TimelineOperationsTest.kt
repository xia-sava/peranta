package to.sava.peranta.receive

import co.touchlab.kermit.Logger
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.sava.peranta.config.ConfigRepository
import to.sava.peranta.config.PerantaConfig
import to.sava.peranta.config.SettingsSecretStore
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.filter.RuleAction
import to.sava.peranta.model.AppRuleSettings
import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.PresencePayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.model.decodeEnvelope
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.NtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.platform.RecordingLogWriter
import to.sava.peranta.platform.recordingLogger
import to.sava.peranta.roster.CAPABILITY_COMMAND
import to.sava.peranta.roster.CAPABILITY_COMMAND_ACK
import to.sava.peranta.roster.CAPABILITY_DISPLAY
import to.sava.peranta.roster.RecordingControlNtfy
import to.sava.peranta.send.CommandAckTracker
import to.sava.peranta.send.CommandSender
import to.sava.peranta.send.FakeTimelineStore
import to.sava.peranta.send.SendPipeline
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TimelineOperations] が組む受信アイテムへの操作（§3.4 / §10.1 / §10.4-1）を、コマンドの配送まで通して検証する。
 * 表示の取り下げ・publish・マーク・非表示を起きた順に同じ記録へ積み、どのコマンドをどこへ送ったかは
 * publish された封筒を復号して確かめる。ロスターは自端末と、応答に対応した phone・tablet の 3 台。
 * dismiss は自端末を除く phone・tablet の両方へ届くため、コマンドの数は phone 宛ての publish で数える。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineOperationsTest {

    private val cipher = MessageCipher(generateKey(), "k1")
    private val controlTopic = "peranta-control-xyz"
    private val self = "desktop-self"

    private fun presence(deviceId: String, endpoint: String, capabilities: List<String>) = PresencePayload(
        id = "p-$deviceId",
        from = deviceId,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 100,
        deviceName = "$deviceId-name",
        endpoint = endpoint,
        capabilities = capabilities,
    )

    private suspend fun event(payload: Payload): NtfyEvent =
        NtfyEvent("e", 100, controlTopic, encodeEnvelope(cipher.seal(payload)))

    private suspend fun rosterHistory(): List<NtfyEvent> {
        val ackCapable = listOf(CAPABILITY_DISPLAY, CAPABILITY_COMMAND, CAPABILITY_COMMAND_ACK)
        return listOf(
            event(presence(self, "https://h/self-topic", listOf(CAPABILITY_DISPLAY))),
            event(presence("phone", "https://h/phone-topic", ackCapable)),
            event(presence("tablet", "https://h/tablet-topic", ackCapable)),
        )
    }

    /**
     * publish を起きた順に [events] へ記録し、本文の記録は [delegate] に任せる [NtfyClient]。
     * [publishFailure] を与えると、publish を記録したうえでそれを投げ、本文は記録しない。
     */
    private class OrderRecordingNtfy(
        private val delegate: RecordingControlNtfy,
        private val events: MutableList<String>,
        private val publishFailure: Throwable?,
    ) : NtfyClient by delegate {
        override suspend fun publish(topic: String, body: String, cache: Boolean) {
            events.add("publish:$topic")
            publishFailure?.let { throw it }
            delegate.publish(topic, body, cache)
        }
    }

    private class Harness(
        val events: List<String>,
        val ntfy: RecordingControlNtfy,
        val repository: ConfigRepository,
        val operations: TimelineOperations,
    ) {
        val actions = operations.timelineActions(operations.appFilterController(repository))
    }

    /**
     * 操作をこのテストのスコープで走らせる [TimelineOperations] を組む。操作の中の例外が捕まえられずに
     * 漏れると、テストのスコープごと失敗する。
     * [senderAvailable] が偽なら送信の設定が揃っていない端末として [CommandSender] を null にし、
     * [senderFailure] を与えると [CommandSender] を組む段で投げる。[publishFailure] を与えると publish が
     * それで失敗する（[CommandSender] が握って false を返す）。[failure] は記録しようとした出来事に対して
     * 投げる例外を返す。
     */
    private suspend fun TestScope.harness(
        timeline: List<TimelineItem> = emptyList(),
        senderAvailable: Boolean = true,
        senderFailure: Throwable? = null,
        publishFailure: Throwable? = null,
        failure: (event: String) -> Throwable? = { null },
        log: Logger = Logger.withTag("TimelineOps"),
    ): Harness {
        val events = mutableListOf<String>()
        val ntfy = RecordingControlNtfy(history = rosterHistory())
        val orderedNtfy = OrderRecordingNtfy(ntfy, events, publishFailure)
        val ackTracker = CommandAckTracker(
            scope = backgroundScope,
            onProblem = { _, _ -> },
            now = { testScheduler.currentTime },
        )
        val sender = CommandSender(
            PerantaConfig(deviceId = self, controlTopic = controlTopic),
            cipher,
            orderedNtfy,
            SendPipeline(cipher, orderedNtfy, FakeTimelineStore()),
            ackTracker,
        )
        val record: (String) -> Unit = { event ->
            failure(event)?.let { throw it }
            events.add(event)
        }
        val settings = MapSettings()
        val operations = TimelineOperations(
            scope = this,
            items = { timeline },
            commandSender = {
                senderFailure?.let { throw it }
                sender.takeIf { senderAvailable }
            },
            markSourceDismissed = { record("mark:$it") },
            hideFromTimeline = { record("hide:$it") },
            withdrawDisplay = { record("withdraw:$it") },
            log = log,
        )
        return Harness(events, ntfy, ConfigRepository(settings, SettingsSecretStore(settings), forceTls = false), operations)
    }

    private fun notificationPayload(itemId: String, key: String, from: String) = NotificationPayload(
        id = "p-$itemId",
        from = from,
        to = BROADCAST_TARGET,
        sentAtEpochMillis = 1,
        packageName = "com.example",
        appName = "Example",
        title = "t",
        text = "b",
        notificationKey = key,
        postedAtEpochMillis = 1,
    )

    /** アイテム id と payload.id を分けた受信通知。取り下げは payload.id、非表示はアイテム id で行う。 */
    private fun notification(itemId: String, key: String, from: String = "phone") = ReceivedNotification(
        id = itemId,
        timestampEpochMillis = 1,
        payload = notificationPayload(itemId, key, from),
    )

    /** 元通知と対応づいていない SMS アイテム（notificationKey を持たない）。 */
    private fun smsWithoutKey(itemId: String) = ReceivedNotification(
        id = itemId,
        timestampEpochMillis = 1,
        payload = SmsPayload(
            id = "p-$itemId",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = 1,
            senderNumber = "123",
            text = "code",
            postedAtEpochMillis = 1,
        ),
    )

    private suspend fun RecordingControlNtfy.commandsTo(topic: String): List<CommandPayload> =
        published.filter { it.topic == topic }.map { cipher.open(decodeEnvelope(it.body)) as CommandPayload }

    /** ✓ で消すと、この端末の表示を取り下げてから dismiss を送り、それから自端末のタイムラインにマークを反映する。 */
    @Test
    fun dismissWithdrawsDisplayThenSendsThenMarks() = runTest {
        val harness = harness()

        harness.actions.dismiss(notification("n1", key = "0|k1"))
        runCurrent()

        assertEquals(
            listOf("withdraw:p-n1", "publish:phone-topic", "publish:tablet-topic", "mark:0|k1"),
            harness.events,
        )
    }

    /** ✓ で送る dismiss は全端末宛てで、押したアイテムの送信元に応答を求める（§3.4）。 */
    @Test
    fun dismissBroadcastsAndRequestsAckFromItemSource() = runTest {
        val harness = harness()

        harness.actions.dismiss(notification("n1", key = "0|k1", from = "tablet"))
        runCurrent()

        val command = harness.ntfy.commandsTo("phone-topic").single()
        assertEquals(CommandType.DISMISS, command.command)
        assertEquals(BROADCAST_TARGET, command.to)
        assertEquals("0|k1", command.targetNotificationKey)
        assertEquals("tablet", command.ackBy)
    }

    /** 元通知に紐づかないアイテムを ✓ で消すと、表示の取り下げだけをしてコマンドもマークも行わない。 */
    @Test
    fun dismissWithoutNotificationKeyOnlyWithdrawsDisplay() = runTest {
        val harness = harness()

        harness.actions.dismiss(smsWithoutKey("s1"))
        runCurrent()

        assertEquals(listOf("withdraw:p-s1"), harness.events)
    }

    /** まとめて消すと、全アイテムの表示を取り下げてから、元通知ごとに dismiss を送ってマークを反映する。 */
    @Test
    fun dismissAllWithdrawsEveryDisplayBeforeSending() = runTest {
        val harness = harness()

        harness.actions.dismissAll(listOf(notification("n1", key = "0|k1"), notification("n2", key = "0|k2")))
        runCurrent()

        assertEquals(
            listOf(
                "withdraw:p-n1",
                "withdraw:p-n2",
                "publish:phone-topic",
                "publish:tablet-topic",
                "mark:0|k1",
                "publish:phone-topic",
                "publish:tablet-topic",
                "mark:0|k2",
            ),
            harness.events,
        )
    }

    /** 同じ元通知を指すアイテムが複数あっても、表示はアイテムごとに取り下げ、dismiss とマークは 1 回にまとめる。 */
    @Test
    fun dismissAllSendsOneCommandPerNotificationKey() = runTest {
        val harness = harness()

        harness.actions.dismissAll(listOf(notification("n1", key = "0|k1"), notification("n2", key = "0|k1")))
        runCurrent()

        assertEquals(1, harness.ntfy.commandsTo("phone-topic").size)
        assertEquals(listOf("withdraw:p-n1", "withdraw:p-n2"), harness.events.filter { it.startsWith("withdraw:") })
        assertEquals(listOf("mark:0|k1"), harness.events.filter { it.startsWith("mark:") })
    }

    /** まとめて消すとき、応答は転送元の端末ごとに最初の 1 件だけに求める（§3.4）。 */
    @Test
    fun dismissAllRequestsAckOncePerSourceDevice() = runTest {
        val harness = harness()

        harness.actions.dismissAll(
            listOf(
                notification("n1", key = "0|k1", from = "phone"),
                notification("n2", key = "0|k2", from = "phone"),
                notification("n3", key = "0|k3", from = "tablet"),
            ),
        )
        runCurrent()

        val commands = harness.ntfy.commandsTo("phone-topic")
        assertEquals(listOf("0|k1", "0|k2", "0|k3"), commands.map { it.targetNotificationKey })
        assertEquals(listOf("phone", null, "tablet"), commands.map { it.ackBy })
    }

    /** まとめて消すとき、同じ元通知のアイテムで送信元が違っても、応答はその元通知の最初のアイテムの送信元に求める。 */
    @Test
    fun dismissAllRequestsAckFromFirstItemOfSameKey() = runTest {
        val harness = harness()

        harness.actions.dismissAll(
            listOf(
                notification("n1", key = "0|k1", from = "phone"),
                notification("n2", key = "0|k1", from = "tablet"),
                notification("n3", key = "0|k2", from = "tablet"),
            ),
        )
        runCurrent()

        val commands = harness.ntfy.commandsTo("phone-topic")
        assertEquals(listOf("0|k1", "0|k2"), commands.map { it.targetNotificationKey })
        assertEquals(listOf("phone", "tablet"), commands.map { it.ackBy })
    }

    /** まとめて消すとき、元通知に紐づかないアイテムは表示を取り下げるだけで、dismiss もマークも行わない。 */
    @Test
    fun dismissAllOnlyWithdrawsItemsWithoutNotificationKey() = runTest {
        val harness = harness()

        harness.actions.dismissAll(listOf(notification("n1", key = "0|k1"), smsWithoutKey("s1")))
        runCurrent()

        assertEquals(
            listOf("withdraw:p-n1", "withdraw:p-s1", "publish:phone-topic", "publish:tablet-topic", "mark:0|k1"),
            harness.events,
        )
    }

    /** ✓ で消すとき dismiss の publish が失敗しても、自端末のタイムラインにはマークを反映する。 */
    @Test
    fun dismissMarksEvenWhenPublishFails() = runTest {
        val harness = harness(publishFailure = IllegalStateException("network down"))

        harness.actions.dismiss(notification("n1", key = "0|k1"))
        runCurrent()

        assertEquals(listOf("withdraw:p-n1", "publish:phone-topic", "mark:0|k1"), harness.events)
    }

    /** まとめて消すとき dismiss の publish が失敗しても、マークを反映して残りの元通知へ進む。 */
    @Test
    fun dismissAllContinuesWhenPublishFails() = runTest {
        val harness = harness(publishFailure = IllegalStateException("network down"))

        harness.actions.dismissAll(listOf(notification("n1", key = "0|k1"), notification("n2", key = "0|k2")))
        runCurrent()

        assertEquals(
            listOf(
                "withdraw:p-n1",
                "withdraw:p-n2",
                "publish:phone-topic",
                "mark:0|k1",
                "publish:phone-topic",
                "mark:0|k2",
            ),
            harness.events,
        )
    }

    /** 送信の設定が揃っていない端末で ✓ で消すと、表示の取り下げとマークだけを行い、何も送らない。 */
    @Test
    fun dismissWithoutCommandSenderWithdrawsAndMarks() = runTest {
        val harness = harness(senderAvailable = false)

        harness.actions.dismiss(notification("n1", key = "0|k1"))
        runCurrent()

        assertEquals(listOf("withdraw:p-n1", "mark:0|k1"), harness.events)
    }

    /** 送信の設定が揃っていない端末でまとめて消すと、表示の取り下げとマークだけを行い、何も送らない。 */
    @Test
    fun dismissAllWithoutCommandSenderWithdrawsAndMarks() = runTest {
        val harness = harness(senderAvailable = false)

        harness.actions.dismissAll(listOf(notification("n1", key = "0|k1"), notification("n2", key = "0|k2")))
        runCurrent()

        assertEquals(listOf("withdraw:p-n1", "withdraw:p-n2", "mark:0|k1", "mark:0|k2"), harness.events)
    }

    /** 送信の設定が揃っていない端末では、アクション発火は何もしない。 */
    @Test
    fun invokeActionWithoutCommandSenderDoesNothing() = runTest {
        val harness = harness(senderAvailable = false)

        harness.actions.invokeAction(notificationPayload("n1", key = "0|k1", from = "phone"), 0)
        runCurrent()

        assertTrue(harness.events.isEmpty())
    }

    /** 送信の設定が揃っていない端末では、返信は何もしない。 */
    @Test
    fun replyWithoutCommandSenderDoesNothing() = runTest {
        val harness = harness(senderAvailable = false)

        harness.actions.reply(notificationPayload("n1", key = "0|k1", from = "phone"), 0, "OK")
        runCurrent()

        assertTrue(harness.events.isEmpty())
    }

    /** 送信の設定が揃っていない端末でも、自端末のタイムラインにはマークを反映する。 */
    @Test
    fun dismissSourceWithoutCommandSenderStillMarks() = runTest {
        val harness = harness(senderAvailable = false)

        harness.operations.dismissSource("0|k1", sourceDeviceId = "phone")

        assertEquals(listOf("mark:0|k1"), harness.events)
        assertTrue(harness.ntfy.published.isEmpty())
    }

    /** 応答を求める相手を省くと、同じ元通知を最後に転送した端末をタイムラインから引いて求める。 */
    @Test
    fun dismissSourceLooksUpLatestSourceFromTimeline() = runTest {
        val harness = harness(
            timeline = listOf(notification("n1", key = "0|k1", from = "phone"), notification("n2", key = "0|k1", from = "tablet")),
        )

        harness.operations.dismissSource("0|k1")

        assertEquals("tablet", harness.ntfy.commandsTo("phone-topic").single().ackBy)
    }

    /** 応答を求める相手に null を渡すと、タイムラインに転送元があっても応答を求めない。 */
    @Test
    fun dismissSourceWithNullSourceRequestsNoAck() = runTest {
        val harness = harness(timeline = listOf(notification("n1", key = "0|k1", from = "phone")))

        harness.operations.dismissSource("0|k1", sourceDeviceId = null)

        assertNull(harness.ntfy.commandsTo("phone-topic").single().ackBy)
    }

    /** タイムラインに同じ元通知のアイテムが無ければ、転送元は引けない。 */
    @Test
    fun sourceDeviceOfUnknownKeyIsNull() = runTest {
        val harness = harness(timeline = listOf(notification("n1", key = "0|k1", from = "phone")))

        assertNull(harness.operations.sourceDeviceOf("0|other"))
    }

    /** マークの反映に失敗したら、dismissSource は失敗を呼び出し元へ伝える。 */
    @Test
    fun dismissSourcePropagatesFailure() = runTest {
        val harness = harness(failure = { event -> IllegalStateException("broken").takeIf { event.startsWith("mark:") } })

        assertFailsWith<IllegalStateException> { harness.operations.dismissSource("0|k1", sourceDeviceId = "phone") }
    }

    /** アクション発火は、アイテムの送信元へ一点指定して応答を求める（§3.4）。 */
    @Test
    fun invokeActionTargetsSourceDevice() = runTest {
        val harness = harness()

        harness.actions.invokeAction(notificationPayload("n1", key = "0|k1", from = "phone"), 2)
        runCurrent()

        assertEquals(listOf("publish:phone-topic"), harness.events)
        val command = harness.ntfy.commandsTo("phone-topic").single()
        assertEquals(CommandType.INVOKE_ACTION, command.command)
        assertEquals("phone", command.to)
        assertEquals("0|k1", command.targetNotificationKey)
        assertEquals(2, command.actionIndex)
        assertEquals("phone", command.ackBy)
    }

    /** 返信は、本文を載せてアイテムの送信元へ一点指定する（§3.4）。 */
    @Test
    fun replyTargetsSourceDevice() = runTest {
        val harness = harness()

        harness.actions.reply(notificationPayload("n1", key = "0|k1", from = "tablet"), 1, "OK")
        runCurrent()

        assertEquals(listOf("publish:tablet-topic"), harness.events)
        val command = harness.ntfy.commandsTo("tablet-topic").single()
        assertEquals(CommandType.REPLY, command.command)
        assertEquals("tablet", command.to)
        assertEquals(1, command.actionIndex)
        assertEquals("OK", command.replyText)
    }

    /** タイムラインからの mute は、自端末のローカルミラーへ denylist の除外として反映する（§10.4-1）。 */
    @Test
    fun muteAppUpdatesLocalMirror() = runTest {
        val harness = harness()

        harness.actions.muteApp(notificationPayload("n1", key = "0|k1", from = "phone"))
        runCurrent()

        val rules = harness.repository.load().filterRules
        assertEquals(listOf("com.example"), rules.map { it.packageName })
        assertEquals(RuleAction.EXCLUDE, rules.single().action)
    }

    /** タイムラインからの mute は、アイテムの送信元へ mute コマンドを送る（§3.4）。 */
    @Test
    fun muteAppSendsMuteCommandToSource() = runTest {
        val harness = harness()

        harness.actions.muteApp(notificationPayload("n1", key = "0|k1", from = "phone"))
        runCurrent()

        val command = harness.ntfy.commandsTo("phone-topic").single()
        assertEquals(CommandType.MUTE_APP, command.command)
        assertEquals("phone", command.to)
        assertEquals("com.example", command.packageName)
    }

    /** アプリフィルタ画面でチェックを外すと、送信元へ unmute コマンドを送る（§10.4-1）。 */
    @Test
    fun filterUncheckSendsUnmuteCommand() = runTest {
        val harness = harness()

        harness.operations.appFilterController(harness.repository).setMirroredMute("com.example", "tablet", mute = false)
        runCurrent()

        val command = harness.ntfy.commandsTo("tablet-topic").single()
        assertEquals(CommandType.UNMUTE_APP, command.command)
        assertEquals("tablet", command.to)
        assertEquals("com.example", command.packageName)
    }

    /** アプリフィルタ画面で扱いを変えると、同じ内容を送信元へ送る（§10.4-1）。 */
    @Test
    fun filterAppRuleSendsSetAppRuleCommand() = runTest {
        val harness = harness()
        val settings = AppRuleSettings(forward = true, redact = true)

        harness.operations.appFilterController(harness.repository).setMirroredAppRule("com.example", "phone", settings)
        runCurrent()

        val command = harness.ntfy.commandsTo("phone-topic").single()
        assertEquals(CommandType.SET_APP_RULE, command.command)
        assertEquals("phone", command.to)
        assertEquals(settings, command.appRule)
    }

    /** タイムラインから消すと、アイテム id で自端末のタイムラインから外すだけで、コマンドは送らない（§10.1）。 */
    @Test
    fun hideFromTimelineHidesWithoutSending() = runTest {
        val harness = harness()

        harness.actions.hideFromTimeline(notification("n1", key = "0|k1"))
        runCurrent()

        assertEquals(listOf("hide:n1"), harness.events)
    }

    /** [CommandSender] を組めずに失敗しても、例外は漏れず、例外のメッセージ（接続先 URL）はログに出ない（§11）。 */
    @Test
    fun commandFailureIsLoggedWithoutEscaping() = runTest {
        val leakedTopic = "phone-topic-never-logged"
        val writer = RecordingLogWriter()
        val harness = harness(
            senderFailure = IllegalStateException("Request timeout has expired [url=https://h/$leakedTopic]"),
            log = recordingLogger(writer, "TimelineOps"),
        )

        harness.actions.invokeAction(notificationPayload("n1", key = "0|k1", from = "phone"), 0)
        runCurrent()

        assertTrue(writer.recorded.any { it.contains("failed to send command") }, "failure was not logged: ${writer.recorded}")
        assertTrue(writer.recorded.none { it.contains(leakedTopic) }, "topic appeared in log: ${writer.recorded}")
    }

    /** mute コマンドを送る [CommandSender] を組めずに失敗しても、例外は漏れずログに残る。ローカルミラーへの反映は済んでいる。 */
    @Test
    fun muteCommandFailureIsLoggedWithoutEscaping() = runTest {
        val writer = RecordingLogWriter()
        val harness = harness(senderFailure = IllegalStateException("broken"), log = recordingLogger(writer, "TimelineOps"))

        harness.actions.muteApp(notificationPayload("n1", key = "0|k1", from = "phone"))
        runCurrent()

        assertTrue(writer.recorded.any { it.contains("failed to send mute command for com.example") }, "${writer.recorded}")
        assertEquals(listOf("com.example"), harness.repository.load().filterRules.map { it.packageName })
    }

    /** ✓ で消すとき表示の取り下げに失敗したら、例外は漏れず、dismiss もマークも行わない。 */
    @Test
    fun dismissStopsWhenWithdrawFails() = runTest {
        val harness = harness(failure = { event -> IllegalStateException("broken").takeIf { event.startsWith("withdraw:") } })

        harness.actions.dismiss(notification("n1", key = "0|k1"))
        runCurrent()

        assertTrue(harness.events.isEmpty())
        assertTrue(harness.ntfy.published.isEmpty())
    }

    /** まとめて消す途中で失敗したら、例外は漏れず、残りの元通知へは dismiss もマークも行わない。 */
    @Test
    fun dismissAllStopsRemainingAfterFailure() = runTest {
        val harness = harness(failure = { event -> IllegalStateException("broken").takeIf { event == "mark:0|k1" } })

        harness.actions.dismissAll(listOf(notification("n1", key = "0|k1"), notification("n2", key = "0|k2")))
        runCurrent()

        assertEquals(
            listOf("withdraw:p-n1", "withdraw:p-n2", "publish:phone-topic", "publish:tablet-topic"),
            harness.events,
        )
    }

    /** タイムラインからの非表示に失敗しても、例外は漏れずログに残る。 */
    @Test
    fun hideFailureIsLoggedWithoutEscaping() = runTest {
        val writer = RecordingLogWriter()
        val harness = harness(
            failure = { event -> IllegalStateException("broken").takeIf { event.startsWith("hide:") } },
            log = recordingLogger(writer, "TimelineOps"),
        )

        harness.actions.hideFromTimeline(notification("n1", key = "0|k1"))
        runCurrent()

        assertTrue(writer.recorded.any { it.contains("failed to hide from timeline") }, "${writer.recorded}")
    }
}
