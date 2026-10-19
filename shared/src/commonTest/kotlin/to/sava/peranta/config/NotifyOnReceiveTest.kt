package to.sava.peranta.config

import to.sava.peranta.model.MessagePayload
import to.sava.peranta.timeline.ErrorItem
import to.sava.peranta.timeline.ErrorKind
import to.sava.peranta.timeline.ReceivedMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifyOnReceiveTest {

    private val message = ReceivedMessage(
        id = "msg-1",
        timestampEpochMillis = 1000L,
        payload = MessagePayload(id = "msg-1", from = "phone", to = "*", sentAtEpochMillis = 1000L, text = "hello"),
    )

    private val error = ErrorItem(id = "err-1", timestampEpochMillis = 1000L, message = "boom", kind = ErrorKind.OTHER)

    /** 既定（ON）では受信したアイテムを通知で知らせる。 */
    @Test
    fun notifiesReceivedItemsByDefault() {
        assertTrue(PerantaConfig().notifiesOnReceive(message))
    }

    /** OFF にすると受信したアイテムは通知で知らせない。 */
    @Test
    fun suppressesReceivedItemsWhenOff() {
        assertFalse(PerantaConfig(notifyOnReceive = false).notifiesOnReceive(message))
    }

    /** OFF でもエラーは通知で知らせる。 */
    @Test
    fun notifiesErrorsEvenWhenOff() {
        assertTrue(PerantaConfig(notifyOnReceive = false).notifiesOnReceive(error))
    }
}
