package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.crypto.MessageCipher
import to.sava.peranta.crypto.generateKey
import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.Payload
import to.sava.peranta.model.encodeEnvelope
import to.sava.peranta.net.FakeNtfyClient
import to.sava.peranta.net.NtfyEvent
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.FakeTimelineFile
import to.sava.peranta.timeline.JsonlTimelineStore
import to.sava.peranta.timeline.TimelineFeed
import to.sava.peranta.timeline.TimelineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 受信した command ペイロードが [CommandExecutor] へ正しくディスパッチされることを検証する（§3.4）。 */
class CommandExecutionTest {

    private val now = 10_000L
    private val deviceId = "phone"
    private val cipher = MessageCipher(generateKey(), "k1")

    /** 呼ばれたメソッドと引数を記録するフェイク実行器。任意で例外を投げさせられる。 */
    private class FakeCommandExecutor(
        private val failWith: CommandExecutionException? = null,
    ) : CommandExecutor {
        val calls = mutableListOf<String>()
        var dismissedKey: String? = null
        var invoked: Pair<String, Int>? = null
        var replied: Triple<String, Int, String>? = null
        var mutedPackage: String? = null
        var unmutedPackage: String? = null

        override suspend fun dismiss(notificationKey: String) {
            failWith?.let { throw it }
            dismissedKey = notificationKey
            calls.add("dismiss")
        }

        override suspend fun invokeAction(notificationKey: String, actionIndex: Int) {
            failWith?.let { throw it }
            invoked = notificationKey to actionIndex
            calls.add("invokeAction")
        }

        override suspend fun reply(notificationKey: String, actionIndex: Int, text: String) {
            failWith?.let { throw it }
            replied = Triple(notificationKey, actionIndex, text)
            calls.add("reply")
        }

        override suspend fun muteApp(packageName: String) {
            failWith?.let { throw it }
            mutedPackage = packageName
            calls.add("muteApp")
        }

        override suspend fun unmuteApp(packageName: String) {
            failWith?.let { throw it }
            unmutedPackage = packageName
            calls.add("unmuteApp")
        }
    }

    private fun store(): TimelineStore = JsonlTimelineStore(FakeTimelineFile())

    private fun pipeline(
        executor: CommandExecutor?,
        store: TimelineStore = store(),
    ) = ReceivePipeline(
        FakeNtfyClient(), cipher, TimelineFeed(store), deviceId,
        commandExecutor = executor,
        now = { now },
    )

    private fun command(
        command: CommandType,
        to: String = deviceId,
        targetNotificationKey: String? = "0|com.example|1|null|10",
        actionIndex: Int? = 0,
        replyText: String? = "了解",
        packageName: String? = "com.example.noisy",
        expiresAt: Long? = null,
        id: String = "cmd1",
    ): CommandPayload = CommandPayload(
        id = id,
        from = "desk",
        to = to,
        sentAtEpochMillis = now - 100,
        command = command,
        targetNotificationKey = targetNotificationKey,
        actionIndex = actionIndex,
        replyText = replyText,
        packageName = packageName,
        expiresAtEpochMillis = expiresAt,
    )

    private suspend fun eventFor(payload: Payload): NtfyEvent =
        NtfyEvent(id = "e", time = now, topic = "t", message = encodeEnvelope(cipher.seal(payload)))

    /** dismiss コマンドは executor.dismiss を対象キーで呼ぶ。 */
    @Test
    fun dismissIsDispatched() = runTest {
        val executor = FakeCommandExecutor()
        pipeline(executor).handleEvent(eventFor(command(CommandType.DISMISS)))
        assertEquals(listOf("dismiss"), executor.calls)
        assertEquals("0|com.example|1|null|10", executor.dismissedKey)
    }

    /** invokeAction コマンドは executor.invokeAction を対象キーとアクション番号で呼ぶ。 */
    @Test
    fun invokeActionIsDispatched() = runTest {
        val executor = FakeCommandExecutor()
        pipeline(executor).handleEvent(eventFor(command(CommandType.INVOKE_ACTION, actionIndex = 2)))
        assertEquals(listOf("invokeAction"), executor.calls)
        assertEquals("0|com.example|1|null|10" to 2, executor.invoked)
    }

    /** reply コマンドは executor.reply を対象キー・アクション番号・返信本文で呼ぶ。 */
    @Test
    fun replyIsDispatched() = runTest {
        val executor = FakeCommandExecutor()
        pipeline(executor).handleEvent(eventFor(command(CommandType.REPLY, actionIndex = 1, replyText = "OK")))
        assertEquals(listOf("reply"), executor.calls)
        assertEquals(Triple("0|com.example|1|null|10", 1, "OK"), executor.replied)
    }

