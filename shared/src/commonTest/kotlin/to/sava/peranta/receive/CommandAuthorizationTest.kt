package to.sava.peranta.receive

import to.sava.peranta.model.AppRuleSettings
import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Payload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 受信した command が、自端末の転送実績を持つ通知にだけ及ぶことを受信中核ごしに検証する（§3.4）。
 * 認可の判定材料は送信済みタイムラインで、自端末の表示を変える経路は認可の対象外になる。
 */
class CommandAuthorizationTest {

    private val now = 10_000L
    private val deviceId = "phone"
    private val key = "0|com.example|1|null|10"
    private val cipher = MessageCipher(generateKey(), "k1")

    /** 呼ばれたメソッドを記録する通知操作のフェイク。 */
    private class RecordingNotificationOps(val calls: MutableList<String> = mutableListOf()) : NotificationOps {
        override suspend fun dismiss(notificationKey: AuthorizedNotificationKey) { calls.add("dismiss") }
        override suspend fun invokeAction(notificationKey: AuthorizedNotificationKey, actionIndex: Int) {
            calls.add("invokeAction")
        }
        override suspend fun reply(notificationKey: AuthorizedNotificationKey, actionIndex: Int, text: String) {
            calls.add("reply")
        }
        override suspend fun muteApp(packageName: String) { calls.add("muteApp") }
        override suspend fun unmuteApp(packageName: String) { calls.add("unmuteApp") }
        override suspend fun setAppRule(packageName: String, settings: AppRuleSettings) { calls.add("setAppRule") }
    }

    /** 呼ばれたメソッドを記録する自端末表示用のフェイク。 */
    private class RecordingLocalDismiss(val calls: MutableList<String> = mutableListOf()) : CommandExecutor {
        override suspend fun dismiss(notificationKey: String) { calls.add("dismiss") }
        override suspend fun invokeAction(notificationKey: String, actionIndex: Int) { calls.add("invokeAction") }
        override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) { calls.add("reply") }
        override suspend fun muteApp(packageName: String) { calls.add("muteApp") }
        override suspend fun unmuteApp(packageName: String) { calls.add("unmuteApp") }
        override suspend fun setAppRule(packageName: String, settings: AppRuleSettings) { calls.add("setAppRule") }
    }

    private fun notification(id: String = "n1") = NotificationPayload(
        id = id,
        from = "phone",
        to = "*",
        sentAtEpochMillis = now - 100,
        packageName = "com.example",
        appName = "Example",
        title = "t",
        text = "b",
        notificationKey = key,
        postedAtEpochMillis = now - 100,
    )

    /** 自端末が [key] を転送した実績を表すタイムライン。 */
    private fun forwarded(): List<TimelineItem> =
        listOf(SentNotification(id = "n1", timestampEpochMillis = now - 100, payload = notification()))

    private fun command(command: CommandType): CommandPayload = CommandPayload(
        id = "cmd-$command",
        from = "desk",
        to = deviceId,
        sentAtEpochMillis = now - 100,
        command = command,
        targetNotificationKey = key,
        actionIndex = 0,
        replyText = "了解",
        packageName = "com.example.noisy",
    )

    private suspend fun eventFor(payload: Payload): NtfyEvent =
        NtfyEvent(id = "e", time = now, topic = "t", message = encodeEnvelope(cipher.seal(payload)))

    /** 転送実績 [items] を持つ端末として、認可付きの受信中核を組む。 */
    private fun pipeline(
        items: List<TimelineItem>,
        notificationOps: NotificationOps,
        localDismiss: CommandExecutor,
    ) = ReceivePipeline(
        FakeNtfyClient(), cipher, TimelineFeed(JsonlTimelineStore(FakeTimelineFile())), deviceId,
        commandExecutor = RoutingCommandExecutor(
            isNlsConnected = { true },
            isForwardingIntended = { true },
            items = { items },
            notificationOps = notificationOps,
            localDismiss = localDismiss,
        ),
        now = { now },
    )

    /** 転送実績のある通知への invokeAction は通知操作へ届く。 */
    @Test
    fun invokeActionOnForwardedNotificationReachesNotificationOps() = runTest {
        val ops = RecordingNotificationOps()
        val p = pipeline(forwarded(), ops, RecordingLocalDismiss())

        p.handleEvent(eventFor(command(CommandType.INVOKE_ACTION)))

        assertEquals(listOf("invokeAction"), ops.calls)
        assertTrue(p.items.value.isEmpty(), "認可を通った実行はエラーを残さない")
    }

    /** 転送実績が無い通知への invokeAction は通知操作へ届かず、専用種別のエラーになる。 */
    @Test
    fun invokeActionOnUnknownNotificationIsRejected() = runTest {
        val ops = RecordingNotificationOps()
        val p = pipeline(emptyList(), ops, RecordingLocalDismiss())

        p.handleEvent(eventFor(command(CommandType.INVOKE_ACTION)))

        assertTrue(ops.calls.isEmpty())
        val error = p.items.value.single() as ErrorItem
        assertEquals(ErrorKind.COMMAND_UNAUTHORIZED, error.kind)
        assertTrue(error.message.contains("転送していない"))
    }

    /** 転送実績が無い通知への reply も同じく拒否される。 */
    @Test
    fun replyOnUnknownNotificationIsRejected() = runTest {
        val ops = RecordingNotificationOps()
        val p = pipeline(emptyList(), ops, RecordingLocalDismiss())

        p.handleEvent(eventFor(command(CommandType.REPLY)))

        assertTrue(ops.calls.isEmpty())
        assertEquals(ErrorKind.COMMAND_UNAUTHORIZED, (p.items.value.single() as ErrorItem).kind)
    }

    /**
     * 転送実績が無くても、DISMISS の自端末表示に対する処理は完遂する。
     * ミラー通知の取り下げと「元通知は消えた」の印は認可と独立して働く。
     * dismiss は全端末へ同報されるため、転送実績が無いことはエラーとして残さない。
     */
    @Test
    fun dismissWithoutForwardingStillUpdatesLocalDisplay() = runTest {
        val ops = RecordingNotificationOps()
        val local = RecordingLocalDismiss()
        val p = pipeline(emptyList(), ops, local)
        p.handleEvent(eventFor(notification()))

        p.handleEvent(eventFor(command(CommandType.DISMISS)))

        assertEquals(listOf("dismiss"), local.calls)
        assertTrue(ops.calls.isEmpty())
        val received = p.items.value.filterIsInstance<ReceivedNotification>().single()
        assertTrue(received.sourceDismissed, "元通知消滅の印は認可に依らず付く")
        assertTrue(p.items.value.filterIsInstance<ErrorItem>().isEmpty(), "同報される dismiss はエラーに残さない")
    }

    /** muteApp / unmuteApp は対象通知を持たないため、転送実績と無関係に届く。 */
    @Test
    fun muteCommandsAreNotSubjectToAuthorization() = runTest {
        val ops = RecordingNotificationOps()
        val p = pipeline(emptyList(), ops, RecordingLocalDismiss())

        p.handleEvent(eventFor(command(CommandType.MUTE_APP)))
        p.handleEvent(eventFor(command(CommandType.UNMUTE_APP)))

        assertEquals(listOf("muteApp", "unmuteApp"), ops.calls)
        assertTrue(p.items.value.isEmpty())
    }
}
