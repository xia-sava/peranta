package to.sava.peranta.send

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.CommandAckPayload
import to.sava.peranta.model.CommandOutcome
import to.sava.peranta.model.CommandType
import to.sava.peranta.timeline.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CommandAckTracker] が応答の有無と成否に応じて知らせること（§3.4）を検証する。 */
class CommandAckTrackerTest {

    private val request = AckRequest(ackBy = "phone", ackTopic = "desk-topic", targetName = "AQUOS")

    /** [reread] は待ち時間を過ぎたときの読み直しの結果（null は読み直せなかった）。 */
    private class Harness(scope: TestScope, reread: List<CommandAckPayload>? = emptyList()) {
        val reports = mutableListOf<Pair<ErrorKind, String>>()
        val rereadTopics = mutableListOf<String>()
        val tracker = CommandAckTracker(
            scope = scope.backgroundScope,
            onProblem = { kind, message -> reports.add(kind to message) },
            history = { topic, _ ->
                rereadTopics.add(topic)
                reread
            },
            now = { scope.testScheduler.currentTime },
        )
    }

    private fun ack(
        commandId: String,
        outcome: CommandOutcome = CommandOutcome.EXECUTED,
        message: String? = null,
        command: CommandType = CommandType.INVOKE_ACTION,
        targetMissing: Boolean = false,
    ) = CommandAckPayload(
        id = "ack-$commandId",
        from = "phone",
        to = "desk",
        sentAtEpochMillis = 0,
        commandId = commandId,
        command = command,
        outcome = outcome,
        message = message,
        targetMissing = targetMissing,
    )

    private suspend fun Harness.send(commandId: String, command: CommandType = CommandType.INVOKE_ACTION) {
        tracker.register(commandId, command, request)
        tracker.arm(commandId)
    }

    /** 待ち時間内に成功の応答が届けば何も知らせない。 */
    @Test
    fun executedAckWithinTimeoutReportsNothing() = runTest {
        val harness = Harness(this)
        harness.send("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS - 1)
        harness.tracker.onAck(ack("c1"))
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS)
        runCurrent()
        assertTrue(harness.reports.isEmpty())
    }

