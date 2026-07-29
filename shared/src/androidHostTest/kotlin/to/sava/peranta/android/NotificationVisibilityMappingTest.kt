package to.sava.peranta.android

import android.app.Notification
import to.sava.peranta.model.NotificationVisibility
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 元通知のロック画面可視性の写し取りと戻し（§4.1・§3.2）。
 * 転送で運ぶ 3 段と Android の生値が往復することを固定する。
 */
class NotificationVisibilityMappingTest {

    /** 生値は対応する 3 段へ写る。 */
    @Test
    fun rawValuesMapToVisibility() {
        assertEquals(NotificationVisibility.PUBLIC, notificationVisibilityOf(Notification.VISIBILITY_PUBLIC))
        assertEquals(NotificationVisibility.PRIVATE, notificationVisibilityOf(Notification.VISIBILITY_PRIVATE))
        assertEquals(NotificationVisibility.SECRET, notificationVisibilityOf(Notification.VISIBILITY_SECRET))
    }

    /** 未知の生値は Android の既定と同じ「伏せる」へ丸める。 */
    @Test
    fun unknownRawValueFallsBackToPrivate() {
        assertEquals(NotificationVisibility.PRIVATE, notificationVisibilityOf(99))
    }

    /** 表示側へ戻すと元の生値に一致する。 */
    @Test
    fun visibilityMapsBackToRawValues() {
        assertEquals(Notification.VISIBILITY_PUBLIC, androidVisibilityOf(NotificationVisibility.PUBLIC))
        assertEquals(Notification.VISIBILITY_PRIVATE, androidVisibilityOf(NotificationVisibility.PRIVATE))
        assertEquals(Notification.VISIBILITY_SECRET, androidVisibilityOf(NotificationVisibility.SECRET))
    }
}
