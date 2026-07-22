package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 通知操作の実行時ルーティングと、設定更新の常時反映を検証する（§3.4/§7）。 */
class RoutingCommandExecutorTest {

    /** 呼ばれた実行器・メソッドを記録するフェイク。任意で通知操作を失敗させられる。 */
    private class RecordingExecutor(
        private val label: String,
        private val log: MutableList<String>,
        private val failNotificationOps: CommandExecutionException? = null,
    ) : CommandExecutor {
        override suspend fun dismiss(notificationKey: String) { log.add("$label.dismiss") }
        override suspend fun invokeAction(notificationKey: String, actionIndex: Int) {
            failNotificationOps?.let { throw it }
            log.add("$label.invokeAction")
        }
        override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) {
            failNotificationOps?.let { throw it }
            log.add("$label.reply")
        }
        override suspend fun muteApp(packageName: String) { log.add("$label.muteApp") }
        override suspend fun unmuteApp(packageName: String) { log.add("$label.unmuteApp") }
    }

    private fun executor(
        log: MutableList<String>,
        connected: Boolean,
        forwarding: Boolean = false,
        notificationOps: CommandExecutor = RecordingExecutor("ops", log),
    ) = RoutingCommandExecutor(
        isNlsConnected = { connected },
        isForwardingIntended = { forwarding },
        notificationOps = notificationOps,
        localDismiss = RecordingExecutor("local", log),
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

    /** 転送の意思がある端末で NLS 未接続なら、invokeAction は通知操作へ委ねられエラーが伝播する。 */
    @Test
    fun invokeActionWhenForwardingButDisconnectedPropagatesError() = runTest {
        val log = mutableListOf<String>()
        val failing = RecordingExecutor("ops", log, failNotificationOps = CommandExecutionException("NLS 未接続"))
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

    /** NLS 接続中は invokeAction/reply を通知操作へ委ねる。 */
    @Test
    fun notificationActionsWhenConnectedRunNotificationOps() = runTest {
        val log = mutableListOf<String>()
        val executor = executor(log, connected = true)
        executor.invokeAction("k", 0)
        executor.reply("k", 0, "hi")
        assertEquals(listOf("ops.invokeAction", "ops.reply"), log)
    }

    /** 委譲先はコマンドごとに判定されるため、構築後に接続が変化しても最新の実態へ従う。 */
    @Test
    fun evaluatesConnectionPerCall() = runTest {
        val log = mutableListOf<String>()
        var connected = false
        val executor = RoutingCommandExecutor(
            isNlsConnected = { connected },
            isForwardingIntended = { false },
            notificationOps = RecordingExecutor("ops", log),
            localDismiss = RecordingExecutor("local", log),
        )

        executor.dismiss("k")
        connected = true
        executor.dismiss("k")

        assertEquals(listOf("local.dismiss", "local.dismiss", "ops.dismiss"), log)
    }
}
