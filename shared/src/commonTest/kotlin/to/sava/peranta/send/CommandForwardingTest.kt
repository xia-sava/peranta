package to.sava.peranta.send

import to.sava.peranta.model.BROADCAST_TARGET
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.PresencePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandForwardingTest {

    private val now = 1_000_000L

    /** command は送信時刻 + 短命 TTL の失効を付け、指定したフィールドをそのまま載せる。 */
    @Test
    fun buildsCommandWithShortExpiryAndFields() {
        val command = buildCommandPayload(
            command = CommandType.REPLY,
            from = "desk",
            to = "phone",
            now = now,
            targetNotificationKey = "0|com.example|1|null|10",
            replyText = "了解",
            idGen = { "cmd-1" },
        )
        assertEquals("cmd-1", command.id)
        assertEquals("desk", command.from)
        assertEquals("phone", command.to)
        assertEquals(CommandType.REPLY, command.command)
        assertEquals("0|com.example|1|null|10", command.targetNotificationKey)
        assertEquals("了解", command.replyText)
        assertEquals(now + COMMAND_TTL_MILLIS, command.expiresAtEpochMillis)
    }

    /** command の TTL は OTP 本文の TTL より短い（即時性が前提のため）。 */
    @Test
    fun commandTtlIsShorterThanOtpTtl() {
        assertEquals(true, COMMAND_TTL_MILLIS < OTP_TTL_MILLIS)
    }

    /** expiresOf は command の失効時刻を取り出す。 */
    @Test
    fun expiresOfReturnsCommandExpiry() {
        val command = buildCommandPayload(
            command = CommandType.DISMISS,
            from = "desk",
            to = BROADCAST_TARGET,
            now = now,
        )
        assertEquals(now + COMMAND_TTL_MILLIS, expiresOf(command))
    }

    /** 失効の概念を持たない presence は expiresOf が null を返す（ロスター最新採用に委ねる）。 */
    @Test
    fun expiresOfIsNullForPresence() {
        val presence = PresencePayload(
            id = "p1",
            from = "desk",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = now,
            deviceName = "Desk",
            endpoint = "https://h/desk",
        )
        assertNull(expiresOf(presence))
    }

    /** notification の失効時刻も従来どおり取り出せる（回帰確認）。 */
    @Test
    fun expiresOfReturnsNotificationExpiry() {
        val notification = NotificationPayload(
            id = "n1",
            from = "phone",
            to = BROADCAST_TARGET,
            sentAtEpochMillis = now,
            packageName = "com.example.bank",
            appName = "Bank",
            title = "t",
            text = "b",
            notificationKey = "0|com.example.bank|1|null|10",
            postedAtEpochMillis = now,
            expiresAtEpochMillis = now + 5_000,
        )
        assertEquals(now + 5_000, expiresOf(notification))
    }
}