    /**
     * 待ち時間内に応答が来ず、自端末の受信 topic を読み直しても無ければ、応答側の端末名を添えて
     * その端末の受信を疑う文言で知らせる。
     */
    @Test
    fun missingAckReportsUnacknowledged() = runTest {
        val harness = Harness(this)
        harness.send("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("desk-topic"), harness.rereadTopics)
        val (kind, message) = harness.reports.single()
        assertEquals(ErrorKind.COMMAND_UNACKNOWLEDGED, kind)
        assertEquals(unacknowledgedCommandMessage("AQUOS"), message)
    }

    /** 自端末の購読が切れていて取りこぼした応答も、読み直しで見つかれば応答なしを知らせない。 */
    @Test
    fun ackRecoveredFromHistoryReportsNothing() = runTest {
        val harness = Harness(this, reread = listOf(ack("other"), ack("c1")))
        harness.send("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertTrue(harness.reports.isEmpty())
    }

    /** 読み直しで見つかった応答が失敗なら、その理由を知らせる。 */
    @Test
    fun failedAckRecoveredFromHistoryReportsReason() = runTest {
        val harness = Harness(this, reread = listOf(ack("c1", CommandOutcome.FAILED, "理由")))
        harness.send("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(ErrorKind.COMMAND_EXECUTION to failedCommandMessage("AQUOS", "理由")), harness.reports)
    }

    /** 読み直せなかったときは、相手とこの端末のどちらの受信かを決めつけない文言で知らせる。 */
    @Test
    fun unreadableHistoryReportsUnconfirmed() = runTest {
        val harness = Harness(this, reread = null)
        harness.send("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(
            listOf(ErrorKind.COMMAND_UNACKNOWLEDGED to unconfirmedCommandMessage("AQUOS")),
            harness.reports,
        )
    }

    /** 失敗の応答は、実行端末が返した理由を添えて知らせる。 */
    @Test
    fun failedAckReportsReason() = runTest {
        val harness = Harness(this)
        harness.send("c1")
        harness.tracker.onAck(ack("c1", CommandOutcome.FAILED, "通知はすでに消えています。"))
        runCurrent()
        assertEquals(
            listOf(ErrorKind.COMMAND_EXECUTION to failedCommandMessage("AQUOS", "通知はすでに消えています。")),
            harness.reports,
        )
    }

    /** 対象通知が既に存在しなかったアクション発火の失敗は知らせない。 */
    @Test
    fun targetMissingActionAckReportsNothing() = runTest {
        val harness = Harness(this)
        harness.send("c1")
        harness.tracker.onAck(ack("c1", CommandOutcome.FAILED, "理由", targetMissing = true))
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertTrue(harness.reports.isEmpty())
    }

    /** 読み直しで見つかった応答でも、対象通知が既に存在しなかったアクション発火の失敗は知らせない。 */
    @Test
    fun targetMissingActionAckRecoveredFromHistoryReportsNothing() = runTest {
        val harness = Harness(this, reread = listOf(ack("c1", CommandOutcome.FAILED, "理由", targetMissing = true)))
        harness.send("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertTrue(harness.reports.isEmpty())
    }

    /** 返信は、対象通知が既に存在しなかった失敗でも理由を添えて知らせる（打った本文が届いていない）。 */
    @Test
    fun targetMissingReplyAckReportsReason() = runTest {
        val harness = Harness(this)
        harness.send("c1", CommandType.REPLY)
        harness.tracker.onAck(
            ack("c1", CommandOutcome.FAILED, "理由", command = CommandType.REPLY, targetMissing = true),
        )
        runCurrent()
        assertEquals(listOf(ErrorKind.COMMAND_EXECUTION to failedCommandMessage("AQUOS", "理由")), harness.reports)
    }

    /** 同じ端末の応答なしは最小間隔の間に 1 度しか知らせず、間隔を過ぎればまた知らせる。 */
    @Test
    fun unacknowledgedWarningIsThrottledPerDevice() = runTest {
        val harness = Harness(this)
        harness.send("c1")
        harness.send("c2")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(1, harness.reports.size)

        advanceTimeBy(UNACKNOWLEDGED_WARNING_INTERVAL_MILLIS)
        harness.send("c3")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(2, harness.reports.size)
    }

    /** publish の前に届いた応答（待ち時間を数え始める前）でも待ちは解け、後から応答なしを知らせない。 */
    @Test
    fun ackBeforeArmIsHonored() = runTest {
        val harness = Harness(this)
        harness.tracker.register("c1", CommandType.INVOKE_ACTION, request)
        harness.tracker.onAck(ack("c1"))
        harness.tracker.arm("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS * 2)
        runCurrent()
        assertTrue(harness.reports.isEmpty())
    }

    /** publish に失敗して取り消した待ちは、応答なしとして知らせない。 */
    @Test
    fun cancelledWaitReportsNothing() = runTest {
        val harness = Harness(this)
        harness.tracker.register("c1", CommandType.INVOKE_ACTION, request)
        harness.tracker.cancel("c1")
        harness.tracker.arm("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS * 2)
        runCurrent()
        assertTrue(harness.reports.isEmpty())
    }

    /** 待っていないコマンドへの応答（遅着）は知らせない。 */
    @Test
    fun lateAckAfterTimeoutIsIgnored() = runTest {
        val harness = Harness(this)
        harness.send("c1")
        advanceTimeBy(COMMAND_ACK_TIMEOUT_MILLIS + 1)
        runCurrent()
        harness.tracker.onAck(ack("c1", CommandOutcome.FAILED, "late"))
        runCurrent()
        assertEquals(listOf(ErrorKind.COMMAND_UNACKNOWLEDGED), harness.reports.map { it.first })
    }
}
