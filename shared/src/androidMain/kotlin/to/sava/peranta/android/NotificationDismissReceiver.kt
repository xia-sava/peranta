package to.sava.peranta.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import to.sava.peranta.model.SwipeBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ミラー通知から既読同期（§3.4）を発火する。「送信元の通知を消す」の押下と、送信端末が
 * [SwipeBehavior.DISMISS_SOURCE] を指示した通知の払いのけ（§3.3）の両方を受ける。
 * アプリの画面を開かずに起こされるため、プロセスが通知表示だけのために起きた状態でも動く。
 *
 * ボタン押下では表示中のミラー通知を先に取り下げる。払いのけでは OS が既に消しているため
 * 取り下げは要らず、そのぶん [EXTRA_PAYLOAD_ID] を伴わない。
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    private val log = Logger.withTag("NotificationDismiss")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY) ?: return
        val payloadId = intent.getStringExtra(EXTRA_PAYLOAD_ID)
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                payloadId?.let { AndroidNotificationPresenter(appContext).cancel(it) }
                PerantaReceive.dismissSourceNotification(appContext, notificationKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "failed to dismiss source notification" }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {

        private const val EXTRA_NOTIFICATION_KEY = "to.sava.peranta.extra.NOTIFICATION_KEY"
        private const val EXTRA_PAYLOAD_ID = "to.sava.peranta.extra.PAYLOAD_ID"

        /**
         * ボタン押下と払いのけを区別する action。[PendingIntent.filterEquals] は extra を見ないため、
         * action を分けないと同じ通知の 2 つの [PendingIntent] が同一視され、後から登録した側が
         * 先の内容を上書きしてしまう。
         */
        private const val ACTION_DISMISS_SOURCE = "to.sava.peranta.action.DISMISS_SOURCE"
        private const val ACTION_SWIPE_DISMISS_SOURCE = "to.sava.peranta.action.SWIPE_DISMISS_SOURCE"

        /**
         * 「送信元の通知を消す」押下で [notificationKey] の既読同期を起こす Intent。
         * [payloadId] はこの端末が表示したミラー通知を取り下げるために使う。
         */
        fun intent(context: Context, notificationKey: String, payloadId: String): Intent =
            Intent(context, NotificationDismissReceiver::class.java)
                .setAction(ACTION_DISMISS_SOURCE)
                .putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
                .putExtra(EXTRA_PAYLOAD_ID, payloadId)

        /**
         * 払いのけで [notificationKey] の既読同期を起こす Intent（§3.3）。
         * 払いのけでは OS が既に通知を消しているため、取り下げ対象の payload id は運ばない。
         */
        fun swipeIntent(context: Context, notificationKey: String): Intent =
            Intent(context, NotificationDismissReceiver::class.java)
                .setAction(ACTION_SWIPE_DISMISS_SOURCE)
                .putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
    }
}
