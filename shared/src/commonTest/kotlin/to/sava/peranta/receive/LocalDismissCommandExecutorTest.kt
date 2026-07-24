package to.sava.peranta.receive

import kotlinx.coroutines.test.runTest
import to.sava.peranta.model.NotificationPayload
import to.sava.peranta.model.SmsPayload
import to.sava.peranta.timeline.ReceivedNotification
import to.sava.peranta.timeline.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 受信専用端末向け [LocalDismissCommandExecutor] の dismiss と no-op を検証する（§3.4）。 */
class LocalDismissCommandExecutorTest {

    private fun notification(key: String, id: String) = NotificationPayload(
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

    private fun received(key: String, id: String): ReceivedNotification =
        ReceivedNotification(id = id, timestampEpochMillis = 1, payload = notification(key, id))

    /** dismiss は同じ notificationKey の受信通知を見つけ、その payload.id で取り下げる。 */
    @Test
    fun dismissDismissesMatchingNotificationByPayloadId() = runTest {
        val dismissed = mutableListOf<String>()
        val items = listOf<TimelineItem>(received("0|k1", "id-1"), received("0|k2", "id-2"))
        val executor = LocalDismissCommandExecutor(items = { items }, dismissLocal = { dismissed.add(it) })
        executor.dismiss("0|k2")
        assertEquals(listOf("id-2"), dismissed)
    }

    /**
     * 同一 notificationKey の受信通知が複数あれば全件を取り下げる（Google Messages 等の再投稿）。
     * 最古の 1 件だけが取り下げられる不具合の回帰。
     */
    @Test
    fun dismissDismissesAllMatchingNotificationsSharingKey() = runTest {
        val dismissed = mutableListOf<String>()
        val items = listOf<TimelineItem>(received("0|k1", "id-1"), received("0|k1", "id-2"))
        val executor = LocalDismissCommandExecutor(items = { items }, dismissLocal = { dismissed.add(it) })
        executor.dismiss("0|k1")
        assertEquals(listOf("id-1", "id-2"), dismissed)
    }

    /** 対象が見つからない dismiss は非致命的に扱い、何も取り下げない。 */
    @Test
    fun dismissWithoutMatchIsNonFatal() = runTest {
        val dismissed = mutableListOf<String>()
        val executor = LocalDismissCommandExecutor(items = { emptyList() }, dismissLocal = { dismissed.add(it) })
        executor.dismiss("0|missing")
        assertTrue(dismissed.isEmpty())
    }

    /** notificationKey を持たない SMS 等は dismiss の対象にならない。 */
    @Test
    fun dismissIgnoresNonNotificationPayloads() = runTest {
        val dismissed = mutableListOf<String>()
        val sms = ReceivedNotification(
            id = "s",
            timestampEpochMillis = 1,
            payload = SmsPayload(
                id = "s",
                from = "phone",
                to = "*",
                sentAtEpochMillis = 1,
                senderNumber = "123",
                text = "code",
                postedAtEpochMillis = 1,
            ),
        )
        val executor = LocalDismissCommandExecutor(items = { listOf(sms) }, dismissLocal = { dismissed.add(it) })
        executor.dismiss("anything")
        assertTrue(dismissed.isEmpty())
    }

    /** invokeAction / reply / muteApp / unmuteApp は表示専用端末では no-op（例外も取り下げも起こさない）。 */
    @Test
    fun otherCommandsAreNoOps() = runTest {
        val dismissed = mutableListOf<String>()
        val executor = LocalDismissCommandExecutor(items = { emptyList() }, dismissLocal = { dismissed.add(it) })
        executor.invokeAction("0|k", 0)
        executor.reply("0|k", 0, "text")
        executor.muteApp("com.example")
        executor.unmuteApp("com.example")
        assertTrue(dismissed.isEmpty())
    }
}
