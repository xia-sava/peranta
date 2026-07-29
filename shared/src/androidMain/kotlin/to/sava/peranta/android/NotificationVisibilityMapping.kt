package to.sava.peranta.android

import android.app.Notification
import to.sava.peranta.model.NotificationVisibility

/**
 * `Notification.visibility` の生値を [NotificationVisibility] に写す（§4.1）。
 * 未知の値は Android の既定と同じ [NotificationVisibility.PRIVATE] に丸める。
 */
fun notificationVisibilityOf(raw: Int): NotificationVisibility = when (raw) {
    Notification.VISIBILITY_PUBLIC -> NotificationVisibility.PUBLIC
    Notification.VISIBILITY_SECRET -> NotificationVisibility.SECRET
    else -> NotificationVisibility.PRIVATE
}

/** [NotificationVisibility] を `Notification.Builder.setVisibility` へ渡す生値に写す（§3.2）。 */
fun androidVisibilityOf(visibility: NotificationVisibility): Int = when (visibility) {
    NotificationVisibility.PUBLIC -> Notification.VISIBILITY_PUBLIC
    NotificationVisibility.PRIVATE -> Notification.VISIBILITY_PRIVATE
    NotificationVisibility.SECRET -> Notification.VISIBILITY_SECRET
}