    /** muteApp コマンドは executor.muteApp をパッケージ名で呼ぶ。 */
    @Test
    fun muteAppIsDispatched() = runTest {
        val executor = FakeCommandExecutor()
        pipeline(executor).handleEvent(eventFor(command(CommandType.MUTE_APP, packageName = "com.spam")))
        assertEquals(listOf("muteApp"), executor.calls)
        assertEquals("com.spam", executor.mutedPackage)
    }

    /** unmuteApp コマンドは executor.unmuteApp をパッケージ名で呼ぶ。 */
    @Test
    fun unmuteAppIsDispatched() = runTest {
        val executor = FakeCommandExecutor()
        pipeline(executor).handleEvent(eventFor(command(CommandType.UNMUTE_APP, packageName = "com.spam")))
        assertEquals(listOf("unmuteApp"), executor.calls)
        assertEquals("com.spam", executor.unmutedPackage)
    }

    /** 失効済みコマンドは実行されない（遅延した操作の誤発火を防ぐ）。 */
    @Test
    fun expiredCommandIsNotExecuted() = runTest {
        val executor = FakeCommandExecutor()
        val store = store()
        pipeline(executor, store).handleEvent(eventFor(command(CommandType.DISMISS, expiresAt = now - 1)))
        assertTrue(executor.calls.isEmpty())
        assertTrue(store.loadAll().isEmpty())
    }

    /** 自分宛でないコマンドは実行されない。 */
    @Test
    fun commandForOtherDeviceIsNotExecuted() = runTest {
        val executor = FakeCommandExecutor()
        pipeline(executor).handleEvent(eventFor(command(CommandType.DISMISS, to = "someone-else")))
        assertTrue(executor.calls.isEmpty())
    }

    /** executor 未注入の端末はコマンドを無視し、エラーも残さない。 */
    @Test
    fun commandWithoutExecutorIsIgnored() = runTest {
        val store = store()
        val p = pipeline(executor = null, store = store)
        p.handleEvent(eventFor(command(CommandType.DISMISS)))
        assertTrue(p.items.value.isEmpty())
        assertTrue(store.loadAll().isEmpty())
    }

    /** 必須フィールド欠落のコマンドは実行されず、COMMAND_EXECUTION エラーが記録される。 */
    @Test
    fun commandMissingRequiredFieldRecordsError() = runTest {
        val cases = listOf(
            command(CommandType.DISMISS, targetNotificationKey = null),
            command(CommandType.INVOKE_ACTION, actionIndex = null),
            command(CommandType.REPLY, replyText = null),
            command(CommandType.MUTE_APP, packageName = null),
            command(CommandType.UNMUTE_APP, packageName = null),
        )
        cases.forEachIndexed { index, payload ->
            val executor = FakeCommandExecutor()
            val p = pipeline(executor)
            p.handleEvent(eventFor(payload.copy(id = "missing-$index")))
            assertTrue(executor.calls.isEmpty(), "executor should not run for $payload")
            val error = p.items.value.single() as ErrorItem
            assertEquals(ErrorKind.COMMAND_EXECUTION, error.kind)
        }
    }

    /** executor が実行失敗を投げると COMMAND_EXECUTION エラーとして記録される。 */
    @Test
    fun executorFailureRecordsError() = runTest {
        val executor = FakeCommandExecutor(failWith = CommandExecutionException("対象の通知が見つかりません"))
        val p = pipeline(executor)
        p.handleEvent(eventFor(command(CommandType.DISMISS)))
        val error = p.items.value.single() as ErrorItem
        assertEquals(ErrorKind.COMMAND_EXECUTION, error.kind)
        assertTrue(error.message.contains("見つかりません"))
    }

    /** 同一 id のコマンドを 2 回受信しても 1 回だけ実行する（再送の二重発火防止）。 */
    @Test
    fun duplicateCommandIsExecutedOnce() = runTest {
        val executor = FakeCommandExecutor()
        val p = pipeline(executor)
        p.handleEvent(eventFor(command(CommandType.DISMISS)))
        p.handleEvent(eventFor(command(CommandType.DISMISS)))
        assertEquals(listOf("dismiss"), executor.calls)
        assertNull(p.items.value.firstOrNull())
    }
}
