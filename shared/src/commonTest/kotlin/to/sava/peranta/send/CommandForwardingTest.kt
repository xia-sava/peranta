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

    /** command は送信時刻 + 配送特性の猶予による失効を付け、指定したフィールドをそのまま載せる。 */
    @Test
    fun buildsCommandWithDeliveryExpiryAndFields() {
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
        assertEquals(now + CommandDelivery.IMMEDIATE.ttlMillis, command.expiresAtEpochMillis)
    }

    /** 即時操作の TTL は OTP 本文の TTL より短い（遅れて実行されると誤爆するため）。 */
    @Test
    fun immediateCommandTtlIsShorterThanOtpTtl() {
        assertEquals(true, CommandDelivery.IMMEDIATE.ttlMillis < OTP_TTL_MILLIS)
    }

    /** 遅れて届いても結果の変わらない command は状態同期、誤爆する command は即時操作に分類する。 */
    @Test
    fun classifiesCommandsByDeliveryCharacteristics() {
        listOf(CommandType.DISMISS, CommandType.MUTE_APP, CommandType.UNMUTE_APP).forEach {
            assertEquals(CommandDelivery.STATE_SYNC, deliveryOf(it), "$it は状態同期")
        }
        listOf(CommandType.INVOKE_ACTION, CommandType.REPLY).forEach {
            assertEquals(CommandDelivery.IMMEDIATE, deliveryOf(it), "$it は即時操作")
        }
    }

    /**
     * 状態同期は受信端末が半日単位で離れていても届く必要があるため、即時操作より長く保ち、
     * サーバのキャッシュ保持は指定せず設定へ委ねる。
     */
    @Test
    fun stateSyncOutlivesImmediateAndLeavesCacheToServer() {
        assertEquals(true, CommandDelivery.STATE_SYNC.ttlMillis > CommandDelivery.IMMEDIATE.ttlMillis)
        assertNull(CommandDelivery.STATE_SYNC.cacheSeconds)
        assertEquals(HIGH_PRIORITY_CACHE_SECONDS, CommandDelivery.IMMEDIATE.cacheSeconds)
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
        assertEquals(now + CommandDelivery.STATE_SYNC.ttlMillis, expiresOf(command))
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
