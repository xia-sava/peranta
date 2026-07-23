package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertFalse

/** 通知操作コマンド失敗時のユーザー向け文言に、生の notificationKey が含まれないことを検証する。 */
class NotificationCommandMessagesTest {

    /** StatusBarNotification.key 形式の生値を含めても、アクション失敗文言に紛れ込まない。 */
    @Test
    fun actionFailedMessageDoesNotExposeRawKey() {
        val rawKey = "0|com.google.android.calendar|612044461|V2AEventKey|10138"
        assertFalse(NOTIFICATION_ACTION_FAILED_MESSAGE.contains(rawKey))
        assertFalse(NOTIFICATION_ACTION_FAILED_MESSAGE.contains("key="))
    }

    /** 返信失敗文言についても、生の notificationKey を含めない。 */
    @Test
    fun replyFailedMessageDoesNotExposeRawKey() {
        val rawKey = "0|com.google.android.calendar|612044461|V2AEventKey|10138"
        assertFalse(NOTIFICATION_REPLY_FAILED_MESSAGE.contains(rawKey))
        assertFalse(NOTIFICATION_REPLY_FAILED_MESSAGE.contains("key="))
    }

    /** アクション番号が対象外だったときの文言にも key=/index= の形式を含めない。 */
    @Test
    fun actionIndexMissingMessageDoesNotExposeRawIdentifiers() {
        assertFalse(NOTIFICATION_ACTION_INDEX_MISSING_MESSAGE.contains("key="))
        assertFalse(NOTIFICATION_ACTION_INDEX_MISSING_MESSAGE.contains("index="))
    }

    /** 返信非対応の文言にも key=/index= の形式を含めない。 */
    @Test
    fun replyUnsupportedMessageDoesNotExposeRawIdentifiers() {
        assertFalse(NOTIFICATION_REPLY_UNSUPPORTED_MESSAGE.contains("key="))
        assertFalse(NOTIFICATION_REPLY_UNSUPPORTED_MESSAGE.contains("index="))
    }
}
