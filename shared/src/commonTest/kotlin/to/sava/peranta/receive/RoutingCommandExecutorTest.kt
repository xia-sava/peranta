package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.send.withSmsNotificationKey
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.SentNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 通知操作の実行時ルーティング・認可と、設定更新の常時反映を検証する（§3.4/§7）。 */
class RoutingCommandExecutorTest {

    /** 呼ばれたメソッドを記録する自端末表示用のフェイク。 */
    private class RecordingLocalDismiss(private val log: MutableList<String>) : CommandExecutor {
        override suspend fun dismiss(notificationKey: String) { log.add("local.dismiss") }
        override suspend fun invokeAction(notificationKey: String, actionIndex: Int) { log.add("local.invokeAction") }
        override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) { log.add("local.reply") }
        override suspend fun muteApp(packageName: String) { log.add("local.muteApp") }
        override suspend fun unmuteApp(packageName: String) { log.add("local.unmuteApp") }
    }

    /** 呼ばれたメソッドを記録する通知操作のフェイク。任意で通知操作を失敗させられる。 */
    private class RecordingNotificationOps(
        private val log: MutableList<String>,
        private val failWith: CommandExecutionException? = null,
    ) : NotificationOps {
        override suspend fun dismiss(notificationKey: AuthorizedNotificationKey) { log.add("ops.dismiss") }
        override suspend fun invokeAction(notificationKey: AuthorizedNotificationKey, actionIndex: Int) {
            failWith?.let { throw it }
            log.add("ops.invokeAction")
        }
        override suspend fun reply(notificationKey: AuthorizedNotificationKey, actionIndex: Int, text: String) {
            failWith?.let { throw it }
            log.add("ops.reply")
        }
        override suspend fun muteApp(packageName: String) { log.add("ops.muteApp") }
        override suspend fun unmuteApp(packageName: String) { log.add("ops.unmuteApp") }
    }

    private fun notification(key: String, id: String = "n1") = NotificationPayload(
        id = id,
        from = "phone",
        to = "*",
        sentAtEpochMillis = 1,
        packageName = "com.example",
        appName = "Example",
        title = "t",
        text = "b",
        notificationKey = key,
        postedAtEpochMillis = 1,
    )

    /** 自端末が [key] を転送した実績を表すタイムライン。 */
    private fun forwarded(key: String): List<TimelineItem> =
        listOf(SentNotification(id = "n1", timestampEpochMillis = 1, payload = notification(key)))

    private fun executor(
        log: MutableList<String>,
        connected: Boolean,
        forwarding: Boolean = false,
        items: List<TimelineItem> = forwarded("k"),
        notificationOps: NotificationOps = RecordingNotificationOps(log),
    ) = RoutingCommandExecutor(
        isNlsConnected = { connected },
        isForwardingIntended = { forwarding },
        items = { items },
        notificationOps = notificationOps,
        localDismiss = RecordingLocalDismiss(log),
    )

    /** 設定更新（mute/unmute）は NLS 未接続でも必ず設定更新実装へ届く（恒久消失を防ぐ）。 */
    @Test
    fun muteReachesNotificationOpsEvenWhenDisconnected() = runTest {
        val log = mutableListOf<String>()
        val executor = executor(log, connected = false, forwarding = false)

        executor.muteApp("p")
        executor.unmuteApp("p")

        assertEquals(listOf("ops.muteApp", "ops.unmuteApp"), log)
    }

    /** 設定更新は対象通知を持たないため、転送実績が無くても届く（認可の対象外）。 */
    @Test
    fun muteIsNotSubjectToAuthorization() = runTest {
        val log = mutableListOf<String>()
        val executor = executor(log, connected = true, items = emptyList())

        executor.muteApp("p")
        executor.unmuteApp("p")

        assertEquals(listOf("ops.muteApp", "ops.unmuteApp"), log)
    }

    /** dismiss は NLS 接続時、表示済みミラーの取り下げと元通知の取り下げの両方を実行する。 */
    @Test
    fun dismissWhenConnectedRunsBothLocalAndNotificationOps() = runTest {
        val log = mutableListOf<String>()
        executor(log, connected = true).dismiss("k")
        assertEquals(listOf("local.dismiss", "ops.dismiss"), log)
    }

    /** dismiss は NLS 未接続時、表示済みミラーの取り下げのみを実行する。 */
    @Test
    fun dismissWhenDisconnectedRunsOnlyLocal() = runTest {
        val log = mutableListOf<String>()
        executor(log, connected = false).dismiss("k")
        assertEquals(listOf("local.dismiss"), log)
    }

    /**
     * 転送実績が無くても、自端末が表示したミラー通知の取り下げは実行される。
     * 認可で止まるのは元通知の取り下げだけで、全端末へ同報される dismiss は拒否を例外にしない。
     */
    @Test
    fun dismissRunsLocalEvenWhenNotAuthorized() = runTest {
        val log = mutableListOf<String>()
        val executor = executor(log, connected = true, items = emptyList())

        executor.dismiss("k")

        assertEquals(listOf("local.dismiss"), log)
    }

    /** 転送の意思がある端末で NLS 未接続なら、invokeAction は通知操作へ委ねられエラーが伝播する。 */
    @Test
    fun invokeActionWhenForwardingButDisconnectedPropagatesError() = runTest {
        val log = mutableListOf<String>()
        val failing = RecordingNotificationOps(log, failWith = CommandExecutionException("NLS 未接続"))
        val executor = executor(log, connected = false, forwarding = true, notificationOps = failing)

        assertFailsWith<CommandExecutionException> { executor.invokeAction("k", 0) }
        assertTrue(log.none { it.startsWith("local.") }, "受信専用のスキップ経路には回さない")
    }

    /** 受信専用端末（転送の意思なし）で NLS 未接続なら、invokeAction は静かにスキップされる。 */
    @Test
    fun invokeActionWhenReceiveOnlyAndDisconnectedSkipsSilently() = runTest {
        val log = mutableListOf<String>()
        executor(log, connected = false, forwarding = false).invokeAction("k", 0)
        assertEquals(listOf("local.invokeAction"), log)
    }

    /** スキップ経路は認可を通らないため、転送実績が無くても拒否ではなくスキップになる。 */
    @Test
    fun invokeActionSkipPathIsNotAuthorized() = runTest {
        val log = mutableListOf<String>()
        executor(log, connected = false, forwarding = false, items = emptyList()).invokeAction("k", 0)
        assertEquals(listOf("local.invokeAction"), log)
    }

    /** NLS 接続中は invokeAction/reply を通知操作へ委ねる。 */
    @Test
    fun notificationActionsWhenConnectedRunNotificationOps() = runTest {
        val log = mutableListOf<String>()
        val executor = executor(log, connected = true)
        executor.invokeAction("k", 0)
        executor.reply("k", 0, "hi")
        assertEquals(listOf("ops.invokeAction", "ops.reply"), log)
    }

    /** 転送実績が無い通知への invokeAction / reply は通知操作へ届かず拒否される。 */
    @Test
    fun notificationActionsForUnknownKeyAreRejected() = runTest {
        val log = mutableListOf<String>()
        val executor = executor(log, connected = true, items = forwarded("other"))

        assertFailsWith<CommandUnauthorizedException> { executor.invokeAction("k", 0) }
        assertFailsWith<CommandUnauthorizedException> { executor.reply("k", 0, "hi") }
        assertTrue(log.isEmpty(), "認可に落ちた操作は通知操作へ届かない")
    }

    /** 他端末から受信しただけの通知は転送実績にならず、認可されない。 */
    @Test
    fun receivedNotificationDoesNotAuthorize() = runTest {
        val log = mutableListOf<String>()
        val received = listOf<TimelineItem>(
            ReceivedNotification(id = "n1", timestampEpochMillis = 1, payload = notification("k")),
        )
        val executor = executor(log, connected = true, items = received)

        assertFailsWith<CommandUnauthorizedException> { executor.invokeAction("k", 0) }
        assertTrue(log.isEmpty())
    }

    /** 通知 key が入った改版として転送された SMS（§3.1）も転送実績として認可される。 */
    @Test
    fun revisedSmsWithNotificationKeyAuthorizes() = runTest {
        val log = mutableListOf<String>()
        val key = "0|com.android.messaging|7|null|10"
        val sms = SmsPayload(
            id = "s1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 1,
            senderNumber = "123",
            text = "code",
            postedAtEpochMillis = 1,
        )
        val revised = withSmsNotificationKey(sms, key)
        val items = listOf<TimelineItem>(
            SentNotification(id = revised.id, timestampEpochMillis = 1, payload = revised),
        )

        executor(log, connected = true, items = items).invokeAction(key, 0)

        assertEquals(listOf("ops.invokeAction"), log)
    }

    /** 委譲先はコマンドごとに判定されるため、構築後に接続が変化しても最新の実態へ従う。 */
    @Test
    fun evaluatesConnectionPerCall() = runTest {
        val log = mutableListOf<String>()
        var connected = false
        val executor = RoutingCommandExecutor(
            isNlsConnected = { connected },
            isForwardingIntended = { false },
            items = { forwarded("k") },
            notificationOps = RecordingNotificationOps(log),
            localDismiss = RecordingLocalDismiss(log),
        )

        executor.dismiss("k")
        connected = true
        executor.dismiss("k")

        assertEquals(listOf("local.dismiss", "local.dismiss", "ops.dismiss"), log)
    }
}
