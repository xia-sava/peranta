package to.sava.peranta.receive

import to.sava.peranta.model.CommandPayload
import to.sava.peranta.model.CommandType
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.Priority
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ReceivedNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationDisplayTest {

    private fun received(payload: to.sava.peranta.model.Payload) = ReceivedNotification(
        id = payload.id,
        timestampEpochMillis = 1_000L,
        payload = payload,
        expiresAtEpochMillis = null,
    )

    private fun notification(
        title: String = "Verification code",
        text: String = "123456",
        appName: String = "Bank",
        priority: Priority = Priority.HIGH,
        expiresAt: Long? = 5_000L,
    ) = NotificationPayload(
        id = "n1",
        from = "phone",
        to = "*",
        sentAtEpochMillis = 900L,
        packageName = "com.example.bank",
        appName = appName,
        title = title,
        text = text,
        notificationKey = "0|com.example.bank|1|null|10",
        postedAtEpochMillis = 900L,
        expiresAtEpochMillis = expiresAt,
        priority = priority,
    )

    /** 通知 payload はタイトル・本文・優先度・失効時刻を表示内容へ写す。 */
    @Test
    fun notificationMapsToDisplay() {
        val display = displayFor(received(notification()))!!
        assertEquals("n1", display.id)
        assertEquals("Verification code", display.title)
        assertEquals("123456", display.body)
        assertEquals(Priority.HIGH, display.priority)
        assertEquals(5_000L, display.expiresAtEpochMillis)
    }

    /** タイトルが空なら appName、それも空なら固定値へフォールバックする。 */
    @Test
    fun blankTitleFallsBackToAppNameThenConstant() {
        assertEquals("Bank", displayFor(received(notification(title = "")))!!.title)
        assertEquals("Peranta", displayFor(received(notification(title = "", appName = "")))!!.title)
    }

    /** 本文が空なら固定の代替本文になる。 */
    @Test
    fun blankBodyFallsBackToConstant() {
        assertEquals("（本文なし）", displayFor(received(notification(text = "")))!!.body)
    }

    /** SMS payload は送信者名（無ければ番号）をタイトルにする。 */
    @Test
    fun smsMapsSenderToTitle() {
        val sms = SmsPayload(
            id = "s1",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "+81900000000",
            senderName = "銀行",
            text = "コードは 999999 です",
            postedAtEpochMillis = 900L,
            expiresAtEpochMillis = 6_000L,
            priority = Priority.HIGH,
        )
        val display = displayFor(received(sms))!!
        assertEquals("銀行", display.title)
        assertEquals("コードは 999999 です", display.body)
        assertEquals(Priority.HIGH, display.priority)
    }

    /** 送信者名が無い SMS は番号をタイトルにする。 */
    @Test
    fun smsWithoutNameUsesNumber() {
        val sms = SmsPayload(
            id = "s2",
            from = "phone",
            to = "*",
            sentAtEpochMillis = 900L,
            senderNumber = "12345",
            senderName = null,
            text = "hi",
            postedAtEpochMillis = 900L,
        )
        assertEquals("12345", displayFor(received(sms))!!.title)
    }

    /** 表示対象外の payload（command 等）は null を返す。 */
    @Test
    fun nonDisplayablePayloadReturnsNull() {
        val command = CommandPayload(
            id = "c1",
            from = "desk",
            to = "phone",
            sentAtEpochMillis = 900L,
            command = CommandType.DISMISS,
            targetNotificationKey = "0|com.example|1|null|10",
        )
        assertNull(displayFor(received(command)))
    }

    /** 優先度は対応する通知チャネル区分へ一対一で写る。 */
    @Test
    fun priorityMapsToChannelKind() {
        assertEquals(NotificationChannelKind.HIGH, channelKindFor(Priority.HIGH))
        assertEquals(NotificationChannelKind.NORMAL, channelKindFor(Priority.NORMAL))
        assertEquals(NotificationChannelKind.LOW, channelKindFor(Priority.LOW))
    }
}
