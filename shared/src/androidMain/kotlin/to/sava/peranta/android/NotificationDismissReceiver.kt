package to.sava.peranta.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ミラー通知の「送信元の通知を消す」から既読同期（§3.4）を発火する。
 * アプリの画面を開かずに押されるため、プロセスが通知表示だけのために起きた状態でも動く。
 * 表示中のミラー通知は先に取り下げ、そのあと dismiss コマンドを全端末へ送る。
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    private val log = Logger.withTag("NotificationDismiss")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY) ?: return
        val payloadId = intent.getStringExtra(EXTRA_PAYLOAD_ID) ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                AndroidNotificationPresenter(appContext).cancel(payloadId)
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
         * [notificationKey] の既読同期を起こす Intent。[payloadId] はこの端末が表示した
         * ミラー通知を取り下げるために使う。
         */
        fun intent(context: Context, notificationKey: String, payloadId: String): Intent =
            Intent(context, NotificationDismissReceiver::class.java)
                .putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
                .putExtra(EXTRA_PAYLOAD_ID, payloadId)
    }
}
